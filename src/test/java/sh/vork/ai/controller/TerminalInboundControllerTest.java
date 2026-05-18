package sh.vork.ai.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.terminal.TerminalStreamRouter;

class TerminalInboundControllerTest {

    @Test
    void handleInput_forwardsRawPayloadToRouter() {
        RecordingRouter router = new RecordingRouter();
        TerminalInboundController controller = new TerminalInboundController(router);

        String payload = "ABC";
        controller.handleInput("session-123", payload);

        assertEquals("session-123", router.lastSessionUuid);
        assertNull(router.lastTerminalId);
        assertArrayEquals(new byte[] { 65, 66, 67 }, router.lastPayload);
    }

    @Test
    void handleInput_withTerminalId_forwardsRawPayloadToRouter() {
        RecordingRouter router = new RecordingRouter();
        TerminalInboundController controller = new TerminalInboundController(router);

        String payload = "DEF";
        controller.handleInput("session-456", "terminal-789", payload);

        assertEquals("session-456", router.lastSessionUuid);
        assertEquals("terminal-789", router.lastTerminalId);
        assertArrayEquals(new byte[] { 68, 69, 70 }, router.lastPayload);
    }

    private static final class RecordingRouter extends TerminalStreamRouter {
        private String lastSessionUuid;
        private String lastTerminalId;
        private byte[] lastPayload;

        private RecordingRouter() {
            super(null, null);
        }

        @Override
        public void writeInput(String sessionUuid, byte[] payload) {
            this.lastSessionUuid = sessionUuid;
            this.lastTerminalId = null;
            this.lastPayload = payload;
        }

        @Override
        public void writeInput(String sessionUuid, String terminalId, byte[] payload) {
            this.lastSessionUuid = sessionUuid;
            this.lastTerminalId = terminalId;
            this.lastPayload = payload;
        }

        @Override
        public String executeStreamedCommand(String sessionUuid,
                                             String host,
                                             String command,
                                             SessionOriginMode originMode) {
            return "";
        }
    }
}
