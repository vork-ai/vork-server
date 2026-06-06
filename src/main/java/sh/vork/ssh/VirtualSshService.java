package sh.vork.ssh;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.sshtools.client.KeyPairAuthenticator;
import com.sshtools.client.PasswordAuthenticator;
import com.sshtools.client.SshClient;
import com.sshtools.client.SshClient.SshClientBuilder;
import com.sshtools.client.SshClientContext;
import com.sshtools.client.sftp.SftpClient;
import com.sshtools.client.sftp.SftpClient.SftpClientBuilder;
import com.sshtools.common.events.EventCodes;
import com.sshtools.common.files.AbstractFileFactory;
import com.sshtools.common.files.memory.InMemoryFileFactory;
import com.sshtools.common.knownhosts.HostKeyVerification;
import com.sshtools.common.permissions.PermissionDeniedException;
import com.sshtools.common.policy.FileFactory;
import com.sshtools.common.policy.FileSystemPolicy.FileSystemPolicyBuilder;
import com.sshtools.common.publickey.InvalidPassphraseException;
import com.sshtools.common.publickey.SshKeyPairGenerator;
import com.sshtools.common.publickey.SshKeyUtils;
import com.sshtools.common.ssh.Channel;
import com.sshtools.common.ssh.ChannelEventListener;
import com.sshtools.common.ssh.SshConnection;
import com.sshtools.common.ssh.SshException;
import com.sshtools.common.ssh.components.SshKeyPair;
import com.sshtools.common.ssh.components.SshPublicKey;
import com.sshtools.server.AbstractSshServer;
import com.sshtools.server.DefaultServerChannelFactory;
import com.sshtools.server.InMemoryPublicKeyAuthenticator;
import com.sshtools.server.SshServerContext;
import com.sshtools.server.vsession.commands.os.NativeSessionChannel;
import com.sshtools.synergy.nio.ConnectRequestFuture;
import com.sshtools.synergy.nio.SshEngineContext;

import io.micrometer.common.util.StringUtils;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.interaction.FieldSource;
import sh.vork.ai.protocol.interaction.FormAction;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;
import com.jadaptive.orm.DatabaseRepository;
import com.jadaptive.orm.SearchQuery;
import com.jadaptive.orm.SortOrder;
import sh.vork.security.SecureCredentialStore;
import sh.vork.security.VorkUser;

@Service
public class VirtualSshService extends AbstractSshServer {

	static Logger log = LoggerFactory.getLogger(VirtualSshService.class.getName());
	SshKeyPair authenticationKeyPair;
	SshKeyPair hostKeyPair;

	@Autowired
	private SecureCredentialStore credentialStore;
	@Autowired
	private DatabaseRepository<VorkNode> nodeRepository;

        /** sessionId → alias → SshClient */
        private final Map<String, Map<String, SshClient>> clientsByAlias = new ConcurrentHashMap<>();
        /** sessionId → canonical-host-string → SshClient */
        private final Map<String, Map<String, SshClient>> clientsByHost = new ConcurrentHashMap<>();
        /** sessionId → hostOrAlias → SftpClient */
        private final Map<String, Map<String, SftpClient>> sftpClientsBySession = new ConcurrentHashMap<>();
        /** SshConnection UUID → lookup key used by cleanupConnection to evict from all maps */
        private final Map<String, ConnectionKey> connectionRegistry = new ConcurrentHashMap<>();

        private record ConnectionKey(String sessionId, String alias, String canonicalHost) {}
	
	@PostConstruct
	private void init() throws IOException, SshException {
		authenticationKeyPair = SshKeyPairGenerator.generateKeyPair(SshKeyPairGenerator.ED25519);
		hostKeyPair = SshKeyPairGenerator.generateKeyPair(SshKeyPairGenerator.ED25519);
		start(false);
	}

	@Override
	public SshServerContext createServerContext(SshEngineContext daemonContext, SocketChannel sc)
			throws IOException, SshException {
		
		SshServerContext context = new SshServerContext(daemonContext.getEngine());
		context.addHostKey(hostKeyPair);
		context.getAuthenticationMechanismFactory()
			.addProvider(new InMemoryPublicKeyAuthenticator()
					.addAuthorizedKey("system", 
							authenticationKeyPair.getPublicKey()));
		
		context.setPolicy(FileSystemPolicyBuilder.create().withFileFactory(new FileFactory() {
			@Override
			public AbstractFileFactory<?> getFileFactory(SshConnection con)
					throws IOException, PermissionDeniedException {
				return new InMemoryFileFactory();
			}
			
		}).build());
		
		context.setChannelFactory(new DefaultServerChannelFactory() {
			@Override
			public NativeSessionChannel createSessionChannel(SshConnection con) {
				return new NativeSessionChannel(con);
			}
		});

		return context;
	}
	
	
	public SshClient connectLocal(int timeout) throws IOException, SshException, InterruptedException {
		
		SshClientContext context = new SshClientContext();

		context.setUsername("system");
		context.addAuthenticator(new KeyPairAuthenticator(authenticationKeyPair));
        ConnectRequestFuture future = acceptVirtualConnection(context);
        future.waitFor(Duration.ofSeconds(timeout));
        SshConnection con = future.getConnection();
		con.addEventListener((e)->{
			if(e.getId()==EventCodes.EVENT_DISCONNECTED) {
				log.info("Virtual SSH client disconnected: " + con.getUUID());
				cleanupConnection(con);
			}
		});
        con.getAuthenticatedFuture().waitFor(Duration.ofSeconds(timeout));

        return SshClientBuilder.create(con)
			.build();
	}

        /** Describes an active outbound SSH connection for this session. */
        public record SshConnectionInfo(String alias, String canonicalHost) {}

        /**
         * Returns all cached outbound SSH connections for the given session as a list
         * of (alias, canonicalHost) pairs.
         */
        public List<SshConnectionInfo> listConnections(String sessionId) {
                Map<String, SshClient> byAlias = clientsByAlias.get(sessionId);
                if (byAlias == null || byAlias.isEmpty()) return List.of();
                List<SshConnectionInfo> result = new ArrayList<>();
                for (Map.Entry<String, SshClient> entry : byAlias.entrySet()) {
                        String alias = entry.getKey();
                        String uuid = entry.getValue().getConnection().getUUID();
                        ConnectionKey key = connectionRegistry.get(uuid);
                        String canonicalHost = (key != null) ? key.canonicalHost() : alias;
                        result.add(new SshConnectionInfo(alias, canonicalHost));
                }
                return result;
        }

        /**
         * Renames the alias of an existing cached connection.  The connection is looked up
         * by its current alias or canonical host.  All caches (byAlias, sftpBySession, registry)
         * are updated atomically under the session-level map entry.
         */
        public void setAlias(String sessionId, String hostOrAlias, String newAlias) {
                SshClient client = getConnection(sessionId, hostOrAlias);
                if (client == null) {
                        throw new IllegalStateException("No active SSH connection found for '" + hostOrAlias + "'.");
                }
                String connectionUuid = client.getConnection().getUUID();
                ConnectionKey oldKey = connectionRegistry.get(connectionUuid);
                if (oldKey == null) {
                        throw new IllegalStateException("No registry entry found for connection '" + hostOrAlias + "'.");
                }
                Map<String, SshClient> byAlias = sessionAliasClients(sessionId);
                byAlias.remove(oldKey.alias());
                byAlias.put(newAlias, client);

                Map<String, SftpClient> sftpMap = sftpClientsBySession.get(sessionId);
                if (sftpMap != null) {
                        SftpClient sftp = sftpMap.remove(oldKey.alias());
                        if (sftp != null) sftpMap.put(newAlias, sftp);
                }
                connectionRegistry.put(connectionUuid,
                                new ConnectionKey(sessionId, newAlias, oldKey.canonicalHost()));
        }

        /**
         * Closes and evicts all resources associated with a connection (SFTP client, SSH client,
         * all map entries and registry entry).  The connection is identified by alias or canonical host.
         */
        public void disconnect(String sessionId, String hostOrAlias) {
                SshClient client = getConnection(sessionId, hostOrAlias);
                if (client == null) {
                        throw new IllegalStateException("No active SSH connection found for '" + hostOrAlias + "'.");
                }
                String connectionUuid = client.getConnection().getUUID();
                ConnectionKey key = connectionRegistry.remove(connectionUuid);

                Map<String, SftpClient> sftpMap = sftpClientsBySession.get(sessionId);
                if (sftpMap != null) {
                        List<String> sftpKeys = (key != null)
                                        ? List.of(key.alias(), key.canonicalHost())
                                        : List.of(hostOrAlias);
                        for (String k : sftpKeys) {
                                SftpClient sftp = sftpMap.remove(k);
                                if (sftp != null) {
                                        try { sftp.close(); } catch (Exception ignored) {}
                                }
                        }
                }

                if (key != null) {
                        Map<String, SshClient> byAlias = clientsByAlias.get(sessionId);
                        if (byAlias != null) byAlias.remove(key.alias());
                        Map<String, SshClient> byHost = clientsByHost.get(sessionId);
                        if (byHost != null) byHost.remove(key.canonicalHost());
                }

                try { client.close(); } catch (Exception ignored) {}
        }

	private void cleanupConnection(SshConnection con) {
		ConnectionKey key = connectionRegistry.remove(con.getUUID());
		if (key == null) return;

		Map<String, SshClient> byAlias = clientsByAlias.get(key.sessionId());
		if (byAlias != null) byAlias.remove(key.alias());

		Map<String, SshClient> byHost = clientsByHost.get(key.sessionId());
		if (byHost != null) byHost.remove(key.canonicalHost());

		Map<String, SftpClient> bySftp = sftpClientsBySession.get(key.sessionId());
		if (bySftp != null) {
			for (String k : List.of(key.alias(), key.canonicalHost())) {
				SftpClient sftp = bySftp.remove(k);
				if (sftp != null) {
					try { sftp.close(); } catch (Exception ignored) {}
				}
			}
		}
	}

	public SshClient connectClient(String username, String host, int timeout) throws IOException, SshException, InterruptedException {

		if (host == null || host.isBlank()) {
			throw new IllegalArgumentException("SSH host is required");
		}

		// If the caller passed an alias rather than a real hostname, resolve it to
		// the canonical host that was recorded when the connection was first cached.
		String sessionId = MDC.get("sessionUuid");
		if (sessionId != null && !sessionId.isBlank()) {
			String resolvedHost = resolveAliasToCanonicalHost(sessionId, host);
			if (resolvedHost != null) {
				host = resolvedHost;
			}
		}

		VorkUser principal = currentPrincipalUser();
		String normalizedHost = normalizeHost(host);
		int port = extractPort(normalizedHost);
		VorkNode node = resolveNode(principal, normalizedHost, port, username);
		String normalizedUser = node.username();

		String passwordSecretKey = secretKeyForPassword(node.uuid());
		String keySecretKey = secretKeyForPrivateKey(node.uuid());
		String passphraseSecretKey = secretKeyForPassphrase(node.uuid());

		SshClientContext context = new SshClientContext();
		context.setUsername(normalizedUser);
		
		String password = credentialStore.getSecret(principal, passwordSecretKey);	
		if(password != null) {
			 context.addAuthenticator(PasswordAuthenticator.forPassword(password));
		}

		String key = credentialStore.getSecret(principal, keySecretKey);
		String passphrase = credentialStore.getSecret(principal, passphraseSecretKey);
		boolean hasUsableAuth = password != null && !password.isBlank();

		if(key != null && !key.isBlank()) {
			try {
				SshKeyPair keyPair = SshKeyUtils.getPrivateKey(key, passphrase);
				context.addAuthenticator(new KeyPairAuthenticator(keyPair));
				hasUsableAuth = true;
			} catch (IOException e) { 
				if (!hasUsableAuth) {
					throw credentialsPrompt(node,
							"The stored private key is invalid. Provide a valid SSH private key or password.",
							false);
				}
			} catch(InvalidPassphraseException e) {
				if (!hasUsableAuth) {
					throw credentialsPrompt(node,
							"The private key requires a valid passphrase. Provide the key passphrase (or password instead).",
							true);
				}
			}
		}

		if(!hasUsableAuth || context.getAuthenticators().isEmpty()) {
			throw credentialsPrompt(node,
					"No stored SSH credentials were found for this user/host. Provide a password and/or private key.",
					false);
		}

		context.setHostKeyVerification(new HostKeyVerification() {
			@Override
			public boolean verifyHost(String host, SshPublicKey pk) throws SshException {
				try {
					return SshKeyUtils.getPublicKey(node.verifiedHostKey()).equals(pk);
				} catch (IOException e) {
					log.error("Failed to load verified host key for node: " + node.uuid(), e);
					return false;
				}
			}
		});

	        SshClient client;
	        try {
	        	client = SshClientBuilder.create()
	        		.withTarget(normalizedHost, port)
	        		.withUsername(normalizedUser)
	        		.withSshContext(context)
	        		.withConnectTimeout(Duration.ofSeconds(timeout))
	        		.build();
	        } catch (IOException | SshException ex) {
	        	throw credentialsPrompt(node,
	        			"Stored credentials could not authenticate to the remote host. Provide updated password and/or key credentials.",
	        			false);
	        }
	        SshConnection con = client.getConnection();
	        con.getAuthenticatedFuture().waitFor(Duration.ofSeconds(timeout));

		if(con.getAuthenticatedFuture().isSuccess() && con.getAuthenticatedFuture().isDone()) {
			return client;
		} else {
			throw credentialsPrompt(node,
					"SSH authentication failed. Provide valid password and/or private key credentials.",
					false);
		}
	}

	private int extractPort(String normalizedHost) {
		return normalizedHost.contains(":") ? Integer.parseInt(normalizedHost.substring(normalizedHost.lastIndexOf(':') + 1)) : 22;
	}

        /**
         * Parses a {@code user@host:port} / {@code user@host} / {@code host:port} / {@code host}
         * connection string, establishes an SSH connection via existing credential/prompt logic,
         * and caches the resulting {@link SshClient} keyed by both the supplied alias and the
         * canonical host string.
         *
         * @param sessionId    the AI session identifier (from MDC)
         * @param hostString   connection string (e.g. {@code alice@dev.example.com:2222})
         * @param alias        short name for the connection; if blank, {@code hostString} is used
         * @return the connected {@link SshClient}
         */
        public SshClient connectAndCache(String sessionId, String hostString, String alias)
                        throws IOException, SshException, InterruptedException {
                String parsedUser = extractUserFromHostString(hostString);
                String hostPort = extractHostPortFromHostString(hostString);
                SshClient client = connectClient(parsedUser, hostPort, 30);

                String effectiveAlias = (alias == null || alias.isBlank()) ? hostString : alias.trim();
                String canonicalHost = normalizeHost(hostPort);

                sessionAliasClients(sessionId).put(effectiveAlias, client);
                sessionHostClients(sessionId).put(canonicalHost, client);
                connectionRegistry.put(client.getConnection().getUUID(),
                                new ConnectionKey(sessionId, effectiveAlias, canonicalHost));

                return client;
        }

        /**
         * Returns a cached {@link SshClient} for the given session, looking up first by alias
         * then by canonical host string. Returns {@code null} if no connection is found.
         */
        public SshClient getConnection(String sessionId, String hostOrAlias) {
                Map<String, SshClient> byAlias = clientsByAlias.get(sessionId);
                if (byAlias != null) {
                        SshClient c = byAlias.get(hostOrAlias);
                        if (c != null) return c;
                }
                Map<String, SshClient> byHost = clientsByHost.get(sessionId);
                if (byHost != null) {
                        SshClient c = byHost.get(normalizeHost(hostOrAlias));
                        if (c != null) return c;
                }
                return null;
        }

        /**
         * Returns (or lazily opens) an {@link SftpClient} for the given session and host/alias.
         * The underlying {@link SshClient} must already be cached via {@link #connectAndCache}.
         *
         * @throws IllegalStateException if no SSH connection is cached for the given identifier
         */
        public SftpClient getSftpClient(String sessionId, String hostOrAlias)
                        throws IOException, SshException, PermissionDeniedException {
                Map<String, SftpClient> sftpMap = sftpClientsBySession.computeIfAbsent(
                                sessionId, k -> new ConcurrentHashMap<>());

                SftpClient existing = sftpMap.get(hostOrAlias);
                if (existing != null) return existing;

                SshClient sshClient = getConnection(sessionId, hostOrAlias);
                if (sshClient == null) {
                        throw new IllegalStateException("No active SSH connection found for '"
                                + hostOrAlias + "'. Use connectSsh to establish a connection first.");
                }

                SftpClient sftp = SftpClientBuilder.create()
                                .withClient(sshClient)
								.withEventListener(new ChannelEventListener() {
									public void onChannelClose(Channel channel) {	
										cleanupSftpClient(channel);
									}
								})
                                .build();

                sftpMap.put(hostOrAlias, sftp);
                return sftp;
        }

		private void cleanupSftpClient(Channel channel) {
			String uuid = channel.getConnection().getUUID();
			ConnectionKey key = connectionRegistry.get(uuid);
			if (key == null) return;
			log.info("Cleaning up SFTP client for session {}, host/alias {}", key.sessionId(), key.alias());
			Map<String, SftpClient> sftpMap = sftpClientsBySession.get(key.sessionId());
			if (sftpMap != null) {
				for (String k : List.of(key.alias(), key.canonicalHost())) {
					SftpClient sftp = sftpMap.remove(k);
					if (sftp != null) {
						try { sftp.close(); } catch (Exception ignored) {}
					}
				}
			}
		}

        private Map<String, SshClient> sessionAliasClients(String sessionId) {
                return clientsByAlias.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
        }

        private Map<String, SshClient> sessionHostClients(String sessionId) {
                return clientsByHost.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
        }

        /**
         * If {@code hostOrAlias} matches a cached alias for the given session, returns the
         * canonical host string stored in the connection registry.  Returns {@code null} if
         * the value is not a known alias (caller should treat it as a literal hostname).
         */
        private String resolveAliasToCanonicalHost(String sessionId, String hostOrAlias) {
                Map<String, SshClient> byAlias = clientsByAlias.get(sessionId);
                if (byAlias == null) return null;
                SshClient client = byAlias.get(hostOrAlias);
                if (client == null) return null;
                ConnectionKey key = connectionRegistry.get(client.getConnection().getUUID());
                return (key != null) ? key.canonicalHost() : null;
        }

        private static String extractUserFromHostString(String hostString) {
                if (hostString == null) return null;
                int atIdx = hostString.indexOf('@');
                return atIdx >= 0 ? hostString.substring(0, atIdx).trim() : null;
        }

        private static String extractHostPortFromHostString(String hostString) {
                if (hostString == null) return null;
                int atIdx = hostString.indexOf('@');
                return atIdx >= 0 ? hostString.substring(atIdx + 1).trim() : hostString.trim();
        }

        private SshPublicKey getHostKey(String host, int port, int timeout) throws IOException, SshException {
                return SshClientBuilder.create()
                                .withTarget(host, port)
                                .withUsername("guest")
                                .withConnectTimeout(Duration.ofSeconds(timeout))
                                .build().getHostKey();
        }
	private ToolSuspensionException credentialsPrompt(VorkNode node,
											 String description,
											 boolean passphraseRequired) {
		String passwordKey = secretKeyForPassword(node.uuid());
		String keyKey = secretKeyForPrivateKey(node.uuid());
		String passphraseKey = secretKeyForPassphrase(node.uuid());

		List<FormField> fields = new ArrayList<>();
		fields.add(new FormField(passwordKey, "password", "SSH Password", "Optional password", false, FieldSource.SECRET, List.of()));
		fields.add(new FormField(keyKey, "textarea", "SSH Private Key", "Optional private key PEM/OpenSSH text", false, FieldSource.SECRET, List.of()));
		fields.add(new FormField(passphraseKey, "password", "SSH Key Passphrase", "Optional key passphrase", passphraseRequired, FieldSource.SECRET, List.of()));

		InteractionFormSchema formSchema = new InteractionFormSchema(
				"AUTHORIZE_TOOL",
				"SSH Credentials Required",
				description,
				fields,
				List.of(
						new FormAction("ONCE", "Save & Continue", "primary"),
						new FormAction("DENIED", "Cancel", "danger")
				));

		return new ToolSuspensionException("connectSsh", "{}", description, formSchema);
	}

	private VorkNode resolveNode(VorkUser principal, String host, int port, String username) throws IOException, SshException {

		SshPublicKey currentHostKey = getHostKey(host, port, 5);

		List<VorkNode> nodes = listNodesForHost(principal, host);
		String resolvedUsername = resolveRequestedUsername(host, username);

		if (resolvedUsername == null || resolvedUsername.isBlank()) {
			if (nodes.size() == 1) {
				return verifyHostKey(nodes.get(0), currentHostKey);
			}
			throw usernamePrompt(host, nodes);
		}

		for (VorkNode node : nodes) {
			if (resolvedUsername.equals(node.username())) {
				return verifyHostKey(node, currentHostKey);
			}
		}

		Object approval = ToolExecutionContext.get("HOST_KEY_VERIFICATION");
	
		if ("true".equals(approval)) {
				long now = System.currentTimeMillis();
				VorkNode created = new VorkNode(
						UUID.randomUUID().toString(),
						principal.uuid(),
						host,
						resolvedUsername,
						now,
						now, 
						SshKeyUtils.getFormattedKey(currentHostKey, "Vork SSH Host Key"));
				nodeRepository.save(created);
				return created;
		}

		throw hostKeyPrompt(host, currentHostKey, null);	
	}

	private VorkNode verifyHostKey(VorkNode node, SshPublicKey currentHostKey) throws IOException, SshException {
		
		if(StringUtils.isNotBlank(node.verifiedHostKey()) 
			&&  SshKeyUtils.getPublicKey(node.verifiedHostKey()).equals(currentHostKey))	{
			return node;
		} else {
			throw hostKeyPrompt(node.host(), currentHostKey, node.verifiedHostKey());
		}
	}

	private List<VorkNode> listNodesForHost(VorkUser principal, String host) {
		try (Stream<VorkNode> stream = nodeRepository.search(
				0,
				200,
				"createdAt",
				SortOrder.ASC,
				SearchQuery.eq("ownerUserUuid", principal.uuid()),
				SearchQuery.eq("host", host))) {
			return stream.toList();
		}
	}

	private static String resolveRequestedUsername(String host, String explicitUsername) {
		if (explicitUsername != null && !explicitUsername.isBlank()) {
			return explicitUsername.trim();
		}
		String contextKey = usernameContextKeyForHost(host);
		Object contextValue = ToolExecutionContext.get(contextKey);
		if (contextValue == null) {
			return null;
		}
		String username = String.valueOf(contextValue).trim();
		return username.isBlank() ? null : username;
	}

	private static ToolSuspensionException usernamePrompt(String host, List<VorkNode> nodes) {
		String key = usernameContextKeyForHost(host);
		List<String> knownUsers = nodes.stream()
				.map(VorkNode::username)
				.distinct()
				.sorted()
				.toList();

		String description;
		String placeholder;
		if (nodes.isEmpty()) {
			description = "Provide the SSH username for host '" + host + "'. A new node entry will be created.";
			placeholder = "e.g. root, ubuntu, ec2-user";
		} else {
			description = "Multiple SSH usernames exist for host '" + host + "'. Choose one or provide a new username.";
			placeholder = "Known: " + String.join(", ", knownUsers);
		}

		InteractionFormSchema formSchema = new InteractionFormSchema(
				"AUTHORIZE_TOOL",
				"SSH Username Required",
				description,
				List.of(new FormField(
						key,
						"text",
						"SSH Username",
						placeholder,
						true,
						FieldSource.CONTEXT,
						knownUsers)),
				List.of(
						new FormAction("ONCE", "Continue", "primary"),
						new FormAction("DENIED", "Cancel", "danger")));

		return new ToolSuspensionException(
				"connectSsh",
				"",
				"An SSH username is required before credentials can be resolved.",
				formSchema);
	}

	private static ToolSuspensionException hostKeyPrompt(String host, SshPublicKey currentHostKey, String verifiedHostKey) throws IOException, SshException {

		String description;
		String placeholder;
		if (verifiedHostKey == null) {
			placeholder = "The authenticity of host '" + host + "' can't be established.";
			description = currentHostKey.getAlgorithm() + " key fingerprint is " + currentHostKey.getFingerprint();
		} else {
			SshPublicKey verifiedKey = SshKeyUtils.getPublicKey(verifiedHostKey);
			placeholder = "The identity of host '\" + host + \"' does not match the verified host key.";
			description = "Expected " + verifiedKey.getAlgorithm() + "/" + verifiedKey + " but recieved" +
							 currentHostKey.getAlgorithm() + "/" + currentHostKey.getFingerprint();
		}

		List<FormField> fields = List.of(
			new FormField(
				"HOST_KEY_VERIFICATION",
				"HIDDEN",
				"",
				"true",
				false,
				FieldSource.CONTEXT, // CRITICAL: This bypasses the LLM history!
				Collections.emptyList()
			)
		);

		InteractionFormSchema schema = new InteractionFormSchema(
			"HOST_KEY_VERIFICATION",
			"SSH Host Key Verification",
			description,
			fields,
			List.of(new FormAction("ONCE", "Trust & Save Node", "warning"),
						new FormAction("DENIED", "Cancel", "danger"))
		);

		return new ToolSuspensionException(
				"connectSsh",
				"",
				placeholder,
				schema);
	}

	private static String secretKeyForPassword(String nodeUuid) {
		return String.format("ssh-password-%s", nodeUuid);
	}

	private static String secretKeyForPrivateKey(String nodeUuid) {
		return String.format("ssh-key-%s", nodeUuid);
	}

	private static String secretKeyForPassphrase(String nodeUuid) {
		return String.format("ssh-passphrase-%s", nodeUuid);
	}

	private static String usernameContextKeyForHost(String host) {
		return "ssh-node-username-" + host.replaceAll("[^A-Za-z0-9_-]", "_");
	}

	private static String normalizeHost(String host) {
		return host.trim().toLowerCase(Locale.ROOT);
	}

	private static VorkUser currentPrincipalUser() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
			throw new IllegalStateException("No authenticated principal available for credential lookup");
		}
		return new VorkUser(auth.getName(), "", "USER", 0L, 0L);
	}
}
