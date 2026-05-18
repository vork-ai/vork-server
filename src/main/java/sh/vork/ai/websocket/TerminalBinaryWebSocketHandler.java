package sh.vork.ai.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import sh.vork.ai.terminal.TerminalStreamRouter;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Binary WebSocket handler for terminal I/O.
 * Accepts raw binary terminal input frames and forwards them to the shell stdin.
 *
 * <p>Protocol:
 * - Client sends BinaryMessage with UTF-8 encoded terminal input (keystrokes, commands, etc.)
 * - Server forwards directly to the active shell process stdin
 * - No sequencing needed; binary WebSocket frames are inherently reliable
 *
 * <p>Session routing:
 * - URL path includes sessionUuid and terminalId:
 *   {@code /ws/terminal/{sessionUuid}/{terminalId}}
 */
@Component
public class TerminalBinaryWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TerminalBinaryWebSocketHandler.class);

    private final TerminalStreamRouter terminalStreamRouter;
    private final TerminalBinarySocketRegistry socketRegistry;

    public TerminalBinaryWebSocketHandler(@Lazy TerminalStreamRouter terminalStreamRouter,
                                          TerminalBinarySocketRegistry socketRegistry) {
        this.terminalStreamRouter = terminalStreamRouter;
        this.socketRegistry = socketRegistry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        socketRegistry.addSession(session);
        log.info("Terminal WebSocket connection established [sessionId={}]", extractSessionId(session));
    }

    @Override
    public void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        ByteBuffer payload = message.getPayload();
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);

        String sessionUuid = extractSessionUuid(session);
        String terminalId = extractTerminalId(session);

        if (sessionUuid == null || terminalId == null) {
            log.warn("Terminal input rejected: missing sessionUuid or terminalId");
            return;
        }

        try {
            terminalStreamRouter.writeInput(sessionUuid, terminalId, bytes);
        } catch (Exception ex) {
            log.error("Failed to write terminal input [session={}, terminal={}]: {}",
                    sessionUuid, terminalId, ex.getMessage(), ex);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) throws Exception {
        socketRegistry.removeSession(session);
        log.info("Terminal WebSocket connection closed [sessionId={}, status={}]", extractSessionId(session), status);
    }

    private String extractSessionId(WebSocketSession session) {
        Map<String, Object> attrs = session.getAttributes();
        Object sessionUuid = attrs.get("sessionUuid");
        Object terminalId = attrs.get("terminalId");
        if (sessionUuid == null || terminalId == null) {
            return null;
        }
        return sessionUuid + "|" + terminalId;
    }

    private String extractSessionUuid(WebSocketSession session) {
        Object uuid = session.getAttributes().get("sessionUuid");
        return uuid == null ? null : String.valueOf(uuid);
    }

    private String extractTerminalId(WebSocketSession session) {
        Object id = session.getAttributes().get("terminalId");
        return id == null ? null : String.valueOf(id);
    }
}
