package sh.vork.ai.security;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import jakarta.annotation.PostConstruct;

/**
 * Decides whether a given tool invocation requires explicit user authorization before execution.
 *
 * <h3>Restricted tool detection</h3>
 * On startup, scans every {@link ToolCallback} bean whose Spring {@code @Bean} factory method
 * carries {@link Restricted @Restricted}. Those tool names are stored in {@link #restrictedToolNames}.
 * Any tool <em>not</em> in that set is automatically allowed.
 *
 * <h3>Exception-rule evaluation order</h3>
 * <ol>
 *   <li><strong>Use-once exceptions</strong> — identified by the tool-call-ID emitted by the
 *       model.  Consumed on first successful match.</li>
 *   <li><strong>Temporary user rules</strong> — in-memory per-user grants that survive until
 *       the application is restarted or the rule is explicitly revoked.</li>
 *   <li><strong>Permanent rules</strong> — in-memory grants intended for use cases where
 *       persistence is desired (can be backed by a {@code DatabaseRepository} in production).</li>
 * </ol>
 */
@Component
public class AuthorizationRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationRuleEngine.class);

    // ── State ─────────────────────────────────────────────────────────────────

    /** Tool names whose factory methods carry {@link Restricted}. */
    private final Set<String> restrictedToolNames = ConcurrentHashMap.newKeySet();

    /**
     * Temporary per-user grants: {@code username → set of allowed tool names}.
     * Lives only for the lifetime of this application instance.
     */
    private final ConcurrentHashMap<String, Set<String>> temporaryUserRules = new ConcurrentHashMap<>();

    /**
     * Permanent per-user grants: {@code username → set of allowed tool names}.
     * For production, replace with a {@code DatabaseRepository}-backed store.
     */
    private final ConcurrentHashMap<String, Set<String>> permanentRules = new ConcurrentHashMap<>();

    /**
     * Use-once exceptions keyed by the tool-call-ID emitted by the model.
     * Removed after the first successful match.
     */
    private final Set<String> useOnceExceptions = ConcurrentHashMap.newKeySet();

    // ── Spring-managed dependencies (null in unit tests) ──────────────────────

    private final List<ToolCallback> allTools;
    private final ConfigurableListableBeanFactory beanFactory;

    // ── Constructors ──────────────────────────────────────────────────────────

    /** Spring-managed constructor — used in the application context. */
    @Autowired
    public AuthorizationRuleEngine(List<ToolCallback> allTools,
                                   ConfigurableListableBeanFactory beanFactory) {
        this.allTools    = allTools;
        this.beanFactory = beanFactory;
    }

    /**
     * Test constructor — directly supplies the initial set of restricted tool names.
     * Skips the bean-factory scan so no Spring context is required.
     */
    AuthorizationRuleEngine(Set<String> restrictedToolNames) {
        this.allTools    = List.of();
        this.beanFactory = null;
        this.restrictedToolNames.addAll(restrictedToolNames);
    }

    // ── Startup scanner ───────────────────────────────────────────────────────

    /**
     * Scans all injected {@link ToolCallback} beans. For each one whose bean definition's
     * factory method carries {@link Restricted @Restricted}, the tool name is added to
     * {@link #restrictedToolNames}.
     */
    @PostConstruct
    void scanRestrictedTools() {
        if (beanFactory == null) {
            return; // test path — restricted set was supplied directly
        }
        for (ToolCallback tool : allTools) {
            String toolName = tool.getToolDefinition().name();
            if (!beanFactory.containsBeanDefinition(toolName)) {
                continue;
            }
            BeanDefinition bd = beanFactory.getBeanDefinition(toolName);
            String factoryBeanName   = bd.getFactoryBeanName();
            String factoryMethodName = bd.getFactoryMethodName();
            if (factoryBeanName == null || factoryMethodName == null) {
                continue;
            }
            try {
                Object factoryBean  = beanFactory.getBean(factoryBeanName);
                Class<?> targetClass = ClassUtils.getUserClass(factoryBean);
                for (Method m : targetClass.getDeclaredMethods()) {
                    if (m.getName().equals(factoryMethodName) && m.isAnnotationPresent(Restricted.class)) {
                        restrictedToolNames.add(toolName);
                        log.info("Tool '{}' is marked @Restricted — authorization required", toolName);
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("Could not inspect factory method for tool '{}': {}", toolName, e.getMessage());
            }
        }
    }

    // ── Core contract ─────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when the tool invocation must be held for explicit user approval.
     *
     * @param toolName   the AI tool name (e.g. {@code "compileJavaType"})
     * @param username   the authenticated username of the requesting user
     * @param toolCallId the unique call-execution ID emitted by the model for this invocation
     */
    public boolean requiresAuthorization(String toolName, String username, String toolCallId) {
        // 1. Not restricted — pass immediately
        if (!restrictedToolNames.contains(toolName)) {
            return false;
        }

        // 2. Use-once exception matching this specific call-execution ID
        if (toolCallId != null && useOnceExceptions.contains(toolCallId)) {
            useOnceExceptions.remove(toolCallId);
            log.debug("Use-once exception consumed for toolCallId='{}', tool='{}'", toolCallId, toolName);
            return false;
        }

        // 3. Temporary per-user rule
        if (hasTemporaryUserRule(username, toolName)) {
            return false;
        }

        // 4. Permanent per-user rule
        if (hasPermanentRule(username, toolName)) {
            return false;
        }

        return true; // no exception found — authorization required
    }

    // ── Mutators ──────────────────────────────────────────────────────────────

    /**
     * Grants a temporary (in-memory, restart-scoped) exception for {@code username}
     * to invoke {@code toolName} without further authorization.
     */
    public void addTemporaryUserRule(String username, String toolName) {
        temporaryUserRules
                .computeIfAbsent(username, k -> ConcurrentHashMap.newKeySet())
                .add(toolName);
        log.debug("Temporary rule added: user='{}', tool='{}'", username, toolName);
    }

    /**
     * Grants a permanent exception for {@code username} to invoke {@code toolName}.
     * In production this should be persisted to a {@code DatabaseRepository}.
     */
    public void addPermanentRule(String username, String toolName) {
        permanentRules
                .computeIfAbsent(username, k -> ConcurrentHashMap.newKeySet())
                .add(toolName);
        log.debug("Permanent rule added: user='{}', tool='{}'", username, toolName);
    }

    /**
     * Registers a single-use exception for the given tool-call-ID.
     * The first call to {@link #requiresAuthorization} with a matching ID will pass,
     * and the token is immediately consumed so subsequent calls are blocked again.
     */
    public void addUseOnceRule(String toolCallId) {
        useOnceExceptions.add(toolCallId);
        log.debug("Use-once rule added for toolCallId='{}'", toolCallId);
    }

    /**
     * Removes a use-once exception without consuming it via authorization check.
     * Use this to drain leftover wildcard tokens (e.g. {@code "pending-id"}) before
     * handing control to a different agent so they cannot auto-approve unrelated tool calls.
     */
    public void removeUseOnceRule(String toolCallId) {
        boolean removed = useOnceExceptions.remove(toolCallId);
        if (removed) {
            log.debug("Use-once rule removed (cleanup) for toolCallId='{}'", toolCallId);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean hasTemporaryUserRule(String username, String toolName) {
        Set<String> rules = temporaryUserRules.get(username);
        return rules != null && rules.contains(toolName);
    }

    private boolean hasPermanentRule(String username, String toolName) {
        Set<String> rules = permanentRules.get(username);
        return rules != null && rules.contains(toolName);
    }

    // ── Accessors (for testing / diagnostics) ─────────────────────────────────

    /** Returns an unmodifiable snapshot of the currently restricted tool names. */
    public Set<String> getRestrictedToolNames() {
        return Set.copyOf(restrictedToolNames);
    }
}
