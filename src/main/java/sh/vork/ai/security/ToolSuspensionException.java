package sh.vork.ai.security;

/**
 * Control-flow exception used to freeze a chat turn before executing a
 * restricted tool callback.
 */
public class ToolSuspensionException extends RuntimeException {

    private final String toolName;
    private final String arguments;

    public ToolSuspensionException(String toolName, String arguments) {
        super("Tool execution suspended pending authorization: " + toolName);
        this.toolName = toolName;
        this.arguments = arguments;
    }

    public String getToolName() {
        return toolName;
    }

    public String getArguments() {
        return arguments;
    }
}
