package sh.vork.ai.tool;

import org.slf4j.MDC;

import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.terminal.TerminalStreamRouter;
import sh.vork.database.DatabaseRepository;

/**
 * Base helper for tools that execute streamed terminal commands and need to route
 * output differently for WEB vs BACKGROUND execution modes.
 */
public abstract class AbstractTerminalTool {

    private final TerminalStreamRouter terminalStreamRouter;
    private final DatabaseRepository<AiSession> aiSessionRepository;

    protected AbstractTerminalTool(TerminalStreamRouter terminalStreamRouter,
                                   DatabaseRepository<AiSession> aiSessionRepository) {
        this.terminalStreamRouter = terminalStreamRouter;
        this.aiSessionRepository = aiSessionRepository;
    }

    protected String executeTerminalCommand(String host, String command) {
        String sessionUuid = resolveSessionUuid();
        AiSession session = aiSessionRepository.get(sessionUuid);
        if (session == null) {
            throw new IllegalStateException("AI session not found: " + sessionUuid);
        }

        SessionOriginMode originMode = session.originMode() == null
                ? SessionOriginMode.BACKGROUND
                : session.originMode();

        return terminalStreamRouter.executeStreamedCommand(sessionUuid, host, command, originMode);
    }

    protected String resolveSessionUuid() {
        String sessionUuid = MDC.get("sessionUuid");
        if (sessionUuid == null || sessionUuid.isBlank() || "<null>".equals(sessionUuid)) {
            throw new IllegalStateException("No sessionUuid available in execution context");
        }
        return sessionUuid;
    }
}
