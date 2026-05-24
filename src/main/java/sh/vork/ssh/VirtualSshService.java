package sh.vork.ssh;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.time.Duration;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import com.sshtools.client.KeyPairAuthenticator;
import com.sshtools.client.SshClient;
import com.sshtools.client.SshClient.SshClientBuilder;
import com.sshtools.client.SshClientContext;
import com.sshtools.common.files.AbstractFileFactory;
import com.sshtools.common.files.memory.InMemoryFileFactory;
import com.sshtools.common.permissions.PermissionDeniedException;
import com.sshtools.common.policy.FileFactory;
import com.sshtools.common.policy.FileSystemPolicy.FileSystemPolicyBuilder;
import com.sshtools.common.publickey.SshKeyPairGenerator;
import com.sshtools.common.ssh.SshConnection;
import com.sshtools.common.ssh.SshException;
import com.sshtools.common.ssh.components.SshKeyPair;
import com.sshtools.server.AbstractSshServer;
import com.sshtools.server.DefaultServerChannelFactory;
import com.sshtools.server.InMemoryPublicKeyAuthenticator;
import com.sshtools.server.SshServerContext;
import com.sshtools.server.vsession.commands.os.NativeSessionChannel;
import com.sshtools.synergy.nio.ConnectRequestFuture;
import com.sshtools.synergy.nio.SshEngineContext;

@Service
public class VirtualSshService extends AbstractSshServer {

	SshKeyPair authenticationKeyPair;
	SshKeyPair hostKeyPair;

	@Autowired
	ApplicationContext appContext;
	
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
		
		// context.setChannelFactory(new VirtualChannelFactory(new CommandProvider<ShellCommand>() {
		// 	@Override
		// 	public ShellCommand createCommand(String command, SshConnection con) throws UnsupportedCommandException {

		// 		VirtualCommand inst = appContext.getBeansOfType(VirtualCommandFactory.class)
		// 	            .values()
		// 	            .stream()
		// 	            .filter(factory -> factory.getName().equals(command))
		// 	            .findFirst()
		// 	            .orElseThrow(() -> new UnsupportedCommandException(command))
		// 	            .createCommand(command, con);

	    //         appContext.getAutowireCapableBeanFactory().autowireBean(inst);
	            
	    //         return inst;
		// 	}

		// 	@Override
		// 	public Set<String> getSupportedCommands() {
		// 		return appContext.getBeansOfType(VirtualCommandFactory.class)
		// 	            .values()
		// 	            .stream()
		// 	            .map(VirtualCommandFactory::getName) // Inherited from ShellCommand
		// 	            .collect(Collectors.toSet());
		// 	}

		// 	@Override
		// 	public boolean supportsCommand(String command) {
		// 		return getSupportedCommands().contains(command);
		// 	}
		// }));
		return context;
	}
	
	
	public SshClient connectClient(int timeout) throws IOException, SshException, InterruptedException {
		
		SshClientContext context = new SshClientContext();
		context.setUsername("system");
		context.addAuthenticator(new KeyPairAuthenticator(authenticationKeyPair));
        ConnectRequestFuture future = acceptVirtualConnection(context);
        future.waitFor(Duration.ofSeconds(timeout));
        SshConnection con = future.getConnection();
        con.getAuthenticatedFuture().waitFor(Duration.ofSeconds(timeout));

        return SshClientBuilder.create(con).build();
	}
}
