package sh.vork.ai.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SecuredToolCallbackTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unrestrictedTool_executesDelegate() {
        AuthorizationRuleEngine rules = new AuthorizationRuleEngine(Set.of("compileJavaType"));
        ToolCallback delegate = delegate("getCurrentWeather");
        when(delegate.call("{\"location\":\"London\"}")).thenReturn("sunny");

        SecuredToolCallback secured = new SecuredToolCallback(delegate, rules);
        String out = secured.call("{\"location\":\"London\"}");

        assertEquals("sunny", out);
        verify(delegate).call("{\"location\":\"London\"}");
    }

    @Test
    void restrictedTool_throwsSuspensionBeforeDelegateCall() {
        AuthorizationRuleEngine rules = new AuthorizationRuleEngine(Set.of("compileJavaType"));
        ToolCallback delegate = delegate("compileJavaType");

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice", "pw"));

        SecuredToolCallback secured = new SecuredToolCallback(delegate, rules);

        ToolSuspensionException ex = assertThrows(ToolSuspensionException.class,
                () -> secured.call("{\"source\":\"class A {}\"}"));

        assertEquals("compileJavaType", ex.getToolName());
        assertEquals("{\"source\":\"class A {}\"}", ex.getArguments());
        verify(delegate, never()).call("{\"source\":\"class A {}\"}");
    }

    @Test
    void callWithToolContext_appliesSameAuthorizationCheck() {
        AuthorizationRuleEngine rules = new AuthorizationRuleEngine(Set.of("compileJavaType"));
        ToolCallback delegate = delegate("compileJavaType");

        SecuredToolCallback secured = new SecuredToolCallback(delegate, rules);

        assertThrows(ToolSuspensionException.class,
            () -> secured.call("{}", null));

        verify(delegate, never()).call(anyString(), any());
    }

    private static ToolCallback delegate(String toolName) {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn(toolName);
        when(delegate.getToolDefinition()).thenReturn(def);
        return delegate;
    }
}
