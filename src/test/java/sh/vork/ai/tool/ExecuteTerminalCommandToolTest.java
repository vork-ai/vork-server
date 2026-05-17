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
import sh.vork.ai.function.ExecuteTerminalCommandRequest;
import sh.vork.ai.terminal.TerminalStreamRouter;
import sh.vork.database.mock.MapDatabaseRepository;

class ExecuteTerminalCommandToolTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void execute_usesSessionOriginModeAndDelegatesToRouter() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        String sessionUuid = "terminal-session-1";
        sessionRepo.save(new AiSession(
                sessionUuid,
                "GEMINI",
                SessionOriginMode.WEB,
                "alice",
                System.currentTimeMillis(),
                0,
                List.of(new AiChatMessage("m1", "USER", "hello", System.currentTimeMillis(), null)),
                AiSessionStatus.RUNNING));

        RecordingRouter router = new RecordingRouter();
        ExecuteTerminalCommandTool tool = new ExecuteTerminalCommandTool(router, sessionRepo);

        MDC.put("sessionUuid", sessionUuid);
        String result = tool.execute(new ExecuteTerminalCommandRequest("pwd", "ignored-host"));

        assertEquals("stream-output", result);
        assertEquals(sessionUuid, router.lastSessionUuid);
        assertEquals("ignored-host", router.lastHost);
        assertEquals("pwd", router.lastCommand);
        assertEquals(SessionOriginMode.WEB, router.lastOriginMode);
    }

    @Test
    void execute_requiresSessionContext() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        ExecuteTerminalCommandTool tool = new ExecuteTerminalCommandTool(new RecordingRouter(), sessionRepo);

        assertThrows(IllegalStateException.class,
                () -> tool.execute(new ExecuteTerminalCommandRequest("pwd", null)));
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
            return "stream-output";
        }
    }
}