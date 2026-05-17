package sh.vork.ai.tool;

import org.springframework.stereotype.Component;

import sh.vork.ai.entity.AiSession;
import sh.vork.ai.function.ExecuteTerminalCommandRequest;
import sh.vork.ai.terminal.TerminalStreamRouter;
import sh.vork.database.DatabaseRepository;

@Component
public class ExecuteTerminalCommandTool extends AbstractTerminalTool {

    public ExecuteTerminalCommandTool(TerminalStreamRouter terminalStreamRouter,
                                      DatabaseRepository<AiSession> aiSessionRepository) {
        super(terminalStreamRouter, aiSessionRepository);
    }

    public String execute(ExecuteTerminalCommandRequest req) {
        if (req == null || req.command() == null || req.command().isBlank()) {
            return "{\"status\":\"error\",\"message\":\"command is required\"}";
        }
        return executeTerminalCommand(req.host(), req.command());
    }
}