package sh.vork.ai.controller;

import java.nio.charset.StandardCharsets;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import sh.vork.ai.terminal.TerminalStreamRouter;

@Controller
public class TerminalInboundController {

    private final TerminalStreamRouter terminalStreamRouter;

    public TerminalInboundController(TerminalStreamRouter terminalStreamRouter) {
        this.terminalStreamRouter = terminalStreamRouter;
    }

    @MessageMapping("/chat/terminal/input/{sessionUuid}")
    public void handleInput(@DestinationVariable String sessionUuid, String payload) {
        terminalStreamRouter.writeInput(sessionUuid, toPayloadBytes(payload));
    }

    @MessageMapping("/chat/terminal/input/{sessionUuid}/{terminalId}")
    public void handleInput(@DestinationVariable String sessionUuid,
                            @DestinationVariable String terminalId,
                            String payload) {
        terminalStreamRouter.writeInput(sessionUuid, terminalId, toPayloadBytes(payload));
    }

    private static byte[] toPayloadBytes(String payload) {
        if (payload == null || payload.isEmpty()) {
            return new byte[0];
        }
        return payload.getBytes(StandardCharsets.UTF_8);
    }
}
