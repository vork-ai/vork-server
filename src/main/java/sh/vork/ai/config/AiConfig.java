package sh.vork.ai.config;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Locale;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.util.ClassUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.AiProvider;
import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.memory.SessionEnvironmentService;
import sh.vork.ai.function.CompileTypeRequest;
import sh.vork.ai.function.CreateMongoDbConnectionRequest;
import sh.vork.ai.function.DeleteSshConnectionRequest;
import sh.vork.ai.function.DeleteMongoDbDocumentsRequest;
import sh.vork.ai.function.DeleteTypeInstanceRequest;
import sh.vork.ai.function.DisconnectSshRequest;
import sh.vork.ai.function.DownloadFileRequest;
import sh.vork.ai.function.ExecuteTerminalCommandRequest;
import sh.vork.ai.function.GetDateTimeRequest;
import sh.vork.ai.function.GetMongoDbCollectionSchemaRequest;
import sh.vork.ai.function.GetTypeInstanceRequest;
import sh.vork.ai.function.GetTypeSchemaRequest;
import sh.vork.ai.function.HttpRequestToolRequest;
import sh.vork.ai.function.InsertMongoDbDocumentRequest;
import sh.vork.ai.function.ListAgentTemplatesRequest;
import sh.vork.ai.function.ListAvailableToolsRequest;
import sh.vork.ai.function.ListEnumValuesRequest;
import sh.vork.ai.function.ListJavaTypesRequest;
import sh.vork.ai.function.ListMongoDbCollectionsRequest;
import sh.vork.ai.function.ListNotificationProvidersRequest;
import sh.vork.ai.function.ListSshConnectionsRequest;
import sh.vork.ai.function.ListTypeInstancesRequest;
import sh.vork.ai.function.LogInfoRequest;
import sh.vork.ai.function.OAuthConnectRequest;
import sh.vork.ai.function.OAuthResetRequest;
import sh.vork.ai.function.CountTypeInstancesRequest;
import sh.vork.ai.function.SaveTypeInstanceRequest;
import sh.vork.ai.function.SearchMongoDbDocumentsRequest;
import sh.vork.ai.function.SearchTypeInstancesRequest;
import sh.vork.ai.function.SendNotificationRequest;
import sh.vork.ai.function.SetSshAliasRequest;
import sh.vork.ai.function.SshConnectRequest;
import sh.vork.ai.function.SshCreateConnectionRequest;
import sh.vork.ai.function.UpdateMongoDbDocumentsRequest;
import sh.vork.ai.function.UploadFileRequest;
import sh.vork.ai.function.UploadTextFileRequest;
import sh.vork.ai.mongo.MongoToolService;
import sh.vork.ai.registry.Hidden;
import sh.vork.ai.registry.ToolCategory;
import sh.vork.ai.registry.ToolDepends;
import sh.vork.ai.registry.ToolRegistry;
import sh.vork.ai.security.AuthorizationRuleEngine;
import sh.vork.ai.security.Restricted;
import sh.vork.ai.security.SecuredToolCallback;
import sh.vork.ai.security.VisualizableToolCallback;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.interaction.FieldSource;
import sh.vork.ai.protocol.interaction.FormAction;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;
import sh.vork.ai.tool.CompleteBackgroundTaskRequest;
import sh.vork.ai.tool.CompleteSkillExecutionRequest;
import sh.vork.ai.tool.MemoryRequest;
import sh.vork.ai.tool.RecordProgressRequest;
import sh.vork.ai.tool.ThinkRequest;
import sh.vork.ai.protocol.UiEventFrame;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import sh.vork.ai.tool.DeleteSshConnectionTool;
import sh.vork.ai.tool.DisconnectSshTool;
import sh.vork.ai.tool.DownloadFileTool;
import sh.vork.ai.tool.ExecuteTerminalCommandTool;
import sh.vork.ai.tool.ListSshConnectionsTool;
import sh.vork.ai.tool.SetSshAliasTool;
import sh.vork.ai.tool.SshConnectTool;
import sh.vork.ai.tool.SshCreateConnectionTool;
import sh.vork.ai.tool.UploadFileTool;
import sh.vork.ai.tool.UploadTextFileTool;
import sh.vork.notification.service.DirectNotificationService;
import sh.vork.oauth.OAuthClientService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.SortOrder;
import sh.vork.scheduling.domain.JobResult;
import sh.vork.scheduling.service.BackgroundExecutionContext;
import sh.vork.security.SecureCredentialStore;
import sh.vork.typegen.JavaType;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.SqlParseException;
import sh.vork.typegen.TypeDatabaseService;
import sh.vork.typegen.TypeGenerationException;
import sh.vork.typegen.TypeGeneratorService;
import sh.vork.web.RequestOriginContext;

/**
 * Wires all AI-related Spring beans.
 *
 * <h3>How the routing works</h3>
 * Each supported provider gets its own {@code @Bean ChatClient}. All clients
 * are collected into a single {@code Map<AiProvider, ChatClient>} registry
 * bean.
 * {@code AiOrchestrationService} resolves the correct client at call-time by
 * looking up the caller-supplied {@link AiProvider} key.
 *
 * <h3>Adding a new provider</h3>
 * <ol>
 * <li>Add the enum entry in {@link AiProvider}.</li>
 * <li>Add a {@code @Bean ChatClient} here (inject the provider's
 * auto-configured
 * {@code ChatModel}).</li>
 * <li>Add an entry in {@link #chatClientRegistry}.</li>
 * </ol>
 * No other class needs to change.
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);
    private static final String OAUTH_SECRET_CLIENT_SECRET_KEY = "clientSecret";
    public static final String BASE_SYSTEM_PROMPT = """
You are an autonomous Vork AI agent operating strictly within a turn-based AI 
orchestration framework. You execute background workflows using function-calling 
tools. You do not text-chat with a user.

CRITICAL PROCESSING PROTOCOL (HIGHEST PRIORITY):

1. ABSOLUTE SILENCE OUTSIDE OF TOOLS: 
   You are forbidden from generating free-form, conversational text responses 
   (e.g., "I will do this," "Processing now," "Sure, let me check"). Every 
   single turn must consist ONLY of tool calls, or the final JSON response. 

2. ONE TURN = COMPLETE EXECUTION:
   Do not pause for user confirmation or give status updates in raw text. If 
   you need to perform actions, you must chain or invoke those tool calls 
   immediately in this turn. 

3. THE "THINK" TOOL RULES:
   - If you need to reason, log progress, vent, or describe your next steps, 
   you MUST use the `think` tool.
   - The `think` tool is for mid-turn internal commentary only. 
   - CRITICAL: A `think` call is never a final action. If you call `think`, 
   you MUST immediately invoke an action tool or complete your data processing 
   in the same turn. You may never end a turn with a `think` call as your 
   standalone or final output.

3b. THE "recordProgress" TOOL RULES:
    - Use `recordProgress` whenever you complete an important checkpoint whose
    state may be needed in later turns (e.g., hosts scanned, artifacts generated,
    report sent).
    - Keep entries concise and factual.
    - `recordProgress` is persistent session memory, not a final action; continue
    execution after recording progress.

3c. THE "memory" TOOL RULES:
    - Use `memory` to store stable key=value context you must reuse in later turns
    (e.g., active_target_alias, selected_profile, ticket_id).
    - Use `memory` with operation=set to persist a value, operation=get/list to
    retrieve values, and operation=delete to remove stale values.
    - Keys and values are injected into future system prompts as session environment
    variables, so keep them concise and machine-readable.

4. TURN COMPLETION (FINISHED_TURN):
   - You may only return the final JSON payload with status "FINISHED_TURN" 
   when you have fully executed the request and have the actual, substantive
   data/result ready.
   - NEVER emit "FINISHED_TURN" with an empty, placeholder, or status-only 
   text response (e.g., "I am about to scan..."). 
   - If the final data is not ready, you are NOT finished. Use your action 
   tools or the think tool to get it.

OUTPUT FORMAT ENFORCEMENT:
Your output must strictly be either:
A) Valid tool invocation syntax (including `think` combined with action tools).
B) The final JSON structure with status "FINISHED_TURN" and the textResponse field 
containing the actual results.

Any conversational preamble or postamble outside of these structures violates 
the protocol and will break the system. Do not converse. Execute.
                                """.stripIndent();
    private final JavaTypeClassLoader typeClassLoader;
    private final TypeDatabaseService typeDatabaseService;
    private final ObjectMapper objectMapper;

    public AiConfig(JavaTypeClassLoader typeClassLoader,
            TypeDatabaseService typeDatabaseService,
            ObjectMapper objectMapper) {
        this.typeClassLoader = typeClassLoader;
        this.typeDatabaseService = typeDatabaseService;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // ChatClient beans
    // -------------------------------------------------------------------------
    // All providers (Gemini, OpenAI, Ollama, Groq) are built programmatically
    // by AiChatClientFactory from credentials stored via the setup UI.
    // No auto-configured ChatClient beans are needed here.

    private static boolean isRestrictedTool(ConfigurableListableBeanFactory beanFactory, String toolName) {
        return readBeanMethodAnnotation(beanFactory, toolName, Restricted.class) != null;
    }

    private static boolean isHiddenTool(ConfigurableListableBeanFactory beanFactory, String toolName) {
        return readBeanMethodAnnotation(beanFactory, toolName, Hidden.class) != null;
    }

    private static <A extends java.lang.annotation.Annotation> A readBeanMethodAnnotation(
            ConfigurableListableBeanFactory beanFactory, String toolName, Class<A> annotationType) {
        if (!beanFactory.containsBeanDefinition(toolName)) {
            return null;
        }
        BeanDefinition bd = beanFactory.getBeanDefinition(toolName);
        String factoryBeanName = bd.getFactoryBeanName();
        String factoryMethodName = bd.getFactoryMethodName();
        if (factoryBeanName == null || factoryMethodName == null) {
            return null;
        }
        try {
            Object factoryBean = beanFactory.getBean(factoryBeanName);
            Class<?> targetClass = ClassUtils.getUserClass(factoryBean);
            for (Method method : targetClass.getDeclaredMethods()) {
                if (method.getName().equals(factoryMethodName)
                        && method.isAnnotationPresent(annotationType)) {
                    return method.getAnnotation(annotationType);
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Provider registry (always empty at startup — AiChatClientFactory builds
    // clients lazily from credentials stored via the setup UI)
    // -------------------------------------------------------------------------

    @Bean
    public Map<AiProvider, ChatClient> chatClientRegistry() {
        return new LinkedHashMap<>();
    }

    // -------------------------------------------------------------------------
    // Function-calling tools
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Secured tool-callback map (for per-request tool filtering)
    // -------------------------------------------------------------------------

    /**
     * A map of every registered {@link ToolCallback} bean, keyed by Spring bean name,
     * with {@link sh.vork.ai.security.Restricted} beans wrapped in
     * {@link sh.vork.ai.security.SecuredToolCallback}.
     *
     * <p>This map is consumed by {@link sh.vork.ai.service.AiOrchestrationService}
     * to filter tool callbacks at request time when an
     * {@link sh.vork.ai.agent.AgentTemplate} restricts the allowed tool set.
     */
    @Bean
    public Map<String, ToolCallback> securedToolCallbackMap(
            List<ToolCallback> toolCallbacks,
            AuthorizationRuleEngine authorizationRuleEngine,
            ConfigurableListableBeanFactory beanFactory) {
        Map<String, ToolCallback> map = new LinkedHashMap<>();
        toolCallbacks.forEach(tool -> {
            String toolName = tool.getToolDefinition().name();
            if (isHiddenTool(beanFactory, toolName)) {
                return; // hidden tools are injected per-session via SessionToolStore
            }
            ToolCallback secured = isRestrictedTool(beanFactory, toolName)
                    ? new SecuredToolCallback(tool, authorizationRuleEngine)
                    : tool;
            map.put(toolName, secured);
        });
        return map;
    }

    /**
     * {@code listAgentTemplates} tool — returns all configured {@link AgentTemplate} records.
     */
    @Bean
    @Hidden
    @ToolCategory("Agent Orchestration")
    public ToolCallback listAgentTemplates(DatabaseRepository<AgentTemplate> agentTemplateRepository) {
        return FunctionToolCallback
                .builder("listAgentTemplates", (ListAgentTemplatesRequest req) -> {
                    List<Object> entries = new ArrayList<>();
                    try (var stream = agentTemplateRepository.list(0, Integer.MAX_VALUE)) {
                        stream.forEach(t -> entries.add(java.util.Map.of(
                                "uuid",         t.uuid(),
                                "name",         t.name(),
                                "agentType",    t.agentType().name(),
                                "systemPrompt", t.systemPrompt(),
                                "allowedTools", t.allowedTools())));
                    }
                    try {
                        return objectMapper.writeValueAsString(entries);
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description("""
                    List all configured agent templates. Returns each template's UUID, name, \
                    system prompt, and the list of allowed tool bean IDs."""
                        .stripIndent())
                .inputType(ListAgentTemplatesRequest.class)
                .build();
    }

    /**
     * {@code listAvailableTools} tool — returns the full registered tool catalog from
     * {@link sh.vork.ai.registry.ToolRegistry}.
     */
    @Bean
    @Hidden
    @ToolCategory("Agent Orchestration")
    public ToolCallback listAvailableTools(ToolRegistry toolRegistry) {
        return FunctionToolCallback
                .builder("listAvailableTools", (ListAvailableToolsRequest req) -> {
                    List<Object> entries = new ArrayList<>();
                    toolRegistry.getAvailableTools().forEach(d -> entries.add(java.util.Map.of(
                            "id",          d.id(),
                            "name",        d.name(),
                            "description", d.description(),
                            "parameterSchema", d.parameterSchema(),
                            "dependsOn", d.dependsOn())));
                    try {
                        return objectMapper.writeValueAsString(entries);
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description("""
                    List all registered tool callbacks with their IDs and descriptions. Use this \
                    to discover valid tool IDs when building or reviewing an AgentTemplate's \
                    allowedTools list."""
                        .stripIndent())
                .inputType(ListAvailableToolsRequest.class)
                .build();
    }

    // -------------------------------------------------------------------------
    // Existing function-calling tools (unchanged)
    // -------------------------------------------------------------------------

    @Bean
    @Hidden
    @ToolCategory("Scheduling")
    public ToolCallback completeBackgroundTask(DatabaseRepository<AiSession> aiSessionRepository,
                                               DatabaseRepository<JobResult> jobResultRepository,
                                               BackgroundExecutionContext backgroundExecutionContext) {
        return FunctionToolCallback
                .builder("completeBackgroundTask", (CompleteBackgroundTaskRequest req) -> {
                    String sessionUuid = resolveSessionUuid();
                    if ((sessionUuid == null || sessionUuid.isBlank() || "system".equals(sessionUuid))
                            && req != null && req.sessionUuid() != null && !req.sessionUuid().isBlank()) {
                        sessionUuid = req.sessionUuid().trim();
                    }

                    if (sessionUuid == null || sessionUuid.isBlank() || "system".equals(sessionUuid)) {
                        return "{\"error\":\"This tool is only available for out-of-band background tasks.\"}";
                    }

                    AiSession session = aiSessionRepository.get(sessionUuid);
                    if (session == null || session.originMode() != SessionOriginMode.BACKGROUND) {
                        return "{\"error\":\"This tool is only available for out-of-band background tasks.\"}";
                    }

                    // Persist the job result before marking the session complete
                    String jobId = session.environmentVariables() != null
                            ? session.environmentVariables().get("JOB_ID")
                            : null;
                    if (jobId != null && !jobId.isBlank()) {
                        JobResult result = new JobResult(
                                java.util.UUID.randomUUID().toString(),
                                jobId,
                                sessionUuid,
                                req.success(),
                                req.report(),
                                System.currentTimeMillis());
                        jobResultRepository.save(result);
                    }

                    aiSessionRepository.save(new AiSession(
                            session.uuid(),
                            session.provider(),
                            session.originMode(),
                            session.username(),
                            session.name(),
                            session.createdAt(),
                            session.currentRoundCount(),
                            session.messages(),
                            mergedEnv(session.environmentVariables(), req.report()),
                            AiSessionStatus.COMPLETED,
                            session.activeAgentTemplateId(),
                            session.modelId(),
                            session.skillStack(),
                            session.sessionSkillUuids(),
                            session.sessionToolIds()));

                    backgroundExecutionContext.markExecutionComplete();
                    return "{\"status\":\"shutdown_initiated\"}";
                })
                .description("Signals that the background task has entirely fulfilled its operational objectives and that the background processing loop should now gracefully terminate. You MUST supply a boolean 'success' value and a 'report' string summarising what was done and produced.")
                .inputType(CompleteBackgroundTaskRequest.class)
                .build();
    }

    private static Map<String, String> mergedEnv(Map<String, String> env, String report) {
        if (report == null || report.isBlank()) {
            return env;
        }
        Map<String, String> merged = new HashMap<>();
        if (env != null && !env.isEmpty()) {
            merged.putAll(env);
        }
        merged.put("JOB_COMPLETION_REPORT", report);
        return Map.copyOf(merged);
    }

    /**
     * Hidden tool available only to sessions with an active skill frame on their stack.
     * The skill AI calls this once when its objective is fully met.  It pops the
     * top {@link sh.vork.skill.SkillFrame} from the session's {@code skillStack},
     * persists the output into {@link sh.vork.ai.ToolExecutionContext} so the
     * parent loop can retrieve it, and returns normally so Spring AI calls the
     * model one final time (which should emit FINISHED_TURN per the skill protocol).
     */
    @Bean("completeSkillExecution")
    @Hidden
    @ToolCategory("Skills")
    public ToolCallback completeSkillExecution(DatabaseRepository<AiSession> aiSessionRepository) {
        return FunctionToolCallback
                .builder("completeSkillExecution", (CompleteSkillExecutionRequest req) -> {
                    String sessionUuid = resolveSessionUuid();
                    if (sessionUuid == null || sessionUuid.isBlank()) {
                        return "{\"error\":\"This tool is only available inside a skill session.\"}";
                    }
                    AiSession session = aiSessionRepository.get(sessionUuid);
                    if (session == null || session.skillStack() == null || session.skillStack().isEmpty()) {
                        return "{\"error\":\"This tool is only available inside a skill session.\"}";
                    }

                    // Store output so executeSkillSubLoop can retrieve it after this turn ends
                    String output = req.output() != null ? req.output() : "";
                    sh.vork.ai.context.ToolExecutionContext.put("__skill_output__", output);

                    // Pop the top skill frame from the stack and save
                    java.util.List<sh.vork.skill.SkillFrame> newStack =
                            session.skillStack().subList(0, session.skillStack().size() - 1);
                    aiSessionRepository.save(new AiSession(
                            session.uuid(),
                            session.provider(),
                            session.originMode(),
                            session.username(),
                            session.name(),
                            session.createdAt(),
                            session.currentRoundCount(),
                            session.messages(),
                            session.environmentVariables(),
                            AiSessionStatus.RUNNING,
                            session.activeAgentTemplateId(),
                            session.modelId(),
                            java.util.List.copyOf(newStack),
                            session.sessionSkillUuids(),
                            session.sessionToolIds()));

                    log.debug("Skill frame popped [session={}, remainingFrames={}]",
                            sessionUuid, newStack.size());
                    String escapedOutput = output
                            .replace("\\", "\\\\").replace("\"", "\\\"")
                            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
                    return "{\"status\":\"skill_complete\",\"output\":\"" + escapedOutput + "\"}";
                })
                .description("Signals that the skill has fully completed its objective. Call this exactly once with the skill output when all required work is done.")
                .inputType(CompleteSkillExecutionRequest.class)
                .build();
    }

    /**
     * Mandatory hidden meta-tool available to the AI in every turn, including inside
     * skill frames.  Call this to express reasoning or analysis before invoking the
     * next action tool — it MUST NOT be used as a substitute for taking action.
     *
     * <p>The reasoning string is:
     * <ul>
     *   <li>logged at DEBUG level with the session UUID;</li>
     *   <li>broadcast as an {@code AI_THINKING} WebSocket event to the session topic
     *       so interactive UIs can render it (background sessions have no subscriber,
     *       so the send is silently a no-op).</li>
     * </ul>
     * Reasoning is intentionally <em>not</em> added to the conversation history so
     * it does not inflate the context window.
     */
    @Bean("think")
    @Hidden
    @ToolCategory("Meta")
    public ToolCallback think(SimpMessagingTemplate messaging) {
        return FunctionToolCallback
                .builder("think", (ThinkRequest req) -> {
                    String sessionUuid = resolveSessionUuid();
                    String reasoning = req.reasoning() != null ? req.reasoning() : "";
                    log.debug("AI thinking [session={}]: {}", sessionUuid, reasoning);
                    if (sessionUuid != null && !sessionUuid.isBlank()) {
                        messaging.convertAndSend(
                                "/topic/chat/" + sessionUuid,
                                new UiEventFrame(
                                        java.util.UUID.randomUUID().toString(),
                                        "AI_THINKING",
                                        "THINKING",
                                        reasoning,
                                        null));
                    }
                    return "{\"status\":\"ok\",\"hint\":\"Reasoning logged. Invoke your next tool now.\"}";
                })
                .description("Log your reasoning or analysis mid-turn without ending the turn. "
                        + "Call this to express your thinking, then IMMEDIATELY invoke the next action tool. "
                        + "NEVER end a turn with only a think call.")
                .inputType(ThinkRequest.class)
                .build();
    }

    /**
     * Global meta-tool for persisting turn-to-turn checkpoints in session memory.
     *
     * <p>Entries are stored under indexed environment keys so they are injected
     * into subsequent system prompts via {@link sh.vork.ai.service.AiOrchestrationService}.
     */
    @Bean("recordProgress")
    @Hidden
    @ToolCategory("Meta")
    public ToolCallback recordProgress(SessionEnvironmentService sessionEnvironmentService,
                                       SimpMessagingTemplate messaging) {
        return FunctionToolCallback
                .builder("recordProgress", (RecordProgressRequest req) -> {
                    String sessionUuid = resolveSessionUuid();
                    if (sessionUuid == null || sessionUuid.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"No session bound\"}";
                    }

                    String entry = req.entry() == null ? "" : req.entry().trim();
                    if (entry.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"entry is required\"}";
                    }

                    Map<String, String> env = sessionEnvironmentService.getEnv(sessionUuid);
                    int nextIndex = 1;
                    if (env != null) {
                        String prior = env.get("BG_PROGRESS_COUNT");
                        if (prior != null) {
                            try {
                                nextIndex = Math.max(1, Integer.parseInt(prior) + 1);
                            } catch (NumberFormatException ignored) {
                                nextIndex = 1;
                            }
                        }
                    }

                    String key = String.format("BG_PROGRESS_%04d", nextIndex);
                    sessionEnvironmentService.setEnv(sessionUuid, "BG_PROGRESS_COUNT", Integer.toString(nextIndex));
                    sessionEnvironmentService.setEnv(sessionUuid, key, entry);

                    sh.vork.ai.context.ToolExecutionContext.put("BG_PROGRESS_COUNT", Integer.toString(nextIndex));
                    sh.vork.ai.context.ToolExecutionContext.put(key, entry);

                    log.debug("AI progress recorded [session={}, key={}, entry={}]", sessionUuid, key, entry);
                    messaging.convertAndSend(
                            "/topic/chat/" + sessionUuid,
                            new UiEventFrame(
                                    java.util.UUID.randomUUID().toString(),
                                    "AI_PROGRESS",
                                    "PROGRESS",
                                    entry,
                                    null));

                    return "{\"status\":\"ok\",\"storedKey\":\"" + key + "\"}";
                })
                .description("Persist a concise progress checkpoint to session memory for use in later turns. "
                        + "Use this after completing significant steps (e.g. host scanned, report generated, report sent).")
                .inputType(RecordProgressRequest.class)
                .build();
    }

    /**
     * Global meta-tool for generic key/value session memory.
     *
     * <p>Values are persisted to session environment variables and are injected
     * into subsequent system prompts.
     */
    @Bean("memory")
    @Hidden
    @ToolCategory("Meta")
    public ToolCallback memory(SessionEnvironmentService sessionEnvironmentService) {
        return FunctionToolCallback
                .builder("memory", (MemoryRequest req) -> {
                    String sessionUuid = resolveSessionUuid();
                    if (sessionUuid == null || sessionUuid.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"No session bound\"}";
                    }

                    String operation = req.operation() == null ? "set" : req.operation().trim().toLowerCase(Locale.ROOT);
                    Map<String, String> env = sessionEnvironmentService.getEnv(sessionUuid);

                    switch (operation) {
                        case "set" -> {
                            String key = req.key() == null ? "" : req.key().trim();
                            if (key.isBlank()) {
                                return "{\"status\":\"error\",\"message\":\"key is required for set\"}";
                            }
                            String value = req.value() == null ? "" : req.value();
                            sessionEnvironmentService.setEnv(sessionUuid, key, value);
                            ToolExecutionContext.put(key, value);
                            log.debug("AI memory set [session={}, key={}, value={}]", sessionUuid, key, value);
                            return "{\"status\":\"ok\",\"operation\":\"set\",\"key\":\"" + key + "\"}";
                        }
                        case "get" -> {
                            String key = req.key() == null ? "" : req.key().trim();
                            if (key.isBlank()) {
                                return "{\"status\":\"error\",\"message\":\"key is required for get\"}";
                            }
                            String value = env.get(key);
                            try {
                                return objectMapper.writeValueAsString(Map.of(
                                        "status", "ok",
                                        "operation", "get",
                                        "key", key,
                                        "value", value == null ? "" : value,
                                        "found", value != null));
                            } catch (Exception e) {
                                log.warn("memory get serialization failed [session={}, key={}]", sessionUuid, key, e);
                                return "{\"status\":\"error\",\"message\":\"Unable to serialize memory response\"}";
                            }
                        }
                        case "list" -> {
                            String prefix = req.prefix() == null ? "" : req.prefix();
                            Map<String, String> filtered = new java.util.TreeMap<>();
                            if (env != null) {
                                env.forEach((k, v) -> {
                                    if (prefix.isBlank() || k.startsWith(prefix)) {
                                        filtered.put(k, v);
                                    }
                                });
                            }
                            try {
                                return objectMapper.writeValueAsString(Map.of(
                                        "status", "ok",
                                        "operation", "list",
                                        "prefix", prefix,
                                        "count", filtered.size(),
                                        "entries", filtered));
                            } catch (Exception e) {
                                log.warn("memory list serialization failed [session={}, prefix={}]", sessionUuid, prefix, e);
                                return "{\"status\":\"error\",\"message\":\"Unable to serialize memory response\"}";
                            }
                        }
                        case "delete" -> {
                            String key = req.key() == null ? "" : req.key().trim();
                            if (key.isBlank()) {
                                return "{\"status\":\"error\",\"message\":\"key is required for delete\"}";
                            }
                            boolean existed = env != null && env.containsKey(key);
                            sessionEnvironmentService.deleteEnv(sessionUuid, key);
                            log.debug("AI memory delete [session={}, key={}, existed={}]", sessionUuid, key, existed);
                            return "{\"status\":\"ok\",\"operation\":\"delete\",\"key\":\""
                                    + key + "\",\"deleted\":" + existed + "}";
                        }
                        default -> {
                            return "{\"status\":\"error\",\"message\":\"Unsupported operation. Use set|get|list|delete\"}";
                        }
                    }
                })
                .description("Session key/value memory store. Use operation=set|get|list|delete to manage reusable context that is injected into future system prompts.")
                .inputType(MemoryRequest.class)
                .build();
    }

    /**
     * Hidden meta-tool that returns the current local system date and time.
     * Available in every AI turn to support time-aware planning and responses.
     */
    @Bean("getDateTime")
    @Hidden
    @ToolCategory("Meta")
    public ToolCallback getDateTime() {
        return FunctionToolCallback
                .builder("getDateTime", (GetDateTimeRequest req) -> {
                    java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
                    return "{\"status\":\"ok\","
                            + "\"isoDateTime\":\"" + now.format(java.time.format.DateTimeFormatter.ISO_ZONED_DATE_TIME) + "\"," 
                            + "\"localDate\":\"" + now.toLocalDate() + "\"," 
                            + "\"localTime\":\"" + now.toLocalTime().withNano(0) + "\"," 
                            + "\"zoneId\":\"" + now.getZone().getId() + "\"}";
                })
                .description("Return the current local system date and time, including timezone.")
                .inputType(GetDateTimeRequest.class)
                .build();
    }

            @Bean
            @Restricted
            @ToolCategory("Command Execution")
            public ToolCallback executeTerminalCommand(ExecuteTerminalCommandTool terminalTool) {
            ToolCallback delegate = FunctionToolCallback
                .builder("executeTerminalCommand", terminalTool::execute)
                .description(
                    """
                    Execute a terminal command through the virtual SSH environment and stream the live output back to the caller. Use this for shell workflows that require interactive terminal I/O.
                    """
                        .stripIndent())
                .inputType(ExecuteTerminalCommandRequest.class)
                .build();

            return new VisualizableToolCallback(delegate, terminalTool::formatAuthorizationDetails);
            }

    @Bean
    @Restricted
    @ToolCategory("SSH & File Transfer")
    public ToolCallback connectSsh(SshConnectTool sshConnectTool) {
        ToolCallback delegate = FunctionToolCallback
                .builder("connectSsh", sshConnectTool::execute)
                .description("""
                    Establish an SSH connection to a remote host and start an interactive shell session. \
                    REASONING_HINT: Include the host and alias in the authorization reasoning. \
                    Invoke this tool when the user says 'ssh <host>', 'connect <host>', or asks to connect to a server. \
                    The host may be specified as user@host:port, user@host, host:port, or just host — the user@ prefix is an SSH login username, never a friendly label. \
                    ALIAS_HINT: when the user says 'connect to X as Y' or 'call it Y', Y is a friendly alias for the connection — put Y in the 'alias' field and leave the username out of 'host' unless explicitly given. \
                    An optional alias can be provided to refer to the connection by a short name in subsequent tool calls."""
                        .stripIndent())
                .inputType(SshConnectRequest.class)
                .build();
        return new VisualizableToolCallback(delegate, sshConnectTool::formatAuthorizationDetails);
    }

    @Bean
    @Restricted
    @ToolCategory("SSH & File Transfer")
    public ToolCallback createSshConnection(SshCreateConnectionTool sshCreateConnectionTool) {
        ToolCallback delegate = FunctionToolCallback
                .builder("createSshConnection", sshCreateConnectionTool::execute)
                .description("""
                    Save a new SSH connection by collecting hostname, port, username and credentials \
                    through a secure form — credentials never appear in the conversation history. \
                    REASONING_HINT: Use this tool when the user wants to add, register, or set up a new SSH server \
                    rather than connect to one immediately. \
                    After saving, the connection can be opened with the connectSsh tool. \
                    An optional alias can be provided to give the connection a friendly name."""
                        .stripIndent())
                .inputType(SshCreateConnectionRequest.class)
                .build();
        return new VisualizableToolCallback(delegate, sshCreateConnectionTool::formatAuthorizationDetails);
    }
    public ToolCallback sshDownloadFile(DownloadFileTool downloadFileTool) {
        return FunctionToolCallback
                .builder("sshDownloadFile", downloadFileTool::execute)
                .description("""
                    Download a file from a remote SSH host to either Vork's file storage service (no extra \
                    authorization required) or a local filesystem path (requires explicit user authorization). \
                    REASONING_HINT: Include the remote file path and destination in the authorization reasoning. \
                    Requires an active SSH connection established with connectSsh."""
                        .stripIndent())
                .inputType(DownloadFileRequest.class)
                .build();
    }

    @Bean
    @Restricted
    @ToolCategory("SSH & File Transfer")
    public ToolCallback sshUploadFile(UploadFileTool uploadFileTool) {
        return FunctionToolCallback
                .builder("sshUploadFile", uploadFileTool::execute)
                .description("""
                    Upload a file to a remote SSH host via SFTP. If the file is already in Vork's file storage \
                    service (specified by UUID or filename), it is uploaded immediately. If the filename refers \
                    to a local filesystem path, explicit user authorization is required first. \
                    REASONING_HINT: Include the file source and remote destination in the authorization reasoning. \
                    Requires an active SSH connection established with connectSsh."""
                        .stripIndent())
                .inputType(UploadFileRequest.class)
                .build();
    }

    @Bean
    @Restricted
    @ToolCategory("SSH & File Transfer")
    public ToolCallback sshUploadTextFile(UploadTextFileTool uploadTextFileTool) {
        return FunctionToolCallback
                .builder("sshUploadTextFile", uploadTextFileTool::execute)
                .description("""
                    Write text content directly to a file on a remote SSH host via SFTP. \
                    The content is provided as a string and written as UTF-8. \
                    Use this instead of sshUploadFile when the content is already available as text \
                    (e.g. a generated script, config file, or document) rather than a stored file. \
                    REASONING_HINT: Include the remote destination path and a brief summary of the content in the authorization reasoning. \
                    Requires an active SSH connection established with connectSsh."""
                        .stripIndent())
                .inputType(UploadTextFileRequest.class)
                .build();
    }

    @Bean
    @ToolCategory("SSH & File Transfer")
    public ToolCallback listSshConnections(ListSshConnectionsTool listSshConnectionsTool) {
        return FunctionToolCallback
                .builder("listSshConnections", listSshConnectionsTool::execute)
                .description("""
                    List all active SSH connections for the current session, showing each connection's \
                    alias and hostname. Invoke when the user asks which hosts are connected, \
                    or to see open SSH sessions."""
                        .stripIndent())
                .inputType(ListSshConnectionsRequest.class)
                .build();
    }

    @Bean
    @ToolCategory("SSH & File Transfer")
    public ToolCallback setSshAlias(SetSshAliasTool setSshAliasTool) {
        return FunctionToolCallback
                .builder("setSshAlias", setSshAliasTool::execute)
                .description("""
                    Rename the alias of an existing SSH connection. \
                    REASONING_HINT: Include the current identifier and the new alias in the authorization reasoning. \
                    Invoke when the user says 'alias <host> as <name>' or 'rename connection <x> to <y>'. \
                    The hostOrAlias field accepts the current alias or hostname to identify the connection."""
                        .stripIndent())
                .inputType(SetSshAliasRequest.class)
                .build();
    }

    @Bean
    @ToolCategory("SSH & File Transfer")
    public ToolCallback disconnectSsh(DisconnectSshTool disconnectSshTool) {
        return FunctionToolCallback
                .builder("disconnectSsh", disconnectSshTool::execute)
                .description("""
                    Close an active SSH connection and release all associated resources (terminal sessions, \
                    SFTP client, and the underlying SSH client). \
                    REASONING_HINT: Include the host or alias being disconnected in the authorization reasoning. \
                    Invoke when the user says 'disconnect <host>', 'close ssh <alias>', or 'exit <host>'."""
                        .stripIndent())
                .inputType(DisconnectSshRequest.class)
                .build();
    }

    @Bean
    @Restricted
    @ToolCategory("SSH & File Transfer")
    public ToolCallback deleteSshConnection(DeleteSshConnectionTool deleteSshConnectionTool) {
        ToolCallback delegate = FunctionToolCallback
                .builder("deleteSshConnection", deleteSshConnectionTool::execute)
                .description("""
                    Permanently delete a saved SSH connection (VorkNode) from Vork storage, \
                    and disconnect any active session to that host. This removes the stored \
                    host key, username, and credentials — the connection cannot be restored \
                    without reconnecting and re-verifying the host key. \
                    REASONING_HINT: Include the host or alias being deleted in the authorization reasoning. \
                    Invoke when the user says 'remove ssh <host>', 'forget <alias>', or 'delete connection <x>'."""
                        .stripIndent())
                .inputType(DeleteSshConnectionRequest.class)
                .build();
        return new VisualizableToolCallback(delegate, deleteSshConnectionTool::formatAuthorizationDetails);
    }

    /**
     * {@code oauthConnect} tool — configure or connect an OAuth client for the
     * current user, returning a connect URL when consent is required.
     */
    @Bean
    @ToolCategory("Web")
    public ToolCallback oauthConnect(OAuthClientService oauthClientService,
                                     SecureCredentialStore secureCredentialStore) {
        ToolCallback schemaCallback = FunctionToolCallback
                .builder("oauthConnect", (OAuthConnectRequest req) -> "{}")
                .description("""
                    Configure or connect a user-scoped OAuth client. If not yet authorized,
                    this tool suspends with an input form when configuration or OOB consent is required.
                    IMPORTANT: infer and populate as many oauthConnect fields as possible from the
                    user's requested target service (e.g. endpoints, scopes, provider-specific
                    authorizationParams). Minimize required manual user input.
                    If this tool returns status=requires_service_defaults, you MUST retry oauthConnect
                    in the same turn with inferred authorizeEndpoint, tokenEndpoint, scopes,
                    and authorizationParams before requesting user input.
                    After callback completion, call this tool again to get status=ready and a Bearer placeholder token key
                    such as {{OAUTH_GITHUB_ACCESS_TOKEN}} for httpRequest headers.
                    """.stripIndent())
                .inputType(OAuthConnectRequest.class)
                .build();

        ToolDefinition definition = schemaCallback.getToolDefinition();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return call(toolInput, null);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                OAuthConnectRequest req;
                try {
                    req = toolInput == null || toolInput.isBlank()
                            ? new OAuthConnectRequest(null, null, null, null, null, null, null, null, null)
                            : objectMapper.readValue(toolInput, OAuthConnectRequest.class);
                } catch (Exception parseError) {
                    return "{\"status\":\"error\",\"message\":\"Invalid oauthConnect input JSON\"}";
                }

                String argumentsJson = safeJson(req);
                try {
                    String username = resolveUsername();
                    if (username == null || username.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"Authenticated user is required\"}";
                    }

                    OAuthConnectRequest effectiveReq = hydrateOAuthConnectRequest(req, username, secureCredentialStore);

                    List<String> missingServiceFields = missingServiceDefaults(effectiveReq);
                    if (!missingServiceFields.isEmpty()) {
                        Map<String, Object> retryHint = new LinkedHashMap<>();
                        retryHint.put("status", "requires_service_defaults");
                        retryHint.put("clientName", firstNonBlank(effectiveReq.clientName(), ""));
                        retryHint.put("missingFields", missingServiceFields);
                        retryHint.put("message",
                                "Populate oauthConnect with provider-specific defaults inferred from the target service before requesting user input. "
                                        + "At minimum include authorizeEndpoint, tokenEndpoint, scopes, and authorizationParams.");
                        retryHint.put("guidance",
                                "Retry oauthConnect with inferred values based on the user's target service. "
                                        + "Do not ask the user for endpoints/scopes if they can be inferred.");
                        return objectMapper.writeValueAsString(retryHint);
                    }

                    Map<String, Object> result = oauthClientService.connectOrEnsure(username, effectiveReq);

                    String status = String.valueOf(result.getOrDefault("status", ""));
                    if ("error".equalsIgnoreCase(status)
                            && String.valueOf(result.getOrDefault("message", "")).toLowerCase(Locale.ROOT)
                            .contains("not configured")) {
                        String suggestedRedirectUri = suggestedRedirectUri();
                        InteractionFormSchema schema = oauthConfigurationForm(effectiveReq, suggestedRedirectUri);
                        throw new ToolSuspensionException(
                                "oauthConnect",
                                argumentsJson,
                                "OAuth client configuration is required before authorization can continue.",
                                schema);
                    }

                    if ("connect_required".equalsIgnoreCase(status)) {
                        InteractionFormSchema schema = oauthConsentForm(effectiveReq, result);
                        throw new ToolSuspensionException(
                                "oauthConnect",
                                argumentsJson,
                                "Complete OAuth consent in your browser, then continue this prompt.",
                                schema);
                    }

                    return objectMapper.writeValueAsString(result);
                } catch (ToolSuspensionException ex) {
                    throw ex;
                } catch (Exception e) {
                    return "{\"status\":\"error\",\"message\":\""
                            + e.getMessage().replace("\"", "'") + "\"}";
                }
            }
        };
    }

    /**
     * {@code oauthReset} tool — clear saved OAuth client state for the current user.
     */
    @Bean
    @ToolCategory("Web")
    public ToolCallback oauthReset(OAuthClientService oauthClientService) {
        ToolCallback schemaCallback = FunctionToolCallback
                .builder("oauthReset", (OAuthResetRequest req) -> "{}")
                .description("""
                    Reset a saved OAuth client for the current user.
                    This deletes persisted OAuth client details (including tokens)
                    and pending OAuth connect sessions for that client.
                    Use this before a clean oauthConnect attempt.
                    """.stripIndent())
                .inputType(OAuthResetRequest.class)
                .build();

        ToolDefinition definition = schemaCallback.getToolDefinition();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                OAuthResetRequest req;
                try {
                    req = toolInput == null || toolInput.isBlank()
                            ? new OAuthResetRequest(null)
                            : objectMapper.readValue(toolInput, OAuthResetRequest.class);
                } catch (Exception parseError) {
                    return "{\"status\":\"error\",\"message\":\"Invalid oauthReset input JSON\"}";
                }

                String username = resolveUsername();
                if (username == null || username.isBlank()) {
                    return "{\"status\":\"error\",\"message\":\"Authenticated user is required\"}";
                }

                try {
                    Map<String, Object> result = oauthClientService.resetClient(username, req.clientName());
                    return objectMapper.writeValueAsString(result);
                } catch (Exception e) {
                    return "{\"status\":\"error\",\"message\":\""
                            + e.getMessage().replace("\"", "'") + "\"}";
                }
            }
        };
    }

    private OAuthConnectRequest hydrateOAuthConnectRequest(OAuthConnectRequest req,
                                                           String username,
                                                           SecureCredentialStore secureCredentialStore) {
        String clientName = firstNonBlank(req == null ? null : req.clientName(), contextString("clientName"));
        String authorizeEndpoint = firstNonBlank(req == null ? null : req.authorizeEndpoint(), contextString("authorizeEndpoint"));
        String tokenEndpoint = firstNonBlank(req == null ? null : req.tokenEndpoint(), contextString("tokenEndpoint"));
        String clientId = firstNonBlank(req == null ? null : req.clientId(), contextString("clientId"));
        String redirectUri = firstNonBlank(req == null ? null : req.redirectUri(), contextString("redirectUri"));
        if (isUnresolvedRedirectUri(redirectUri)) {
            redirectUri = null;
        }
        if (redirectUri == null || redirectUri.isBlank()) {
            redirectUri = suggestedRedirectUri();
        }

        List<String> scopes = (req != null && req.scopes() != null && !req.scopes().isEmpty())
                ? req.scopes()
                : parseScopes(contextString("scopes"));

        Map<String, String> authorizationParams = (req != null && req.authorizationParams() != null)
                ? req.authorizationParams()
                : parseAuthorizationParams(contextString("authorizationParams"));

        Boolean forceReconnect = req == null ? null : req.forceReconnect();
        if (forceReconnect == null) {
            forceReconnect = parseBoolean(contextString("forceReconnect"));
        }

        String clientSecret = req == null ? null : req.clientSecret();
        if (clientSecret == null || clientSecret.isBlank()) {
            clientSecret = secureCredentialStore.getSecretForUser(username, OAUTH_SECRET_CLIENT_SECRET_KEY);
        }

        return new OAuthConnectRequest(
                clientName,
                authorizeEndpoint,
                tokenEndpoint,
                clientId,
                clientSecret,
                redirectUri,
                scopes,
                authorizationParams,
                forceReconnect);
    }

    private InteractionFormSchema oauthConfigurationForm(OAuthConnectRequest effectiveReq,
                                                         String suggestedRedirectUri) {
        String clientNameValue = firstNonBlank(effectiveReq.clientName(), "gmail");
        String authorizeEndpointValue = firstNonBlank(effectiveReq.authorizeEndpoint(), "");
        String tokenEndpointValue = firstNonBlank(effectiveReq.tokenEndpoint(), "");
        String clientIdValue = firstNonBlank(effectiveReq.clientId(), "");
        String scopesValue = scopesPlaceholder(effectiveReq.scopes());
        String authorizationParamsValue = authorizationParamsPlaceholder(effectiveReq.authorizationParams());
        String forceReconnectValue = String.valueOf(Boolean.TRUE.equals(effectiveReq.forceReconnect()));

        List<FormField> fields = List.of(
                new FormField("clientName", "TEXT", "clientName", clientNameValue, clientNameValue, true, FieldSource.CONTEXT, null),
                new FormField("authorizeEndpoint", "TEXT", "authorizeEndpoint", authorizeEndpointValue, authorizeEndpointValue, true, FieldSource.CONTEXT, null),
                new FormField("tokenEndpoint", "TEXT", "tokenEndpoint", tokenEndpointValue, tokenEndpointValue, true, FieldSource.CONTEXT, null),
                new FormField("clientId", "TEXT", "clientId", clientIdValue, clientIdValue, true, FieldSource.CONTEXT, null),
                new FormField("clientSecret", "PASSWORD", "clientSecret (optional for PKCE public clients)", "", null, false, FieldSource.SECRET, null),
                new FormField("redirectUri", "READONLY", "redirectUri", suggestedRedirectUri, suggestedRedirectUri, true, FieldSource.CONTEXT, null),
                new FormField("scopes", "TEXTAREA", "scopes (space/comma/newline separated)", scopesValue, scopesValue, false, FieldSource.CONTEXT, null),
                new FormField("authorizationParams", "TEXTAREA", "authorizationParams JSON", authorizationParamsValue, authorizationParamsValue, false, FieldSource.CONTEXT, null),
                new FormField("forceReconnect", "CHECKBOX", "forceReconnect", forceReconnectValue, forceReconnectValue, false, FieldSource.CONTEXT, null)
        );

        return new InteractionFormSchema(
                "COLLECT_OAUTH_CONNECT_INPUT",
                "OAuth Configuration Required",
                "Provide OAuth client details to continue. Register the redirectUri with your provider before authorizing.",
                fields,
                List.of(
                        new FormAction("ONCE", "Save & Continue", "primary"),
                        new FormAction("DENIED", "Cancel", "danger")
                ));
    }

    private InteractionFormSchema oauthConsentForm(OAuthConnectRequest effectiveReq,
                                                   Map<String, Object> result) {
        String authorizationUrl = String.valueOf(result.getOrDefault("authorizationUrl", ""));
        String clientName = firstNonBlank(effectiveReq.clientName(), "provider");
        List<FormField> fields = List.of(
            new FormField("authorizationUrl", "HIDDEN", "authorizationUrl", authorizationUrl, authorizationUrl, false, FieldSource.CONTEXT, null)
        );

        return new InteractionFormSchema(
                "OAUTH_AUTHORIZE_OUT_OF_BAND",
                "OAuth Authorization Required",
            "Connect your account to continue.",
                fields,
                List.of(
                new FormAction("ONCE", "Connect to " + clientName, "primary"),
                        new FormAction("DENIED", "Cancel", "danger")
                ));
    }

    private static String suggestedRedirectUri() {
        String requestBaseUrl = RequestOriginContext.resolveBaseUrlFromCurrentRequest();
        if (requestBaseUrl != null && !requestBaseUrl.isBlank()) {
            return requestBaseUrl + "/api/oauth/callback";
        }

        String contextBaseUrl = contextString("__request_base_url__");
        if (contextBaseUrl != null && !contextBaseUrl.isBlank()) {
            return contextBaseUrl.trim() + "/api/oauth/callback";
        }

        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null && attrs.getRequest() != null) {
                String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
                return base + "/api/oauth/callback";
            }
        } catch (Exception ignored) {
        }
        return "https://<your_ip_address>/api/oauth/callback";
    }

    private static boolean isUnresolvedRedirectUri(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("<your_ip_address>")
                || (normalized.contains("<") && normalized.contains(">"));
    }

    private static String firstNonBlank(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return fallback;
    }

    private static String contextString(String key) {
        Object value = ToolExecutionContext.get(key);
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private static Boolean parseBoolean(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return "true".equalsIgnoreCase(raw.trim()) || "on".equalsIgnoreCase(raw.trim()) || "1".equals(raw.trim());
    }

    private static List<String> parseScopes(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        String[] parts = raw.split("[\\n,\\s]+");
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out.isEmpty() ? null : List.copyOf(out);
    }

    private Map<String, String> parseAuthorizationParams(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(raw, Map.class);
            Map<String, String> out = new LinkedHashMap<>();
            parsed.forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null) {
                    out.put(k, String.valueOf(v));
                }
            });
            return out.isEmpty() ? null : Map.copyOf(out);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String scopesPlaceholder(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return "";
        }
        return String.join("\n", scopes);
    }

    private String authorizationParamsPlaceholder(Map<String, String> authorizationParams) {
        if (authorizationParams == null || authorizationParams.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(authorizationParams);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static List<String> missingServiceDefaults(OAuthConnectRequest req) {
        List<String> missing = new ArrayList<>();
        if (req == null) {
            return List.of("authorizeEndpoint", "tokenEndpoint", "scopes", "authorizationParams");
        }
        if (req.authorizeEndpoint() == null || req.authorizeEndpoint().isBlank()) {
            missing.add("authorizeEndpoint");
        }
        if (req.tokenEndpoint() == null || req.tokenEndpoint().isBlank()) {
            missing.add("tokenEndpoint");
        }
        if (req.scopes() == null || req.scopes().isEmpty()) {
            missing.add("scopes");
        }
        if (req.authorizationParams() == null || req.authorizationParams().isEmpty()) {
            missing.add("authorizationParams");
        }
        return missing;
    }

    /**
     * {@code httpRequest} tool — a generic HTTP client that replaces the old
     * {@code getURLContents} tool.  Supports all common methods, custom headers,
     * and a request body so the model can interact with REST APIs, fetch web pages,
     * and submit forms.
     */
    @Bean
    @ToolCategory("Web")
    public ToolCallback httpRequest(OAuthClientService oauthClientService) {
        return FunctionToolCallback
                .builder("httpRequest", (HttpRequestToolRequest req) -> {
                    if (req == null || req.url() == null || req.url().isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"url is required\"}";
                    }

                    try {
                        URI uri = URI.create(req.url().trim());
                        String scheme = uri.getScheme();
                        if (scheme == null
                                || (!"http".equalsIgnoreCase(scheme)
                                    && !"https".equalsIgnoreCase(scheme))) {
                            return "{\"status\":\"error\",\"message\":\"Only http and https URLs are supported\"}";
                        }

                        String method = req.method() != null && !req.method().isBlank()
                                ? req.method().trim().toUpperCase()
                                : "GET";

                        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                                .timeout(Duration.ofSeconds(30))
                                .header("User-Agent", "vork-ai-tool/1.0");

                        String username = resolveUsername();

                        // Apply caller-supplied headers (User-Agent may be overridden)
                        if (req.headers() != null) {
                            for (Map.Entry<String, String> entry : req.headers().entrySet()) {
                                String value = oauthClientService.resolveHeaderValue(username, entry.getValue());
                                builder.header(entry.getKey(), value);
                            }
                        }

                        // Body publisher
                        HttpRequest.BodyPublisher bodyPublisher =
                                (req.body() != null && !req.body().isEmpty())
                                ? HttpRequest.BodyPublishers.ofString(req.body())
                                : HttpRequest.BodyPublishers.noBody();

                        builder.method(method, bodyPublisher);

                        HttpResponse<String> response = HttpClient.newHttpClient()
                                .send(builder.build(), HttpResponse.BodyHandlers.ofString());

                        // Collect response headers as a Map<String, List<String>>,
                        // but flatten single-value headers to strings for readability
                        Map<String, Object> responseHeaders = new java.util.LinkedHashMap<>();
                        response.headers().map().forEach((k, vals) -> {
                            if (vals.size() == 1) {
                                responseHeaders.put(k, vals.get(0));
                            } else {
                                responseHeaders.put(k, vals);
                            }
                        });

                        String content = response.body() == null ? "" : response.body();
                        if (content.length() > 20_000) {
                            content = content.substring(0, 20_000) + "\n...<truncated>";
                        }

                        Map<String, Object> result = new java.util.LinkedHashMap<>();
                        result.put("statusCode", response.statusCode());
                        result.put("headers", responseHeaders);
                        result.put("body", content);
                        return objectMapper.writeValueAsString(result);

                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\""
                                + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description("""
                    Send an HTTP request and return the response status code, headers, and body.
                    Supports GET, POST, PUT, PATCH, DELETE, HEAD, and OPTIONS.
                    Use this to interact with REST APIs, fetch web pages, or submit forms.
                    For GET requests put query parameters in the URL.
                    For POST/PUT/PATCH supply the body as a string and set the Content-Type header.
                    The response body is truncated to 20 000 characters.
                    """.stripIndent())
                .inputType(HttpRequestToolRequest.class)
                .build();
    }

    /**
     * {@code createMongoDBConnection} tool — stores and validates a MongoDB
     * connection profile for the authenticated user.
     */
    @Bean
    @Restricted
    @ToolCategory("Data & Integrations")
    public ToolCallback createMongoDBConnection(MongoToolService mongoToolService) {
        ToolCallback delegate = FunctionToolCallback
                .builder("createMongoDBConnection", (CreateMongoDbConnectionRequest req) -> {
                    String username = resolveUsername();
                    if (username == null || username.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"Authenticated user is required\"}";
                    }

                    CreateMongoDbConnectionRequest effectiveReq = req != null
                            ? req
                            : new CreateMongoDbConnectionRequest(null, null, null, null, null, null, null, null, null);

                    if (needsMongoConnectionInput(effectiveReq)) {
                        throw new ToolSuspensionException(
                                "createMongoDBConnection",
                                safeJson(effectiveReq),
                                "MongoDB connection details are required before saving credentials.",
                                mongoConnectionInputForm(effectiveReq));
                    }

                    return mongoToolService.createConnection(username, effectiveReq);
                })
                .description(
                        """
                        Create and save a user-scoped MongoDB connection profile for generic CRM/data access tools.
                        Credentials are stored in encrypted user secrets and never need to be repeated after setup.
                        This tool validates connectivity with a ping before saving.
                        Credentials are always collected via a secure user form and should never be passed directly in tool arguments.
                        If required fields are missing, this tool suspends and requests host, port, database, and credentials via a secure form.
                        Use connectionName to save multiple profiles (defaults to 'default').
                        REASONING_HINT: Authorization is required to save MongoDB connection '{{connectionName}}'.
                        """.stripIndent())
                .inputType(CreateMongoDbConnectionRequest.class)
                .build();
        return new VisualizableToolCallback(delegate, argsJson -> {
            try {
                var node = objectMapper.readTree(argsJson);
                String name = node.path("connectionName").asText("default");
                String db = node.path("database").asText("");
                return "Create MongoDB connection profile: " + name + (db.isBlank() ? "" : " (db=" + db + ")");
            } catch (Exception ex) {
                return "Create MongoDB connection profile";
            }
        });
    }

    private static boolean needsMongoConnectionInput(CreateMongoDbConnectionRequest req) {
        if (req == null) {
            return true;
        }
        if (!Boolean.TRUE.equals(req.credentialPromptComplete())) {
            return true;
        }
        boolean hasConnectionString = req.connectionString() != null && !req.connectionString().isBlank();
        if (hasConnectionString) {
            return false;
        }
        return req.host() == null || req.host().isBlank()
            || req.database() == null || req.database().isBlank();
    }

    private static InteractionFormSchema mongoConnectionInputForm(CreateMongoDbConnectionRequest req) {
        String connectionName = req.connectionName() != null && !req.connectionName().isBlank()
            ? req.connectionName() : "default";
        String host = req.host() != null && !req.host().isBlank() ? req.host() : "localhost";
        String port = String.valueOf(req.port() != null && req.port() > 0 ? req.port() : 27017);
        String database = req.database() != null && !req.database().isBlank() ? req.database() : "";
        String authDatabase = req.authDatabase() != null && !req.authDatabase().isBlank() ? req.authDatabase() : "admin";
        String username = req.username() != null ? req.username() : "";

        return new InteractionFormSchema(
            "COLLECT_MONGODB_CONNECTION_INPUT",
            "MongoDB Connection Required",
            "Provide MongoDB connection details. You can use either a full connectionString or host/port/database fields.",
            List.of(
                new FormField("connectionName", "TEXT", "Connection Name", "default", connectionName, true, FieldSource.CONTEXT, null),
                new FormField("connectionString", "TEXT", "connectionString (optional)",
                    "mongodb://user:pass@host:27017/database", req.connectionString(), false, FieldSource.CONTEXT, null),
                new FormField("host", "TEXT", "Host", "localhost", host, false, FieldSource.CONTEXT, null),
                new FormField("port", "NUMBER", "Port", "27017", port, false, FieldSource.CONTEXT, null),
                new FormField("database", "TEXT", "Database", "crm", database, false, FieldSource.CONTEXT, null),
                new FormField("authDatabase", "TEXT", "Auth Database", "admin", authDatabase, false, FieldSource.CONTEXT, null),
                new FormField("username", "TEXT", "Username", "mongodb-user", username, false, FieldSource.SECRET, null),
                new FormField("password", "PASSWORD", "Password", "MongoDB password", null, false, FieldSource.SECRET, null),
                new FormField("credentialPromptComplete", "HIDDEN", "credentialPromptComplete", "true", "true", false, FieldSource.CONTEXT, null)
            ),
            List.of(
                new FormAction("ONCE", "Save & Continue", "primary"),
                new FormAction("DENIED", "Cancel", "danger")
            )
        );
    }

    /**
     * {@code listMongoDBCollections} tool — lists collections for a saved MongoDB
     * connection profile.
     */
    @Bean
    @ToolCategory("Data & Integrations")
    @ToolDepends({"createMongoDBConnection"})
    public ToolCallback listMongoDBCollections(MongoToolService mongoToolService) {
        return FunctionToolCallback
                .builder("listMongoDBCollections", (ListMongoDbCollectionsRequest req) -> {
                    String username = resolveUsername();
                    if (username == null || username.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"Authenticated user is required\"}";
                    }
                    return mongoToolService.listCollections(username, req);
                })
                .description(
                        """
                        List collections in a MongoDB database using a previously saved connection profile.
                        Read-only and unrestricted.
                        """.stripIndent())
                .inputType(ListMongoDbCollectionsRequest.class)
                .build();
    }

    /**
     * {@code getMongoDBCollectionSchema} tool — infers schema hints by sampling
     * documents from a resolved collection.
     */
    @Bean
    @ToolCategory("Data & Integrations")
    @ToolDepends({"createMongoDBConnection", "listMongoDBCollections"})
    public ToolCallback getMongoDBCollectionSchema(MongoToolService mongoToolService) {
        return FunctionToolCallback
                .builder("getMongoDBCollectionSchema", (GetMongoDbCollectionSchemaRequest req) -> {
                    String username = resolveUsername();
                    if (username == null || username.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"Authenticated user is required\"}";
                    }
                    return mongoToolService.getCollectionSchema(username, req);
                })
                .description(
                        """
                        Infer a collection schema by sampling documents.
                        The tool can resolve collection name from natural language query context when collection is omitted.
                        Read-only and unrestricted.
                        """.stripIndent())
                .inputType(GetMongoDbCollectionSchemaRequest.class)
                .build();
    }

    /**
     * {@code searchMongoDBDocuments} tool — generic read/search against external
     * MongoDB collections.
     */
    @Bean
    @ToolCategory("Data & Integrations")
    @ToolDepends({"createMongoDBConnection", "listMongoDBCollections"})
    public ToolCallback searchMongoDBDocuments(MongoToolService mongoToolService) {
        return FunctionToolCallback
                .builder("searchMongoDBDocuments", (SearchMongoDbDocumentsRequest req) -> {
                    String username = resolveUsername();
                    if (username == null || username.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"Authenticated user is required\"}";
                    }
                    return mongoToolService.searchDocuments(username, req);
                })
                .description(
                        """
                        Search/read MongoDB documents using either raw filterJson or natural language query.
                        When filterJson is omitted, query is used to build a fuzzy text filter and resolve collection names.
                        Supports pagination and sorting.
                        Read-only and unrestricted.
                        """.stripIndent())
                .inputType(SearchMongoDbDocumentsRequest.class)
                .build();
    }

    /**
     * {@code insertMongoDBDocument} tool — insert one JSON document into a
     * collection.
     */
    @Bean
    @Restricted
    @ToolCategory("Data & Integrations")
    @ToolDepends({"createMongoDBConnection"})
    public ToolCallback insertMongoDBDocument(MongoToolService mongoToolService) {
        ToolCallback delegate = FunctionToolCallback
                .builder("insertMongoDBDocument", (InsertMongoDbDocumentRequest req) -> {
                    String username = resolveUsername();
                    if (username == null || username.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"Authenticated user is required\"}";
                    }
                    return mongoToolService.insertDocument(username, req);
                })
                .description(
                        """
                        Insert a document into a MongoDB collection for a saved connection profile.
                        This is a write operation and requires authorization.
                        REASONING_HINT: Authorization is required to insert data into '{{collection}}'.
                        """.stripIndent())
                .inputType(InsertMongoDbDocumentRequest.class)
                .build();
        return new VisualizableToolCallback(delegate, argsJson -> {
            try {
                var node = objectMapper.readTree(argsJson);
                return "Insert MongoDB document into collection: " + node.path("collection").asText("(unknown)");
            } catch (Exception ex) {
                return "Insert MongoDB document";
            }
        });
    }

    /**
     * {@code updateMongoDBDocuments} tool — update one or many documents.
     */
    @Bean
    @Restricted
    @ToolCategory("Data & Integrations")
    @ToolDepends({"createMongoDBConnection", "listMongoDBCollections"})
    public ToolCallback updateMongoDBDocuments(MongoToolService mongoToolService) {
        ToolCallback delegate = FunctionToolCallback
                .builder("updateMongoDBDocuments", (UpdateMongoDbDocumentsRequest req) -> {
                    String username = resolveUsername();
                    if (username == null || username.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"Authenticated user is required\"}";
                    }
                    return mongoToolService.updateDocuments(username, req);
                })
                .description(
                        """
                        Update MongoDB documents using raw filterJson or natural language query.
                        updateJson must be a MongoDB update document (for example with $set).
                        This is a write operation and requires authorization.
                        REASONING_HINT: Authorization is required to update MongoDB collection '{{collection}}'.
                        """.stripIndent())
                .inputType(UpdateMongoDbDocumentsRequest.class)
                .build();
        return new VisualizableToolCallback(delegate, argsJson -> {
            try {
                var node = objectMapper.readTree(argsJson);
                return "Update MongoDB documents in collection: " + node.path("collection").asText("(resolved from query)");
            } catch (Exception ex) {
                return "Update MongoDB documents";
            }
        });
    }

    /**
     * {@code deleteMongoDBDocuments} tool — delete one or many documents.
     */
    @Bean
    @Restricted
    @ToolCategory("Data & Integrations")
    @ToolDepends({"createMongoDBConnection", "listMongoDBCollections"})
    public ToolCallback deleteMongoDBDocuments(MongoToolService mongoToolService) {
        ToolCallback delegate = FunctionToolCallback
                .builder("deleteMongoDBDocuments", (DeleteMongoDbDocumentsRequest req) -> {
                    String username = resolveUsername();
                    if (username == null || username.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"Authenticated user is required\"}";
                    }
                    return mongoToolService.deleteDocuments(username, req);
                })
                .description(
                        """
                        Delete MongoDB documents using raw filterJson or natural language query.
                        This is a write operation and requires authorization.
                        REASONING_HINT: Authorization is required to delete records from '{{collection}}'.
                        """.stripIndent())
                .inputType(DeleteMongoDbDocumentsRequest.class)
                .build();
        return new VisualizableToolCallback(delegate, argsJson -> {
            try {
                var node = objectMapper.readTree(argsJson);
                return "Delete MongoDB documents from collection: " + node.path("collection").asText("(resolved from query)");
            } catch (Exception ex) {
                return "Delete MongoDB documents";
            }
        });
    }

    /**
     * {@code logInfo} tool — writes a message to server logs at INFO level.
     */
    @Bean
    @ToolCategory("Diagnostics")
    public ToolCallback logInfo() {
        return FunctionToolCallback
                .builder("logInfo", (LogInfoRequest req) -> {
                    String message = req == null ? null : req.message();
                    if (message == null || message.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"message is required\"}";
                    }
                    log.info("AI logInfo tool message: {}", message);
                    return "{\"status\":\"ok\"}";
                })
                .description(
                        """
                        Write a provided message to application logs at INFO level.
                        """
                                .stripIndent())
                .inputType(LogInfoRequest.class)
                .build();
    }

    /**
     * {@code compileJavaType} tool — compiles a Java schema from source code
     * supplied by the model, persists it to MongoDB, and loads it into the
     * running JVM so it is available for subsequent operations.
     *
     * <p>
     * The tool returns a small JSON object:
     * <ul>
     * <li>{@code {"status":"ok","class":"sh.vork.generated.Foo"}} on success.</li>
     * <li>{@code {"status":"error","message":"..."}} on failure.</li>
     * </ul>
     */
    @Bean
    @Restricted
    @ToolCategory("Schema & Types")
    public ToolCallback compileJavaType(TypeGeneratorService typeGeneratorService) {
        ToolCallback delegate = FunctionToolCallback
                .builder("compileJavaType", (CompileTypeRequest req) -> {
                    try {
                        Class<?> clazz = typeGeneratorService.compileAndSave(req.source());
                        return "{\"status\":\"ok\",\"class\":\"" + clazz.getName() + "\"}";
                    } catch (TypeGenerationException e) {
                        return "{\"status\":\"error\",\"message\":\"" +
                                e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description(
                        """
Compile a Java schema (record, class, interface, or enum) from source code and load it into the running application. 
The schema is persisted to MongoDB and will be available after a restart. 
Returns the fully-qualified class name on success. 
If a type implements sh.vork.orm.DatabaseEntity, uuid must be String (String uuid(); and field/component type String), never java.util.UUID. 
Any record should implement sh.vork.orm.DatabaseEntity. 
All types should use a sub-package of sh.vork.generated.
When generating a record that will be managed in the Data Inspector UI, annotate record components with @sh.vork.typegen.DisplayField to control table columns and form rendering. Example: @DisplayField(label="Full Name", order=1, tableColumn=true, inputType="text", required=true). Fields not annotated with tableColumn=true will not appear in the table but will still appear in the create/edit form. Use tableColumn=false for nested records, long text, and list fields.
Embedded value-object types (e.g. Address, LineItem) that are only used as nested fields inside a parent record MUST NOT implement DatabaseEntity and MUST NOT have a uuid field. Only top-level records that are stored and queried independently should implement DatabaseEntity. This distinction controls which types appear in the Data Inspector dropdown.
When generating an enum and there is enough context to give each constant a human-readable display name (e.g. country names, status labels, category titles), always add a single String constructor field and a getLabel() accessor: private final String label; EnumName(String label) { this.label = label; } public String getLabel() { return label; }. This enables the Data Inspector to show readable labels in dropdowns and table columns instead of raw constant names. If there is no meaningful display name beyond the constant name itself, omit the field.
If a user asks in natural language to "create a record", "create an enum", "define a record", or "model a record", use this tool.
REASONING_HINT: Authorization is required to compile {{type_name}} record/enum schema.
                                """
                                .stripIndent())
                .inputType(CompileTypeRequest.class)
                .build();

        return new VisualizableToolCallback(delegate, argumentsJson -> {
            try {
                String sourceCode = objectMapper.readTree(argumentsJson)
                        .path("source")
                        .asText();
                if (sourceCode == null || sourceCode.isBlank()) {
                    return argumentsJson;
                }
                return sourceCode;
            } catch (Exception ex) {
                return argumentsJson;
            }
        });
    }

    /**
     * {@code listJavaTypes} tool — returns all custom schemas that have been
     * compiled and persisted to MongoDB via {@link #compileJavaType}.
     */
    @Bean
    @ToolCategory("Schema & Types")
    public ToolCallback listJavaTypes(DatabaseRepository<JavaType> javaTypeRepository) {
        return FunctionToolCallback
                .builder("listJavaTypes", (ListJavaTypesRequest req) -> {
                    List<String> entries = new ArrayList<>();
                    try (var stream = javaTypeRepository.list(0, Integer.MAX_VALUE)) {
                        stream.forEach(jt -> entries.add(
                                "{\"fqn\":\"" + jt.uuid() + "\"," +
                                        "\"classFiles\":" + jt.bytecode().size() + "," +
                                        "\"createdAt\":\"" + new java.util.Date(jt.createdAt()) + "\"}"));
                    }
                    if (entries.isEmpty()) {
                        return "{\"types\":[]}";
                    }
                    return "{\"types\":[" + String.join(",", entries) + "]}";
                })
                .description(
                        """
                                List all custom schemas (records/enums/classes) that have been compiled and persisted to MongoDB. Returns each schema's fully-qualified class name, number of class files (including inner classes), and the date it was first created.
                                """
                                .stripIndent())
                .inputType(ListJavaTypesRequest.class)
                .build();
    }

    /**
     * {@code getJavaTypeSource} tool — retrieves the stored Java source for a
     * compiled type, allowing the model to read it before making targeted edits.
     */
    @Bean
    @ToolCategory("Schema & Types")
    public ToolCallback getJavaTypeSource(DatabaseRepository<JavaType> javaTypeRepository) {
        return FunctionToolCallback
                .builder("getJavaTypeSource", (GetTypeSchemaRequest req) -> {
                    JavaType jt = javaTypeRepository.get(req.fqn());
                    if (jt == null) {
                        return "{\"status\":\"error\",\"message\":\"Type not found: " + req.fqn() + "\"}";
                    }
                    return "{\"fqn\":\"" + jt.uuid() + "\",\"source\":" +
                            objectMapper.valueToTree(jt.source()).toString() + "}";
                })
                .description(
                        """
                                Retrieve the stored Java source code for a compiled schema by its fully-qualified class name. \
                                Use this before modifying a record/enum so you can read the existing definition and make targeted changes \
                                rather than rewriting it from scratch.
                                """
                                .stripIndent())
                .inputType(GetTypeSchemaRequest.class)
                .build();
    }

    // -------------------------------------------------------------------------
    // TypeDatabase CRUD tools
    // -------------------------------------------------------------------------

    /**
     * {@code getTypeSchema} tool — returns a JSON schema derived from the record's
     * components, so the model knows exactly what fields and types to supply.
     */
    @Bean
    @ToolCategory("Schema & Types")
    public ToolCallback getTypeSchema() {
        return FunctionToolCallback
                .builder("getTypeSchema", (GetTypeSchemaRequest req) -> {
                    try {
                        Class<?> clazz = typeClassLoader.loadClass(req.fqn());
                        return "{\"schema\":" + buildSchema(clazz) + "}";
                    } catch (ClassNotFoundException e) {
                        return "{\"status\":\"error\",\"message\":\"Type not found: " + req.fqn() + "\"}";
                    }
                })
                .description(
                        """
                                Get the JSON field schema for a custom record by its fully-qualified class name. Use listJavaTypes first to discover available schemas.
                                """
                                .stripIndent())
                .inputType(GetTypeSchemaRequest.class)
                .build();
    }

    /**
     * {@code saveTypeInstance} tool — deserialises a JSON string into the named
     * type
     * and persists it via {@link TypeDatabaseService}.
     */
    @Bean
    @ToolCategory("Schema & Types")
    public ToolCallback saveTypeInstance() {
        return FunctionToolCallback
                .builder("saveTypeInstance", (SaveTypeInstanceRequest req) -> {
                    try {
                        Class<?> clazz = typeClassLoader.loadClass(req.fqn());
                        Object instance = objectMapper.readValue(req.json(), clazz);
                        typeDatabaseService.save(instance);
                        String uuid = (String) clazz.getMethod("uuid").invoke(instance);
                        return "{\"status\":\"ok\",\"uuid\":\"" + uuid + "\"}";
                    } catch (ClassNotFoundException e) {
                        return "{\"status\":\"error\",\"message\":\"Type not found: " + req.fqn() + "\"}";
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description(
                        """
                                Save (create or update) a record instance. Provide the fully-qualified class name and a JSON string representing the record. The JSON must include a uuid field — generate a random UUID v4 string for new instances. Use getTypeSchema to discover the required fields first.
                                """
                                .stripIndent())
                .inputType(SaveTypeInstanceRequest.class)
                .build();
    }

    /**
     * {@code getTypeInstance} tool — returns a single stored record instance by UUID.
     */
    @Bean
    @ToolCategory("Schema & Types")
    public ToolCallback getTypeInstance() {
        return FunctionToolCallback
                .builder("getTypeInstance", (GetTypeInstanceRequest req) -> {
                    try {
                        Class<?> clazz = typeClassLoader.loadClass(req.fqn());
                        Object entity = typeDatabaseService.get(clazz, req.uuid());
                        if (entity == null) {
                            return "{\"status\":\"not_found\",\"message\":\"Record not found for uuid: "
                                    + req.uuid().replace("\"", "'") + "\"}";
                        }
                        return objectMapper.writeValueAsString(entity);
                    } catch (ClassNotFoundException e) {
                        return "{\"status\":\"error\",\"message\":\"Type not found: " + req.fqn() + "\"}";
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description(
                        """
                                Fetch a single stored record instance by fully-qualified class name and uuid.
                                """
                                .stripIndent())
                .inputType(GetTypeInstanceRequest.class)
                .build();
    }

    /**
     * {@code listTypeInstances} tool — returns all persisted instances of a custom
     * type
     * as a JSON array.
     */
    @Bean
    @ToolCategory("Schema & Types")
    public ToolCallback listTypeInstances() {
        return FunctionToolCallback
                .builder("listTypeInstances", (ListTypeInstancesRequest req) -> {
                    try {
                        Class<?> clazz = typeClassLoader.loadClass(req.fqn());
                        int page = req.page() != null ? req.page() : 0;
                        int pageSize = req.pageSize() != null && req.pageSize() > 0 ? req.pageSize() : 20;
                        List<Object> items = new ArrayList<>();
                        try (var stream = typeDatabaseService.list(clazz, page, pageSize)) {
                            stream.forEach(items::add);
                        }
                        return objectMapper.writeValueAsString(items);
                    } catch (ClassNotFoundException e) {
                        return "{\"status\":\"error\",\"message\":\"Type not found: " + req.fqn() + "\"}";
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description(
                        """
                                List stored record instances by fully-qualified class name. Supports pagination via page (default 0) and pageSize (default 20).
                                """
                                .stripIndent())
                .inputType(ListTypeInstancesRequest.class)
                .build();
    }

    /**
     * {@code countTypeInstances} tool — counts stored record instances, optionally filtered.
     */
    @Bean
    @ToolCategory("Schema & Types")
    public ToolCallback countTypeInstances() {
        return FunctionToolCallback
                .builder("countTypeInstances", (CountTypeInstancesRequest req) -> {
                    try {
                        Class<?> clazz = typeClassLoader.loadClass(req.fqn());
                        String query = req.query();
                        if (query == null || query.isBlank()) {
                            return "{\"count\":" + typeDatabaseService.count(clazz) + "}";
                        }
                        String queryType = req.queryType() == null ? "SQL" : req.queryType().toUpperCase(Locale.ROOT);
                        long count = "MONGO".equals(queryType)
                                ? typeDatabaseService.searchCountByMongoFilter(clazz, query)
                                : typeDatabaseService.searchCountBySql(clazz, query);
                        return "{\"count\":" + count + "}";
                    } catch (ClassNotFoundException e) {
                        return "{\"status\":\"error\",\"message\":\"Type not found: " + req.fqn() + "\"}";
                    } catch (SqlParseException e) {
                        return "{\"status\":\"error\",\"message\":\"SQL parse error: "
                                + e.getMessage().replace("\"", "'") + "\"}";
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\""
                                + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description(
                        """
                                Count stored record instances for a class. Optionally provide a SQL or Mongo filter query to count matching records only.
                                """
                                .stripIndent())
                .inputType(CountTypeInstancesRequest.class)
                .build();
    }

    /**
     * {@code listEnumValues} tool — returns all declared constants of an enum
     * class resolved via {@link JavaTypeClassLoader}.
     */
    @Bean
    @ToolCategory("Schema & Types")
    public ToolCallback listEnumValues() {
        return FunctionToolCallback
                .builder("listEnumValues", (ListEnumValuesRequest req) -> {
                    try {
                        Class<?> clazz = typeClassLoader.loadClass(req.fqn());
                        if (!clazz.isEnum()) {
                            return "{\"status\":\"error\",\"message\":\"" + req.fqn() + " is not an enum\"}";
                        }
                        Object[] constants = clazz.getEnumConstants();
                        StringBuilder sb = new StringBuilder("{\"fqn\":\"");
                        sb.append(req.fqn()).append("\",\"values\":[");
                        for (int i = 0; i < constants.length; i++) {
                            if (i > 0)
                                sb.append(',');
                            sb.append('\"').append(constants[i].toString()).append('\"');
                        }
                        sb.append("]}");
                        return sb.toString();
                    } catch (ClassNotFoundException e) {
                        return "{\"status\":\"error\",\"message\":\"Type not found: " + req.fqn() + "\"}";
                    }
                })
                .description(
                        """
                                List all declared constants of an enum by its fully-qualified class name. Use listJavaTypes to discover available types first.
                                """
                                .stripIndent())
                .inputType(ListEnumValuesRequest.class)
                .build();
    }

    /**
     * {@code deleteTypeInstance} tool — deletes a persisted instance by UUID.
     */
    @Bean
    public ToolCallback deleteTypeInstance() {
        return FunctionToolCallback
                .builder("deleteTypeInstance", (DeleteTypeInstanceRequest req) -> {
                    try {
                        Class<?> clazz = typeClassLoader.loadClass(req.fqn());
                        typeDatabaseService.delete(clazz, req.uuid());
                        return "{\"status\":\"ok\"}";
                    } catch (ClassNotFoundException e) {
                        return "{\"status\":\"error\",\"message\":\"Type not found: " + req.fqn() + "\"}";
                    }
                })
                .description(
                        """
                                Delete a stored record instance by UUID. Requires the fully-qualified class name and the instance UUID.
                                """
                                .stripIndent())
                .inputType(DeleteTypeInstanceRequest.class)
                .build();
    }

    /**
     * {@code searchTypeInstances} tool — searches stored instances of a custom Java
     * type using either a SQL-like WHERE clause or a raw MongoDB filter JSON.
     */
    @Bean
    public ToolCallback searchTypeInstances() {
        return FunctionToolCallback
                .builder("searchTypeInstances", (SearchTypeInstancesRequest req) -> {
                    try {
                        Class<?> clazz = typeClassLoader.loadClass(req.fqn());
                        int page = req.page() != null ? req.page() : 0;
                        int pageSize = req.pageSize() != null && req.pageSize() > 0
                                ? req.pageSize()
                                : 20;
                        String sortField = req.sortField() != null ? req.sortField() : "uuid";
                        SortOrder order = "DESC".equalsIgnoreCase(req.sortOrder())
                                ? SortOrder.DESC
                                : SortOrder.ASC;
                        String queryType = req.queryType() != null
                                ? req.queryType().toUpperCase()
                                : "SQL";

                        List<Object> items = new ArrayList<>();
                        long total;

                        if ("MONGO".equals(queryType)) {
                            try (var stream = typeDatabaseService.searchByMongoFilter(
                                    clazz, req.query(), page, pageSize, sortField, order)) {
                                stream.forEach(items::add);
                            }
                            total = typeDatabaseService.searchCountByMongoFilter(clazz, req.query());
                        } else {
                            try (var stream = typeDatabaseService.searchBySql(
                                    clazz, req.query(), page, pageSize, sortField, order)) {
                                stream.forEach(items::add);
                            }
                            total = typeDatabaseService.searchCountBySql(clazz, req.query());
                        }

                        String resultsJson = objectMapper.writeValueAsString(items);
                        return "{\"total\":" + total + ",\"page\":" + page
                                + ",\"pageSize\":" + pageSize + ",\"results\":" + resultsJson + "}";
                    } catch (ClassNotFoundException e) {
                        return "{\"status\":\"error\",\"message\":\"Type not found: " + req.fqn() + "\"}";
                    } catch (SqlParseException e) {
                        return "{\"status\":\"error\",\"message\":\"SQL parse error: "
                                + e.getMessage().replace("\"", "'") + "\"}";
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\""
                                + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description(
                        """
                                Search stored record instances using either a SQL-like WHERE clause (queryType: SQL, default) or a raw MongoDB JSON filter (queryType: MONGO). SQL examples: "name = 'Alice'", "age > 18 AND active = true", "address.city = 'London'", "status IN ('active', 'pending')", "name LIKE '%ali%'". MONGO example: {"status":"active","age":{"$gt":18}}. Supports pagination (page, pageSize) and sorting (sortField, sortOrder).
                                """
                                .stripIndent())
                .inputType(SearchTypeInstancesRequest.class)
                .build();
    }

    // -------------------------------------------------------------------------
    // JSON schema helpers
    // -------------------------------------------------------------------------

    private static String buildSchema(Class<?> clazz) {
        if (clazz == String.class)
            return "{\"type\":\"string\"}";
        if (clazz == int.class || clazz == Integer.class ||
                clazz == long.class || clazz == Long.class)
            return "{\"type\":\"integer\"}";
        if (clazz == double.class || clazz == Double.class ||
                clazz == float.class || clazz == Float.class ||
                clazz == java.math.BigDecimal.class)
            return "{\"type\":\"number\"}";
        if (clazz == boolean.class || clazz == Boolean.class)
            return "{\"type\":\"boolean\"}";
        if (clazz.isRecord()) {
            StringBuilder sb = new StringBuilder("{\"type\":\"object\",\"title\":\"")
                    .append(clazz.getSimpleName()).append("\",\"properties\":{");
            RecordComponent[] comps = clazz.getRecordComponents();
            for (int i = 0; i < comps.length; i++) {
                if (i > 0)
                    sb.append(',');
                sb.append('"').append(comps[i].getName()).append("\":");
                sb.append(schemaForType(comps[i].getType(), comps[i].getGenericType()));
            }
            sb.append("}}");
            return sb.toString();
        }
        return "{\"type\":\"object\"}";
    }

    private static String schemaForType(Class<?> type, Type generic) {
        if (type == List.class || type == java.util.Collection.class) {
            String itemSchema = "{\"type\":\"object\"}";
            if (generic instanceof ParameterizedType pt) {
                Type arg = pt.getActualTypeArguments()[0];
                if (arg instanceof Class<?> c)
                    itemSchema = buildSchema(c);
            }
            return "{\"type\":\"array\",\"items\":" + itemSchema + "}";
        }
        return buildSchema(type);
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    /**
     * {@code listNotificationProviders} tool — discovers configured notification
     * providers that support sending to arbitrary (unregistered) addresses.
     *
     * <p>The AI should call this before {@code sendNotification} to determine which
     * providers are available and what address types each one accepts.
     */
    @Bean
    @ToolCategory("Notifications")
    public ToolCallback listNotificationProviders(DirectNotificationService directNotificationService) {
        return FunctionToolCallback
                .builder("listNotificationProviders",
                        (ListNotificationProvidersRequest req) -> {
                            log.debug("Tool listNotificationProviders invoked");
                            try {
                                var providers = directNotificationService.listAvailable();
                                return objectMapper.writeValueAsString(providers);
                            } catch (Exception e) {
                                return "{\"status\":\"error\",\"message\":\""
                                        + e.getMessage().replace("\"", "'") + "\"}";
                            }
                        })
                .description(
                        "List all configured notification providers that can send to arbitrary "
                        + "addresses (email, SMS). Returns each provider's configId, displayName, "
                        + "providerKey, and supported mediaTypes (EMAIL_ADDRESS, PHONE_NUMBER). "
                        + "Call this before sendNotification to choose the right provider.")
                .inputType(ListNotificationProvidersRequest.class)
                .build();
    }

    /**
     * {@code sendNotification} tool — sends a notification to an arbitrary address
     * using a specific configured provider.
     *
     * <p>Requires prior approval ({@link Restricted}) because it delivers messages
     * to external addresses.
     */
    @Bean
    @Restricted
    @ToolCategory("Notifications")
    @ToolDepends({"listNotificationProviders"})
    public ToolCallback sendNotification(DirectNotificationService directNotificationService) {
        ToolCallback delegate = FunctionToolCallback
                .builder("sendNotification",
                        (SendNotificationRequest req) -> {
                            log.debug("Tool sendNotification invoked: providerConfigId={}, address={}",
                                    req.providerConfigId(), req.address());
                            String result = directNotificationService.send(
                                    req.providerConfigId(), req.title(), req.body(), req.address());
                            if ("ok".equals(result)) {
                                return "{\"status\":\"ok\"}";
                            }
                            return "{\"status\":\"error\",\"message\":\""
                                    + result.replace("\"", "'") + "\"}";
                        })
                .description(
                        "Send a notification to an arbitrary email address or phone number. "
                        + "Call listNotificationProviders first to get a valid providerConfigId "
                        + "and confirm the address type is supported. "
                        + "address must match the provider type: email address for email providers, "
                        + "E.164 phone number (e.g. +14155552671) for SMS providers.")
                .inputType(SendNotificationRequest.class)
                .build();
        return new VisualizableToolCallback(delegate, argumentsJson -> {
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(argumentsJson);
                String providerConfigId = node.path("providerConfigId").asText(null);
                String to      = node.path("address").asText("(unknown)");
                String subject = node.path("title").asText("(no subject)");
                String body    = node.path("body").asText("");
                return directNotificationService.formatDirectNotification(providerConfigId, to, subject, body);
            } catch (Exception ex) {
                return argumentsJson;
            }
        });
    }

    private static String resolveSessionUuid() {
        String sessionUuid = MDC.get("sessionUuid");
        if (sessionUuid == null || sessionUuid.isBlank() || "<null>".equals(sessionUuid)) {
            return "system";
        }
        return sessionUuid;
    }

    private static String resolveUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()
                || "anonymousUser".equalsIgnoreCase(auth.getName())) {
            return null;
        }
        return auth.getName();
    }

}
