package sh.vork.ai.security;

import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.interaction.FieldSource;
import sh.vork.ai.protocol.interaction.FormAction;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;

/**
 * Decorator that enforces authorization checks before invoking the underlying tool.
 */
public class SecuredToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final AuthorizationRuleEngine ruleEngine;

    public SecuredToolCallback(ToolCallback delegate, AuthorizationRuleEngine ruleEngine) {
        this.delegate = delegate;
        this.ruleEngine = ruleEngine;
    }

    @Override
    public String call(String arguments) {
        enforce(arguments, null);
        return delegate.call(arguments);
    }

    @Override
    public String call(String arguments, ToolContext toolContext) {
        enforce(arguments, toolContext);
        return delegate.call(arguments, toolContext);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    private void enforce(String arguments, ToolContext toolContext) {
        String username = resolveUsername();
        String toolName = delegate.getToolDefinition().name();
        String effectiveArguments = resolveArguments(arguments, toolContext);

        if (ruleEngine.requiresAuthorization(toolName, username, "pending-id")) {
            String reasoning = extractReasoning(toolContext);
            String displayArguments = formatForDisplay(effectiveArguments);
            InteractionFormSchema formSchema = new InteractionFormSchema(
                    "AUTHORIZE_TOOL",
                    "Authorization Required",
                    "Confirm whether this protected tool call should run.",
                    List.of(new FormField(
                            "arguments",
                            "markdown",
                            "Tool input preview",
                            displayArguments,
                            false,
                            FieldSource.CONTEXT,
                            List.of())),
                    List.of(
                            new FormAction("ONCE", "Allow Once", "primary"),
                            new FormAction("SESSION", "Allow for Session", "secondary"),
                            new FormAction("ALWAYS", "Always Allow", "success"),
                            new FormAction("DENIED", "Deny", "danger")));
            throw new ToolSuspensionException(toolName, effectiveArguments, reasoning, formSchema);
        }
    }

    private String resolveArguments(String arguments, ToolContext toolContext) {
        String normalized = normalizeArguments(arguments);
        if (!"{}".equals(normalized)) {
            return normalized;
        }

        if (toolContext == null) {
            return normalized;
        }

        try {
            var method = toolContext.getClass().getMethod("getContext");
            Object contextObj = method.invoke(toolContext);
            if (!(contextObj instanceof Map<?, ?> context)) {
                return normalized;
            }

            Object fromMap = firstNonNull(
                    context.get("arguments"),
                    context.get("toolArguments"),
                    context.get("tool_arguments"),
                    context.get("input"),
                    context.get("toolInput"),
                    context.get("tool_input"));
            if (fromMap == null) {
                return normalized;
            }
            if (fromMap instanceof String str) {
                String candidate = normalizeArguments(str);
                return candidate.isBlank() ? normalized : candidate;
            }
            if (fromMap instanceof Map<?, ?> mapValue) {
                return toJsonLike(mapValue);
            }
        } catch (Exception ignored) {
            // Best-effort extraction only.
        }

        return normalized;
    }

    private String formatForDisplay(String argumentsJson) {
        if (delegate instanceof VisualizableTool visualizableTool) {
            try {
                String formatted = visualizableTool.formatAuthorizationDetails(argumentsJson);
                if (formatted != null && !formatted.isBlank()) {
                    return formatted;
                }
            } catch (Exception ignored) {
                // Fall back to raw payload if formatter fails.
            }
        }
        return "```json\n" + normalizeArguments(argumentsJson) + "\n```";
    }

    private static String normalizeArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "{}";
        }
        return arguments;
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String toJsonLike(Map<?, ?> map) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(String.valueOf(entry.getKey()).replace("\"", "\\\"")).append('"');
            sb.append(':');
            Object value = entry.getValue();
            if (value == null) {
                sb.append("null");
            } else {
                sb.append('"').append(String.valueOf(value).replace("\"", "\\\"")).append('"');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static String extractReasoning(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }

        try {
            var method = toolContext.getClass().getMethod("getContext");
            Object contextObj = method.invoke(toolContext);
            if (contextObj instanceof java.util.Map<?, ?> context) {
                String fromMap = firstNonBlank(
                        context.get("reasoning"),
                        context.get("justification"),
                        context.get("content"),
                        context.get("text"),
                        context.get("assistantMessage"),
                        context.get("assistant_message"),
                        context.get("message"),
                        context.get("output"));
                if (fromMap != null) {
                    return fromMap;
                }
            }
        } catch (Exception ignored) {
            // Best-effort extraction only.
        }

        return null;
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static String resolveUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return "anonymous";
        }
        return auth.getName();
    }
}
