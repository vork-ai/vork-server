package sh.vork.ai.websocket;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Extracts sessionUuid and terminalId from WebSocket handshake URL.
 * Stores them as session attributes for use in the TerminalBinaryWebSocketHandler.
 *
 * <p>URL pattern: {@code /ws/terminal/{sessionUuid}/{terminalId}}
 */
public class TerminalWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TerminalWebSocketHandshakeInterceptor.class);

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        String path = request.getURI().getPath();
        
        // Extract from /ws/terminal/{sessionUuid}/{terminalId}
        String[] parts = path.split("/");
        if (parts.length >= 5 && "terminal".equals(parts[2])) {
            String sessionUuid = parts[3];
            String terminalId = parts[4];
            
            if (sessionUuid != null && !sessionUuid.isBlank()) {
                attributes.put("sessionUuid", sessionUuid);
                if (terminalId != null && !terminalId.isBlank()) {
                    attributes.put("terminalId", terminalId);
                }
                log.debug("Terminal WebSocket handshake [sessionUuid={}, terminalId={}]", sessionUuid, terminalId);
                return true;
            }
        }
        
        log.warn("Terminal WebSocket handshake rejected: invalid URL pattern [path={}]", path);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception ex) {
        // No-op
    }
}
