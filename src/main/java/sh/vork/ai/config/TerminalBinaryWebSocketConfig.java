package sh.vork.ai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import sh.vork.ai.websocket.TerminalBinaryWebSocketHandler;
import sh.vork.ai.websocket.TerminalWebSocketHandshakeInterceptor;

@Configuration
@EnableWebSocket
public class TerminalBinaryWebSocketConfig implements WebSocketConfigurer {

    private final TerminalBinaryWebSocketHandler terminalBinaryWebSocketHandler;

    public TerminalBinaryWebSocketConfig(TerminalBinaryWebSocketHandler terminalBinaryWebSocketHandler) {
        this.terminalBinaryWebSocketHandler = terminalBinaryWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalBinaryWebSocketHandler, "/ws/terminal/{sessionUuid}/{terminalId}")
                .addInterceptors(new TerminalWebSocketHandshakeInterceptor())
                .setAllowedOriginPatterns("*");
    }
}
