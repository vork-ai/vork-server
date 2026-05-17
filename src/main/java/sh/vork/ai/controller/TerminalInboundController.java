package sh.vork.ai.controller;

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
    public void handleInput(@DestinationVariable String sessionUuid, byte[] payload) {
        terminalStreamRouter.writeInput(sessionUuid, payload);
    }

    @MessageMapping("/chat/terminal/input/{sessionUuid}/{terminalId}")
    public void handleInput(@DestinationVariable String sessionUuid,
                            @DestinationVariable String terminalId,
                            byte[] payload) {
        terminalStreamRouter.writeInput(sessionUuid, terminalId, payload);
    }
}
