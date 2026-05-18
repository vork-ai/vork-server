package sh.vork.ai.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.sshtools.client.SessionChannelNG;
import com.sshtools.client.SshClient;
import com.sshtools.client.shell.ExpectShell;
import com.sshtools.client.shell.ShellProcess;
import com.sshtools.common.ssh.SshException;

import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.websocket.TerminalBinarySocketRegistry;
import sh.vork.ssh.VirtualSshService;

@Service
public class TerminalStreamRouter {

    private static final Logger log = LoggerFactory.getLogger(TerminalStreamRouter.class);
    private static final int BUFFER_SIZE = 1024;
    private static final java.util.regex.Pattern ANSI_ESCAPE_PATTERN = java.util.regex.Pattern.compile("\\u001B\\[[0-9;?]*[ -/]*[@-~]");

    private final VirtualSshService virtualSshService;
    private final SimpMessagingTemplate messagingTemplate;
    private final TerminalOutputStore terminalOutputStore;
    private final TerminalBinarySocketRegistry terminalBinarySocketRegistry;

    private final Map<String, ActiveTerminalSession> sessionsByCompositeKey = new ConcurrentHashMap<>();
    private final Map<String, ActiveTerminalSession> sessionsBySessionUuid = new ConcurrentHashMap<>();

    @Autowired
    public TerminalStreamRouter(VirtualSshService virtualSshService,
                                SimpMessagingTemplate messagingTemplate,
                                TerminalOutputStore terminalOutputStore,
                                TerminalBinarySocketRegistry terminalBinarySocketRegistry) {
        this.virtualSshService = virtualSshService;
        this.messagingTemplate = messagingTemplate;
        this.terminalOutputStore = terminalOutputStore;
        this.terminalBinarySocketRegistry = terminalBinarySocketRegistry;
    }

    public TerminalStreamRouter(VirtualSshService virtualSshService,
                                SimpMessagingTemplate messagingTemplate) {
        this(virtualSshService, messagingTemplate, null, null);
    }

    public String executeStreamedCommand(String sessionUuid,
                                         String host,
                                         String command,
                                         SessionOriginMode originMode) {
        ActiveTerminalSession session = getOrCreateShell(sessionUuid, host);
        TerminalOutputStore.TerminalOutputWriter outputWriter = terminalOutputStore == null
            ? null
            : terminalOutputStore.createWriter(sessionUuid, command);
        StringBuilder fallbackOutput = outputWriter == null ? new StringBuilder() : null;
        String terminalId = UUID.randomUUID().toString();
        ShellProcess process;

        if (originMode == SessionOriginMode.WEB && messagingTemplate != null) {
            messagingTemplate.convertAndSend("/topic/chat/" + sessionUuid,
                    Map.of("type", "EVENT", "status", "TERMINAL_START", "command", command, "terminalId", terminalId));
        }

        synchronized (session.lock()) {
            try {
                process = session.shell().executeCommand(command, false);
                session.setActiveProcess(process, terminalId);
                session.setActiveInput(process.getOutputStream());
            } catch (SshException | IOException ex) {
                throw new IllegalStateException("Failed to start terminal command stream", ex);
            }
        }

        // Stream stats for instrumentation
        long streamStartTime = System.currentTimeMillis();
        long frameCount = 0;
        long totalBytes = 0;

        try (InputStream stdout = process.getInputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (true) {
                int bytesRead = stdout.read(buffer);
                if (bytesRead == -1) {
                    break;
                }
                if (bytesRead == 0) {
                    if (!process.isActive()) {
                        break;
                    }
                    continue;
                }

                byte[] payload = Arrays.copyOf(buffer, bytesRead);
                String chunk = new String(payload, StandardCharsets.UTF_8);

                frameCount++;
                totalBytes += bytesRead;

                if (outputWriter != null) {
                    try {
                        outputWriter.write(chunk);
                        outputWriter.flush();
                    } catch (IOException ex) {
                        log.warn("Failed to write terminal output to file: {}", ex.getMessage());
                    }
                } else {
                    fallbackOutput.append(chunk);
                }

                if (originMode == SessionOriginMode.WEB && terminalBinarySocketRegistry != null) {
                    terminalBinarySocketRegistry.broadcast(sessionUuid, terminalId, payload);
                } else if (originMode == SessionOriginMode.WEB && messagingTemplate != null) {
                    messagingTemplate.convertAndSend("/topic/chat/" + sessionUuid,
                            Map.of(
                                    "type", "EVENT",
                                    "status", "TERMINAL_DATA",
                                    "terminalId", terminalId,
                                    "chunk", chunk));
                }

                if (!process.isActive() && stdout.available() == 0) {
                    break;
                }
            }
            process.waitFor();
            
            // Final stream stats
            long elapsedMs = System.currentTimeMillis() - streamStartTime;
            long avgFrameSize = frameCount > 0 ? totalBytes / frameCount : 0;
            long throughputKbps = elapsedMs > 0 ? (totalBytes * 8) / elapsedMs : 0;
            log.info("Shell stream COMPLETED [session={}, terminal={}]: frames={}, totalBytes={}, avgSize={} bytes, throughput={}Kbps, elapsed={}ms",
                    sessionUuid, terminalId, frameCount, totalBytes, avgFrameSize, throughputKbps, elapsedMs);
        } catch (IOException ex) {
            throw new RuntimeException("Terminal execution stream broken", ex);
        } finally {
            synchronized (session.lock()) {
                session.clearActiveProcess();
            }
        }

        // Finalize output file and get the stored file UUID
        String outputFileUuid = null;
        if (terminalOutputStore != null && outputWriter != null) {
            try {
                sh.vork.storage.StoredFile storedFile = terminalOutputStore.finalize(outputWriter);
                if (storedFile != null) {
                    outputFileUuid = storedFile.uuid();
                    log.info("Terminal output file stored [command={}, uuid={}]", command, outputFileUuid);
                }
            } catch (Exception ex) {
                log.error("Failed to finalize terminal output file: {}", ex.getMessage(), ex);
            }
        }

        if (originMode == SessionOriginMode.WEB && messagingTemplate != null) {
            messagingTemplate.convertAndSend("/topic/chat/" + sessionUuid,
                    Map.of(
                            "type", "EVENT",
                            "status", "TERMINAL_END",
                    "terminalId", terminalId));
        }

        // Return JSON response with file UUID (if available) for tool response
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("status", "COMPLETED");
        response.put("command", command);
        if (outputFileUuid != null) {
            response.put("outputFileUuid", outputFileUuid);
        }
        if (fallbackOutput != null) {
            response.put("output", sanitizeForModel(fallbackOutput.toString()));
        }
        
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(response);
        } catch (Exception ex) {
            log.error("Failed to serialize terminal response: {}", ex.getMessage());
            return sanitizeForModel("");
        }
    }

    public void writeInput(String sessionUuid, byte[] payload) {
        writeInput(sessionUuid, null, payload);
    }

    public void writeInput(String sessionUuid, String terminalId, byte[] payload) {
        if (payload == null || payload.length == 0) {
            return;
        }
        ActiveTerminalSession session = sessionsBySessionUuid.get(sessionUuid);
        if (session == null) {
            log.debug("Ignoring terminal input because no active session was found [session={}]", sessionUuid);
            return;
        }

        synchronized (session.lock()) {
            if (terminalId != null && !terminalId.isBlank() && !terminalId.equals(session.activeTerminalId())) {
                log.debug("Ignoring terminal input because the command is no longer active [session={}, terminalId={}]",
                        sessionUuid, terminalId);
                return;
            }
            OutputStream in = session.activeInput();
            if (in == null) {
                log.debug("Ignoring terminal input because no active command stdin is available [session={}]", sessionUuid);
                return;
            }
            try {
                in.write(payload);
                in.flush();
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to write terminal input", ex);
            }
        }
    }

    private ActiveTerminalSession getOrCreateShell(String sessionUuid, String host) {
        String normalizedHost = (host == null || host.isBlank()) ? "local" : host.trim();
        String key = sessionUuid + "|" + normalizedHost;

        ActiveTerminalSession existing = sessionsByCompositeKey.get(key);
        if (existing != null && !existing.isClosed()) {
            sessionsBySessionUuid.put(sessionUuid, existing);
            return existing;
        }

        ActiveTerminalSession created = createShellSession(sessionUuid, normalizedHost);
        sessionsByCompositeKey.put(key, created);
        sessionsBySessionUuid.put(sessionUuid, created);
        return created;
    }

    static String sanitizeForModel(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }

        String sanitized = ANSI_ESCAPE_PATTERN.matcher(output).replaceAll("");
        sanitized = sanitized.replace('\u0000', ' ');
        sanitized = sanitized.replaceAll("\\r\\n", "\n");
        sanitized = sanitized.replace('\r', '\n');
        sanitized = sanitized.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        sanitized = sanitized.trim();

        if (sanitized.isBlank() && !output.isBlank()) {
            return "[terminal output omitted: control characters only]";
        }
        return sanitized;
    }

    static String selectUiOutput(String command, String output) {
        String normalized = normalizeUiOutput(command, output);
        if (normalized.isBlank()) {
            return "";
        }
        return normalized;
    }

    static boolean hasDisplayableContent(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return false;
        }
        String cleaned = ANSI_ESCAPE_PATTERN.matcher(chunk).replaceAll("");
        cleaned = cleaned.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        return !cleaned.trim().isBlank();
    }

    static String normalizeUiOutput(String command, String output) {
        String normalized = output == null ? "" : output;
        if (normalized.isBlank()) {
            return "";
        }

        normalized = normalized.replace("\r\n", "\n").replace('\r', '\n');

        if (command != null && !command.isBlank()) {
            while (!normalized.isBlank()) {
                int newlineIndex = normalized.indexOf('\n');
                String firstLine = newlineIndex >= 0 ? normalized.substring(0, newlineIndex) : normalized;
                String firstLineWithoutPrompt = firstLine.replaceFirst("^\\s*[$#>%]\\s*", "").trim();

                if (!firstLineWithoutPrompt.contains(command)) {
                    break;
                }

                if (newlineIndex < 0) {
                    return "";
                }
                normalized = normalized.substring(newlineIndex + 1);
            }
        }

        return normalized;
    }

    private ActiveTerminalSession createShellSession(String sessionUuid, String host) {
        try {
            SshClient client = virtualSshService.connectClient(10);
            SessionChannelNG channel = client.openSessionChannel();
            channel.allocatePseudoTerminal("xterm-256color", 120, 40).waitFor(Duration.ofSeconds(5));
            channel.startShell();

            ExpectShell shell = new ExpectShell(channel, ExpectShell.OS_LINUX);
            log.info("Created terminal stream shell [session={}, host={}]", sessionUuid, host);
            return new ActiveTerminalSession(sessionUuid, host, client, channel, shell);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize terminal shell session", ex);
        }
    }

    private record ActiveTerminalSession(
            String sessionUuid,
            String host,
            SshClient client,
            SessionChannelNG channel,
            ExpectShell shell,
            Object lock,
            ActiveProcessRef activeProcessRef
    ) {
        private ActiveTerminalSession(String sessionUuid,
                                      String host,
                                      SshClient client,
                                      SessionChannelNG channel,
                                      ExpectShell shell) {
            this(sessionUuid, host, client, channel, shell, new Object(), new ActiveProcessRef());
        }

        private void setActiveProcess(ShellProcess process, String terminalId) {
            activeProcessRef.terminalId = terminalId;
        }

        private void setActiveInput(OutputStream input) {
            activeProcessRef.stdin = input;
        }

        private OutputStream activeInput() {
            return activeProcessRef.stdin;
        }

        private String activeTerminalId() {
            return activeProcessRef.terminalId;
        }

        private void clearActiveProcess() {
            activeProcessRef.stdin = null;
            activeProcessRef.terminalId = null;
        }

        private boolean isClosed() {
            return shell == null || shell.isClosed() || client == null || !client.isConnected();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ActiveTerminalSession other)) {
                return false;
            }
            return Objects.equals(sessionUuid, other.sessionUuid)
                    && Objects.equals(host, other.host);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sessionUuid, host);
        }
    }

    private static final class ActiveProcessRef {
        private OutputStream stdin;
        private String terminalId;
    }
}
