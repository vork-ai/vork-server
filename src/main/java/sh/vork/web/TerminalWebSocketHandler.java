package sh.vork.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.sshtools.client.SessionChannelNG;
import com.sshtools.client.SshClient;

import sh.vork.ssh.VirtualSshService;

/**
 * Relays I/O between a browser xterm.js WebSocket client and a Maverick Synergy
 * virtual SSH shell session.  One SSH session is created per WebSocket connection.
 *
 * <p>Text frames from the browser are forwarded to the shell's stdin.
 * A single JSON control message is understood:
 * <pre>{"type":"resize","cols":N,"rows":N}</pre>
 * Any other text frame is treated as raw terminal input.
 *
 * <p>Binary frames are also forwarded to stdin verbatim.
 *
 * <p>Shell stdout is read on a virtual thread and sent to the browser as UTF-8
 * text frames.
 */
@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    /** Default PTY column count used when establishing the session. */
    static final int DEFAULT_COLS = 220;
    /** Default PTY row count used when establishing the session. */
    static final int DEFAULT_ROWS = 50;
    private static final int SSH_TIMEOUT_SECS = 10;

    @Autowired
    private VirtualSshService sshService;

    private final ConcurrentHashMap<String, TerminalSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
        SshClient sshClient = sshService.connectClient(SSH_TIMEOUT_SECS);
        SessionChannelNG channel = sshClient.openSessionChannel();

        channel.allocatePseudoTerminal("xterm-256color", DEFAULT_COLS, DEFAULT_ROWS)
               .waitFor(Duration.ofSeconds(SSH_TIMEOUT_SECS));
        channel.startShell()
               .waitFor(Duration.ofSeconds(SSH_TIMEOUT_SECS));

        InputStream  shellOutput = channel.getInputStream();
        OutputStream shellInput  = channel.getOutputStream();

        sessions.put(wsSession.getId(), new TerminalSession(sshClient, channel, shellInput));

        // Forward shell stdout → browser on a virtual thread (non-blocking)
        Thread.ofVirtual()
              .name("terminal-out-" + wsSession.getId())
              .start(() -> forwardOutput(shellOutput, wsSession));
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) throws Exception {
        TerminalSession ts = sessions.get(wsSession.getId());
        if (ts == null) return;

        String payload = message.getPayload();

        // JSON control message check (resize only)
        if (payload.startsWith("{") && payload.contains("\"resize\"")) {
            int cols = extractJsonInt(payload, "cols", DEFAULT_COLS);
            int rows = extractJsonInt(payload, "rows", DEFAULT_ROWS);
            ts.channel().changeTerminalDimensions(cols, rows, 0, 0);
            return;
        }

        ts.shellInput().write(payload.getBytes(StandardCharsets.UTF_8));
        ts.shellInput().flush();
    }

    @Override
    public void handleBinaryMessage(WebSocketSession wsSession, BinaryMessage message) {
        TerminalSession ts = sessions.get(wsSession.getId());
        if (ts == null) return;
        byte[] data = new byte[message.getPayload().remaining()];
        message.getPayload().get(data);
        try {
            ts.shellInput().write(data);
            ts.shellInput().flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
        TerminalSession ts = sessions.remove(wsSession.getId());
        if (ts != null) ts.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void forwardOutput(InputStream shellOutput, WebSocketSession wsSession) {
        byte[] buf = new byte[4096];
        int n;
        try {
            while ((n = shellOutput.read(buf)) != -1) {
                if (wsSession.isOpen()) {
                    wsSession.sendMessage(new TextMessage(new String(buf, 0, n, StandardCharsets.UTF_8)));
                }
            }
        } catch (IOException ignored) {
            // shell closed or WebSocket closed — normal teardown
        } finally {
            try { wsSession.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Extracts a numeric value for {@code key} from a minimal JSON payload.
     * Returns {@code defaultVal} if the key is absent or unparseable.
     */
    static int extractJsonInt(String json, String key, int defaultVal) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return defaultVal;
        int start = idx + search.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        if (start == end) return defaultVal;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    // ── Inner record ──────────────────────────────────────────────────────────

    private record TerminalSession(SshClient client, SessionChannelNG channel, OutputStream shellInput) {
        void close() {
            try { channel.close(); } catch (Exception ignored) {}
            try { client.disconnect(); } catch (Exception ignored) {}
        }
    }
}
