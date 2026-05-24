package sh.vork.ai.exception;

import java.util.List;

import sh.vork.ai.protocol.interaction.FormAction;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;

/**
 * Control-flow exception used to freeze a chat turn before executing a
 * restricted tool callback.
 */
public class ToolSuspensionException extends RuntimeException {

    private final String toolName;
    private final String arguments;
    private final String justification;
    private final InteractionFormSchema formSchema;

    public ToolSuspensionException(String toolName,
                                   String arguments,
                                   String justification,
                                   InteractionFormSchema formSchema) {
        super("Tool execution suspended pending authorization: " + toolName);
        this.toolName = toolName;
        this.arguments = arguments;
        this.justification = justification;
        this.formSchema = formSchema;
    }

    public ToolSuspensionException(String toolName, String arguments, String justification) {
        this(toolName, arguments, justification,
                new InteractionFormSchema(
                        "AUTHORIZE_TOOL",
                        "Authorization Required",
                        "Confirm whether this protected tool call should run.",
                        List.of(),
                        List.of(
                                new FormAction("ONCE", "Allow Once", "primary"),
                                new FormAction("SESSION", "Allow for Session", "secondary"),
                                new FormAction("ALWAYS", "Always Allow", "success"),
                                new FormAction("DENIED", "Deny", "danger")
                        )));
    }

    public ToolSuspensionException(String toolName, String arguments) {
        this(toolName, arguments, null);
    }

    public String getToolName() {
        return toolName;
    }

    public String getArguments() {
        return arguments;
    }

    public String getJustification() {
        return justification;
    }

    public InteractionFormSchema getFormSchema() {
        return formSchema;
    }
}