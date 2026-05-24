package sh.vork.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.terminal.TerminalStreamRouter;
import sh.vork.database.mock.MapDatabaseRepository;

class AbstractTerminalToolTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void executeTerminalCommand_routesUsingSessionOriginMode() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        String sessionUuid = "session-tool-1";
        sessionRepo.save(new AiSession(
                sessionUuid,
                "GEMINI",
                SessionOriginMode.WEB,
                "alice",
            "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(new AiChatMessage("m1", "USER", "hello", System.currentTimeMillis(), null)),
                AiSessionStatus.RUNNING));

        RecordingRouter router = new RecordingRouter();
        DemoTool tool = new DemoTool(router, sessionRepo);

        MDC.put("sessionUuid", sessionUuid);
        String out = tool.run("local", "ls -la");

        assertEquals("terminal-output", out);
        assertEquals(sessionUuid, router.lastSessionUuid);
        assertEquals("local", router.lastHost);
        assertEquals("ls -la", router.lastCommand);
        assertEquals(SessionOriginMode.WEB, router.lastOriginMode);
    }

    @Test
    void executeTerminalCommand_throwsWhenNoSessionInMdc() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        RecordingRouter router = new RecordingRouter();
        DemoTool tool = new DemoTool(router, sessionRepo);

        assertThrows(IllegalStateException.class, () -> tool.run("local", "pwd"));
    }

    private static final class DemoTool extends AbstractTerminalTool {

        private DemoTool(TerminalStreamRouter terminalStreamRouter,
                         MapDatabaseRepository<AiSession> aiSessionRepository) {
            super(terminalStreamRouter, aiSessionRepository);
        }

        private String run(String host, String command) {
            return executeTerminalCommand(host, command);
        }
    }

    private static final class RecordingRouter extends TerminalStreamRouter {
        private String lastSessionUuid;
        private String lastHost;
        private String lastCommand;
        private SessionOriginMode lastOriginMode;

        private RecordingRouter() {
            super(null, null);
        }

        @Override
        public String executeStreamedCommand(String sessionUuid,
                                             String host,
                                             String command,
                                             SessionOriginMode originMode) {
            this.lastSessionUuid = sessionUuid;
            this.lastHost = host;
            this.lastCommand = command;
            this.lastOriginMode = originMode;
            return "terminal-output";
        }
    }
}
