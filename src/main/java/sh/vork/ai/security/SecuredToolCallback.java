package sh.vork.ai.security;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

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
        enforce(arguments);
        return delegate.call(arguments);
    }

    @Override
    public String call(String arguments, ToolContext toolContext) {
        enforce(arguments);
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

    private void enforce(String arguments) {
        String username = resolveUsername();
        String toolName = delegate.getToolDefinition().name();

        if (ruleEngine.requiresAuthorization(toolName, username, "pending-id")) {
            throw new ToolSuspensionException(toolName, arguments);
        }
    }

    private static String resolveUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return "anonymous";
        }
        return auth.getName();
    }
}
