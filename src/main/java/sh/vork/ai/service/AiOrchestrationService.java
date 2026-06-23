package sh.vork.ai.service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.AiProvider;
import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.config.AiConfig;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.memory.SessionEnvironmentService;
import sh.vork.ai.provider.AiChatClientFactory;
import sh.vork.ai.registry.ToolDepends;
import sh.vork.ai.session.SessionToolStore;
import sh.vork.orm.DatabaseRepository;

/**
 * Routes AI generation requests to the appropriate {@link ChatClient} at runtime.
 *
 * <h3>Dynamic routing</h3>
 * The injected {@code Map<AiProvider, ChatClient>} is the single source of truth
 * for which backend backs which enum value.  Adding a new provider only requires
 * updating {@code AiConfig} — this class never changes.
 *
 * <h3>The {@code mutate()} pattern</h3>
 * Each call goes through {@link ChatClient#mutate()} which returns a fresh
 * {@link ChatClient.Builder} pre-seeded with the shared client's configuration
 * (default functions, options, system prompt, etc.).  Building a new instance
 * from that builder gives a per-request {@link ChatClient} with an isolated
 * call chain, so:
 * <ul>
 *   <li>The shared base client is never modified between concurrent calls.</li>
 *   <li>Per-request overrides (extra system instructions, option tweaks, additional
 *       tools) can be applied to the mutated builder before building, without
 *       leaking to other in-flight calls.</li>
 * </ul>
 */
@Service
public class AiOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(AiOrchestrationService.class);
        private static final String BACKGROUND_OPERATIONAL_PROTOCOL = """
BACKGROUND OPERATIONAL PROTOCOL: You are executing autonomously in an isolated background thread. You must perform all necessary analysis and tool calls across multiple message rounds without expecting further human input. Once you have validated that the requested objective is entirely satisfied (e.g., your types compile successfully and records are saved), you MUST invoke the completeBackgroundTask tool to cleanly finalize the run. You MUST provide a boolean 'success' value and a 'report' string summarising what was done and produced. Do not exit without invoking this tool.
                        """.stripIndent();

        /**
         * Builds the structured-output mandate injected at the end of every system prompt.
         * The set of valid statuses is narrowed based on what the current agent/context
         * is actually able to do, avoiding confusion from status values the AI cannot use.
         *
         * @param inSkillFrame  true when the AI is executing inside a sandboxed skill
         * @param isBackground  true for BACKGROUND_SCHEDULER sessions
         * @param canDelegate   true only for orchestrator agents that may assign sub-agents
         * @param canSwitchAgent true for interactive sessions where the user can change agents
         */
        private static String buildResponseMandate(boolean inSkillFrame,
                                                   boolean isBackground,
                                                   boolean canDelegate,
                                                   boolean canSwitchAgent) {
                if (inSkillFrame) {
                        return "\n\n### TURN OUTPUT REQUIREMENT\n"
                                + "Return a single valid JSON object. No markdown fences, no explanation outside the JSON:\n"
                                + "{\n"
                                + "  \"status\": \"FINISHED_TURN\",\n"
                                + "  \"textResponse\": \"<the complete skill output>\"\n"
                                + "}\n"
                                + "FINISHED_TURN exits the skill. textResponse IS the skill output — "
                                + "format it exactly as SKILL DIRECTIVES specify. "
                                + "Do NOT call any tool to signal completion; returning FINISHED_TURN with your result IS the exit signal.";
                }

                StringBuilder sb = new StringBuilder("\n\n### TURN OUTPUT REQUIREMENT\n");
                sb.append("Return a single valid JSON object. No markdown fences, no explanation outside the JSON:\n{\n");
                sb.append("  \"status\": \"FINISHED_TURN");
                if (canDelegate) sb.append(" | DELEGATE_TURN");
                if (canSwitchAgent) sb.append(" | SWITCH_AGENT");
                sb.append("\",\n");
                sb.append("  \"textResponse\": \"<your complete response>\"");
                if (canDelegate || canSwitchAgent) {
                        sb.append(",\n  \"targetAgent\": \"<exact agent display name, or null>\"");
                        sb.append(",\n  \"delegationInstructions\": \"<full self-contained task for sub-agent, or null>\"");
                }
                sb.append("\n}\n");

                sb.append("FINISHED_TURN: Task complete — textResponse MUST be a complete, substantive result or summary. ");
                sb.append("Do NOT set FINISHED_TURN with an empty or status-only message — use the think tool for mid-turn updates.\n");
                if (canDelegate) {
                        sb.append("DELEGATE_TURN: Assign work to a specialist agent — set targetAgent to their exact display name "
                                + "and provide comprehensive instructions in delegationInstructions.\n");
                }
                if (canSwitchAgent) {
                        sb.append("SWITCH_AGENT: Only when the user explicitly asks to change to a different agent — "
                                + "set targetAgent to the exact display name and confirm in textResponse.\n");
                }

                if (!isBackground) {
                        sb.append("You may ask the user clarifying questions when the task is ambiguous before taking action.\n");
                } else {
                        sb.append("Do NOT ask for user input — you are running autonomously without human supervision.\n");
                }

                return sb.toString();
        }

    /**
     * Stable model alias to fall back to when the session's requested model is
     * deprecated, removed (404/400), or otherwise unavailable.  These should be
     * long-lived, generally-available model names for each provider.
     */
    private static final Map<AiProvider, String> STABLE_FALLBACK_MODELS = Map.of(
            AiProvider.GEMINI,               "gemini-2.5-flash",
            AiProvider.OPENAI,               "gpt-4o",
            AiProvider.OLLAMA,               "llama3.2",
            AiProvider.BACKGROUND_SCHEDULER, "gemini-2.5-flash"
    );

        private final Map<AiProvider, ChatClient> registry;
        private final AiChatClientFactory chatClientFactory;
        private final SessionEnvironmentService sessionEnvironmentService;
        private final DatabaseRepository<AiSession> sessionRepo;
        private final DatabaseRepository<AgentTemplate> agentTemplateRepo;
        private final DatabaseRepository<sh.vork.skill.Skill> skillRepo;
        private final Map<String, ToolCallback> securedToolCallbackMap;
        private final SessionToolStore sessionToolStore;
        private final sh.vork.skill.SkillToolCallbackFactory skillToolCallbackFactory;
        private final ToolCallback listAvailableToolsCallback;
        private final ToolCallback listAgentTemplatesCallback;
        private final ToolCallback getDateTimeCallback;
        private final ToolCallback recordProgressCallback;
        private final ToolCallback memoryCallback;
        private final ToolCallback thinkCallback;
        private final sh.vork.ai.security.SkillSecretSubstitutor skillSecretSubstitutor;
        private final ConfigurableListableBeanFactory beanFactory;
        private final ObjectMapper objectMapper;

        @org.springframework.beans.factory.annotation.Autowired
        public AiOrchestrationService(Map<AiProvider, ChatClient> chatClientRegistry,
                                                                  AiChatClientFactory chatClientFactory,
                                                                  SessionEnvironmentService sessionEnvironmentService,
                                                                  DatabaseRepository<AiSession> aiSessionRepository,
                                                                  DatabaseRepository<AgentTemplate> agentTemplateRepository,
                                                                  DatabaseRepository<sh.vork.skill.Skill> skillRepository,
                                                                  Map<String, ToolCallback> securedToolCallbackMap,
                                                                  SessionToolStore sessionToolStore,
                                                                  sh.vork.skill.SkillToolCallbackFactory skillToolCallbackFactory,
                                                                  @org.springframework.beans.factory.annotation.Qualifier("listAvailableTools") ToolCallback listAvailableToolsCallback,
                                                                  @org.springframework.beans.factory.annotation.Qualifier("listAgentTemplates") ToolCallback listAgentTemplatesCallback,
                                                                  @org.springframework.beans.factory.annotation.Qualifier("getDateTime") ToolCallback getDateTimeCallback,
                                                                  @org.springframework.beans.factory.annotation.Qualifier("recordProgress") ToolCallback recordProgressCallback,
                                                                  @org.springframework.beans.factory.annotation.Qualifier("memory") ToolCallback memoryCallback,
                                                                  @org.springframework.beans.factory.annotation.Qualifier("think") ToolCallback thinkCallback,
                                                                  sh.vork.ai.security.SkillSecretSubstitutor skillSecretSubstitutor,
                                                                  ConfigurableListableBeanFactory beanFactory,
                                                                  ObjectMapper objectMapper) {
                this.registry = chatClientRegistry;
                this.chatClientFactory = chatClientFactory;
                this.sessionEnvironmentService = sessionEnvironmentService;
                this.sessionRepo = aiSessionRepository;
                this.agentTemplateRepo = agentTemplateRepository;
                this.skillRepo = skillRepository;
                this.securedToolCallbackMap = securedToolCallbackMap;
                this.sessionToolStore = sessionToolStore;
                this.skillToolCallbackFactory = skillToolCallbackFactory;
                this.listAvailableToolsCallback = listAvailableToolsCallback;
                this.listAgentTemplatesCallback = listAgentTemplatesCallback;
                this.getDateTimeCallback = getDateTimeCallback;
                this.recordProgressCallback = recordProgressCallback;
                this.memoryCallback = memoryCallback;
                this.thinkCallback = thinkCallback;
                this.skillSecretSubstitutor = skillSecretSubstitutor;
                this.beanFactory = beanFactory;
                this.objectMapper = objectMapper;
    }

        public AiOrchestrationService(Map<AiProvider, ChatClient> chatClientRegistry,
                                                                  AiChatClientFactory chatClientFactory,
                                                                  SessionEnvironmentService sessionEnvironmentService,
                                                                  DatabaseRepository<AiSession> aiSessionRepository,
                                                                  DatabaseRepository<AgentTemplate> agentTemplateRepository,
                                                                  DatabaseRepository<sh.vork.skill.Skill> skillRepository,
                                                                  Map<String, ToolCallback> securedToolCallbackMap,
                                                                  SessionToolStore sessionToolStore,
                                                                  sh.vork.skill.SkillToolCallbackFactory skillToolCallbackFactory,
                                                                  ToolCallback listAvailableToolsCallback,
                                                                  ToolCallback listAgentTemplatesCallback,
                                                                  ToolCallback getDateTimeCallback,
                                                                  ToolCallback recordProgressCallback,
                                                                  ToolCallback memoryCallback,
                                                                  ToolCallback thinkCallback,
                                                                  sh.vork.ai.security.SkillSecretSubstitutor skillSecretSubstitutor) {
                this(chatClientRegistry,
                        chatClientFactory,
                        sessionEnvironmentService,
                        aiSessionRepository,
                        agentTemplateRepository,
                        skillRepository,
                        securedToolCallbackMap,
                        sessionToolStore,
                        skillToolCallbackFactory,
                        listAvailableToolsCallback,
                        listAgentTemplatesCallback,
                        getDateTimeCallback,
                        recordProgressCallback,
                        memoryCallback,
                        thinkCallback,
                        skillSecretSubstitutor,
                        null,
                        new ObjectMapper());
        }

        public AiOrchestrationService(Map<AiProvider, ChatClient> chatClientRegistry,
                                                                  AiChatClientFactory chatClientFactory,
                                                                  SessionEnvironmentService sessionEnvironmentService,
                                                                  DatabaseRepository<AiSession> aiSessionRepository,
                                                                  DatabaseRepository<AgentTemplate> agentTemplateRepository,
                                                                  DatabaseRepository<sh.vork.skill.Skill> skillRepository,
                                                                  Map<String, ToolCallback> securedToolCallbackMap,
                                                                  SessionToolStore sessionToolStore,
                                                                  sh.vork.skill.SkillToolCallbackFactory skillToolCallbackFactory,
                                                                  ToolCallback listAvailableToolsCallback,
                                                                  ToolCallback listAgentTemplatesCallback,
                                                                  ToolCallback getDateTimeCallback,
                                                                  ToolCallback recordProgressCallback,
                                                                  ToolCallback thinkCallback) {
                this(chatClientRegistry,
                        chatClientFactory,
                        sessionEnvironmentService,
                        aiSessionRepository,
                        agentTemplateRepository,
                        skillRepository,
                        securedToolCallbackMap,
                        sessionToolStore,
                        skillToolCallbackFactory,
                        listAvailableToolsCallback,
                        listAgentTemplatesCallback,
                        getDateTimeCallback,
                        recordProgressCallback,
                        null,
                        thinkCallback,
                        null,
                        null,
                        new ObjectMapper());
        }

        public AiOrchestrationService(Map<AiProvider, ChatClient> chatClientRegistry,
                                                                  AiChatClientFactory chatClientFactory,
                                                                  SessionEnvironmentService sessionEnvironmentService,
                                                                  DatabaseRepository<AiSession> aiSessionRepository,
                                                                  DatabaseRepository<AgentTemplate> agentTemplateRepository,
                                                                  DatabaseRepository<sh.vork.skill.Skill> skillRepository,
                                                                  Map<String, ToolCallback> securedToolCallbackMap,
                                                                  SessionToolStore sessionToolStore,
                                                                  sh.vork.skill.SkillToolCallbackFactory skillToolCallbackFactory,
                                                                  ToolCallback listAvailableToolsCallback,
                                                                  ToolCallback listAgentTemplatesCallback,
                                                                  ToolCallback recordProgressCallback,
                                                                  ToolCallback memoryCallback,
                                                                  ToolCallback thinkCallback,
                                                                  sh.vork.ai.security.SkillSecretSubstitutor skillSecretSubstitutor) {
                this(chatClientRegistry,
                        chatClientFactory,
                        sessionEnvironmentService,
                        aiSessionRepository,
                        agentTemplateRepository,
                        skillRepository,
                        securedToolCallbackMap,
                        sessionToolStore,
                        skillToolCallbackFactory,
                        listAvailableToolsCallback,
                        listAgentTemplatesCallback,
                        null,
                        recordProgressCallback,
                        memoryCallback,
                        thinkCallback,
                        skillSecretSubstitutor,
                        null,
                        new ObjectMapper());
        }

        public AiOrchestrationService(Map<AiProvider, ChatClient> chatClientRegistry,
                                                                  AiChatClientFactory chatClientFactory,
                                                                  SessionEnvironmentService sessionEnvironmentService,
                                                                  DatabaseRepository<AiSession> aiSessionRepository,
                                                                  DatabaseRepository<AgentTemplate> agentTemplateRepository,
                                                                  DatabaseRepository<sh.vork.skill.Skill> skillRepository,
                                                                  Map<String, ToolCallback> securedToolCallbackMap,
                                                                  SessionToolStore sessionToolStore,
                                                                  sh.vork.skill.SkillToolCallbackFactory skillToolCallbackFactory,
                                                                  ToolCallback listAvailableToolsCallback,
                                                                  ToolCallback listAgentTemplatesCallback,
                                                                  ToolCallback getDateTimeCallback,
                                                                  ToolCallback recordProgressCallback,
                                                                  ToolCallback thinkCallback,
                                                                  sh.vork.ai.security.SkillSecretSubstitutor skillSecretSubstitutor,
                                                                  ConfigurableListableBeanFactory beanFactory,
                                                                  ObjectMapper objectMapper) {
                this(chatClientRegistry,
                        chatClientFactory,
                        sessionEnvironmentService,
                        aiSessionRepository,
                        agentTemplateRepository,
                        skillRepository,
                        securedToolCallbackMap,
                        sessionToolStore,
                        skillToolCallbackFactory,
                        listAvailableToolsCallback,
                        listAgentTemplatesCallback,
                        getDateTimeCallback,
                        recordProgressCallback,
                        null,
                        thinkCallback,
                        skillSecretSubstitutor,
                        beanFactory,
                        objectMapper);
        }

    /**
     * Generates a text response for {@code userPrompt} using the specified provider.
     *
     * @param userPrompt the user's prompt text
     * @param provider   the AI backend to route to
     * @return the model's response as a plain string
     * @throws IllegalArgumentException if the provider has no registered client
     */
    public String generate(String userPrompt, AiProvider provider) {
        ChatClient base = resolveClient(provider);

        // mutate() seeds a fresh builder from the shared client's config so
        // per-request changes (e.g. additional tools, system prompt override)
        // never bleed into other concurrent calls.
        log.info("Generating response [provider={}] prompt=\"{}\"...",
                provider, userPrompt.length() > 120 ? userPrompt.substring(0, 120) + "…" : userPrompt);

        String effectiveText = withBackgroundDirective(userPrompt, provider);
        String response = callWithFallback(
                builder -> builder.build().prompt().user(effectiveText).call().content(),
                base, provider);

        log.info("Response received [provider={}, length={}]: {}",
                provider,
                response == null ? 0 : response.length(),
                response == null ? "<null>" : (response.length() > 200 ? response.substring(0, 200) + "…" : response));

        return response;
    }

    /**
     * Generates a welcome message using the active agent's system prompt with a
     * welcome-specific instruction appended.  All tool callbacks are stripped so
     * the model cannot trigger secured tools or authorization challenges.
     *
     * <p>The model is instructed to return a JSON object with
     * {@code status=FINISHED_TURN} and the welcome text in {@code textResponse},
     * consistent with the normal structured-response protocol.  Callers should
     * unwrap the result via {@code ChatService.extractTextResponse()}.
     *
     * @param provider the AI backend to route to
     * @return the model's raw response (JSON or plain text)
     */
    public String generateWelcomeMessage(AiProvider provider) {
        ChatClient base = resolveClient(provider);
        log.info("Generating welcome message [provider={}]...", provider);
        String systemPrompt = composeSystemPrompt()
                + "\n\n### WELCOME MESSAGE INSTRUCTION\n"
                + "This is the start of a new chat session. Your ONLY task right now is to provide "
                + "a short, friendly welcome message that introduces yourself and summarises the "
                + "capabilities available to the user. "
                + "Return a single JSON object with no markdown fences:\n"
                + "{\n  \"status\": \"FINISHED_TURN\",\n  \"textResponse\": \"<your welcome message>\"\n}\n"
                + "Do NOT call any tools. Do NOT perform any actions.";
        return callWithFallback(
                builder -> builder
                        .defaultSystem(systemPrompt)
                        .defaultToolCallbacks(new org.springframework.ai.tool.ToolCallback[0])
                        .build()
                        .prompt()
                        .user("Please provide your welcome message now.")
                        .call()
                        .content(),
                base, provider);
    }

    /**
     * Generates a response using prior conversation history for context.
     *
     * @param conversationHistory previous turns as Spring AI {@link Message} objects
     * @param newUserMessage      the latest user input
     * @param provider            the AI backend to route to
     * @return the model's response as a plain string
     */
    public String generateWithHistory(List<Message> conversationHistory, String newUserMessage, AiProvider provider) {
        ChatClient base = resolveClient(provider);

        log.info("Generating chat response [provider={}, history={} msgs]...", provider, conversationHistory.size());

        Message[] historyArray = conversationHistory.toArray(Message[]::new);
        String effectiveUser   = withBackgroundDirective(newUserMessage, provider);
        String response = callWithFallback(
                builder -> builder.build().prompt().messages(historyArray).user(effectiveUser).call().content(),
                base, provider);

        log.info("Chat response received [provider={}, length={}]",
                provider, response == null ? 0 : response.length());

        return response;
    }

    /**
     * Generates a response with conversation history and media attachments.
     *
     * <p>The {@code media} list is attached to the current user turn so that
     * vision / multimodal models can reason over the provided files.  Pass an
     * empty list (never {@code null}) when there are no attachments.
     *
     * @param conversationHistory previous turns
     * @param userText            the user's text message (may be blank if only media)
     * @param media               Spring AI {@link Media} objects to attach
     * @param provider            the AI backend to route to
     * @return the model's response as a plain string
     */
    public String generateWithHistoryAndMedia(List<Message> conversationHistory,
                                              String userText,
                                              List<Media> media,
                                              AiProvider provider) {
        ChatClient base = resolveClient(provider);

        log.info("Generating chat response with media [provider={}, history={} msgs, media={}]",
                provider, conversationHistory.size(), media.size());

        List<Message> allMessages = new ArrayList<>(conversationHistory);
        String effectiveText = (userText == null || userText.isBlank()) ? "Please analyse the attached file(s)." : userText;
        effectiveText = withBackgroundDirective(effectiveText, provider);
        allMessages.add(UserMessage.builder().text(effectiveText).media(media).build());

        Message[] allMsgsArray = allMessages.toArray(Message[]::new);
        String response = callWithFallback(
                builder -> builder.build().prompt().messages(allMsgsArray).call().content(),
                base, provider);

        log.info("Chat response with media received [provider={}, length={}]",
                provider, response == null ? 0 : response.length());

        return response;
    }

        private static String withBackgroundDirective(String text, AiProvider provider) {
                String baseText = text == null ? "" : text;
                if (provider != AiProvider.BACKGROUND_SCHEDULER) {
                        return baseText;
                }
                return BACKGROUND_OPERATIONAL_PROTOCOL + "\n\n" + baseText;
        }

        private static final String SKILL_OPERATIONAL_PROTOCOL =
                "SKILL EXECUTION PROTOCOL: You are executing a sandboxed skill.\n"
                + "MANDATORY RULES:\n"
                + "1. Use the available tools to gather information, run commands, or process data.\n"
                + "2. When your objective is complete, return FINISHED_TURN. "
                +    "Your textResponse IS the skill output — this is the exit signal.\n"
                + "3. If SKILL DIRECTIVES specify an output format, textResponse MUST follow it exactly.\n"
                + "4. Do not end a turn without either using a tool or returning FINISHED_TURN with a result.\n"
                + "5. Mid-turn commentary: call the think tool first, then immediately invoke your next action tool. "
                +    "Never emit a bare status message without following it with a tool call in the same turn.";

        private static final String AVAILABLE_TOOL_DETAILS_CONTEXT_KEY = "__available_tool_details__";

        private String composeSystemPrompt() {
                String sessionUuid = ToolExecutionContext.getSessionUuid();
                StringBuilder prompt = new StringBuilder(AiConfig.BASE_SYSTEM_PROMPT);

                if (sessionUuid == null || sessionUuid.isBlank()) {
                        log.debug("System prompt composed [session=none, origin=ANONYMOUS, chars={}]", prompt.length());
                        return prompt.toString();
                }

                // Inject active agent template directives
                AiSession session = sessionRepo.get(sessionUuid);
                if (session != null) {
                        // For sessions with an active skill frame: inject skill instructions + protocol
                        if (session.skillStack() != null && !session.skillStack().isEmpty()) {
                                sh.vork.skill.SkillFrame topFrame = session.skillStack().getLast();
                                String skillPrompt = topFrame.instructions();

                                prompt.append("\n\n").append(SKILL_OPERATIONAL_PROTOCOL);

                                if (skillPrompt != null && !skillPrompt.isBlank()) {
                                        prompt.append("\n\n### SKILL DIRECTIVES\n").append(skillPrompt);
                                }
                                // Output format mandate belongs in the system prompt, not in a user message,
                                // so the model treats it as a hard constraint rather than a request.
                                String outputTemplate = topFrame.outputTemplate();
                                if (outputTemplate != null && !outputTemplate.isBlank()) {
                                        prompt.append("\n\n### REQUIRED OUTPUT FORMAT\n")
                                                .append("Your textResponse MUST conform exactly to this template — ")
                                                .append("same structure, same fields, no deviations:\n")
                                                .append(outputTemplate);
                                }
                                // List sub-skill tools explicitly so the model knows the exact names to call.
                                // Without this, lite models don't discover sub-skills from function declarations alone.
                                sh.vork.skill.Skill frameSkill = skillRepo.get(topFrame.skillUuid());
                                if (frameSkill != null && !frameSkill.subSkillUuids().isEmpty()) {
                                        List<sh.vork.skill.Skill> subSkills = frameSkill.subSkillUuids().stream()
                                                .map(skillRepo::get)
                                                .filter(Objects::nonNull)
                                                .toList();
                                        if (!subSkills.isEmpty()) {
                                                prompt.append("\n\n### AVAILABLE SUB-SKILLS\n");
                                                prompt.append("The following sub-skills are available as tools. ");
                                                prompt.append("Call them by their tool name — do NOT use executeSkill.\n\n");
                                                for (sh.vork.skill.Skill sub : subSkills) {
                                                        prompt.append("- `").append(sub.toolName()).append("`");
                                                        if (!sub.description().isBlank())
                                                                prompt.append(": ").append(sub.description());
                                                        if (!sub.parameters().isEmpty()) {
                                                                prompt.append(" | Parameters: ");
                                                                prompt.append(sub.parameters().stream()
                                                                        .map(p -> p.name() + "(" + p.type() + ")")
                                                                        .collect(Collectors.joining(", ")));
                                                        }
                                                        prompt.append("\n");
                                                }
                                        }
                                }

                                // Inject available secrets so the model knows which {{TOKEN}} to use.
                                if (frameSkill != null
                                        && frameSkill.secrets() != null
                                        && !frameSkill.secrets().isEmpty()) {
                                        prompt.append("\n\n### AVAILABLE SECRETS\n");
                                        prompt.append("The following named secrets are pre-configured for this skill. ");
                                        prompt.append("Use each placeholder token verbatim as the value in any tool argument where the secret is needed ");
                                        prompt.append("(e.g. in an HTTP header value or request body). ");
                                        prompt.append("Do NOT ask the user for these values — they are injected automatically.\n\n");
                                        for (sh.vork.skill.SkillSecret s : frameSkill.secrets()) {
                                                prompt.append("- `{{").append(s.name()).append("}}`");
                                                if (!s.description().isBlank())
                                                        prompt.append(" — ").append(s.description());
                                                prompt.append("\n");
                                        }
                                }

                                // Append skill-specific mandate: FINISHED_TURN is the only exit signal.
                                appendEnvironmentVariables(prompt, sessionUuid);
                                appendAvailableToolsSection(prompt);
                                prompt.append(buildResponseMandate(true, false, false, false));
                                return logAndReturn(prompt, sessionUuid, "SKILL");
                        }

                        if (session.originMode() == SessionOriginMode.BACKGROUND) {
                                prompt.append("\n\n").append(BACKGROUND_OPERATIONAL_PROTOCOL);

                                Object persistentTask = ToolExecutionContext.get("__background_task_instruction__");
                                String taskText = persistentTask == null ? null : persistentTask.toString();
                                if ((taskText == null || taskText.isBlank())
                                        && session.environmentVariables() != null) {
                                        taskText = session.environmentVariables().get("JOB_TASK_PROMPT");
                                }
                                if (taskText != null && !taskText.isBlank()) {
                                        prompt.append("\n\n### BACKGROUND TASK DIRECTIVE\n")
                                                .append(taskText);
                                }

                                Object turnInstruction = ToolExecutionContext.get("__background_turn_instruction__");
                                if (turnInstruction != null) {
                                        String turnText = turnInstruction.toString();
                                        if (!turnText.isBlank()) {
                                                prompt.append("\n\n### BACKGROUND TURN DIRECTIVE\n")
                                                        .append(turnText);
                                        }
                                }
                        }

                        String agentId = session.getActiveAgentTemplateId();
                        if (agentId != null) {
                                AgentTemplate template = agentTemplateRepo.get(agentId);
                                if (template != null && !template.systemPrompt().isBlank()) {
                                        prompt.append("\n\n### ACTIVE AGENT DIRECTIVES\n").append(template.systemPrompt());
                                }

                                // Skills are injected as tools at runtime; list them so the model knows the exact tool names.
                                if (template != null && template.skillUuids() != null && !template.skillUuids().isEmpty()) {
                                        List<sh.vork.skill.Skill> skills = template.skillUuids().stream()
                                                .map(skillRepo::get)
                                                .filter(Objects::nonNull)
                                                .toList();
                                        if (!skills.isEmpty()) {
                                                prompt.append("\n\n### AVAILABLE SKILLS\n");
                                                prompt.append("The following skills are available to you as tools. ");
                                                prompt.append("Call them by their tool name. ");
                                                prompt.append("Tool visibility is attachment-based: only call tools that are available in this turn ");
                                                prompt.append("(directly attached to this agent/session, or exposed by the active skill frame):\n\n");
                                                for (sh.vork.skill.Skill s : skills) {
                                                        prompt.append("- `").append(s.toolName()).append("`");
                                                        if (!s.description().isBlank())
                                                                prompt.append(": ").append(s.description());
                                                        prompt.append("\n");
                                                }
                                        }
                                }
                        }
                        // Also list session-level skills so the model knows their tool names
                        List<String> sessionSkillUuids = session.sessionSkillUuids();
                        if (sessionSkillUuids != null && !sessionSkillUuids.isEmpty()) {
                                List<sh.vork.skill.Skill> sessionSkills = sessionSkillUuids.stream()
                                        .map(skillRepo::get)
                                        .filter(Objects::nonNull)
                                        .toList();
                                if (!sessionSkills.isEmpty()) {
                                        // Append to existing ### AVAILABLE SKILLS block if already started, else create it
                                        if (!prompt.toString().contains("### AVAILABLE SKILLS")) {
                                                prompt.append("\n\n### AVAILABLE SKILLS\n");
                                                prompt.append("The following skills are available to you as tools. ");
                                                prompt.append("Call them by their tool name. ");
                                                prompt.append("Tool visibility is attachment-based: only call tools that are available in this turn ");
                                                prompt.append("(directly attached to this agent/session, or exposed by the active skill frame):\n\n");
                                        }
                                        for (sh.vork.skill.Skill s : sessionSkills) {
                                                prompt.append("- `").append(s.toolName()).append("`");
                                                if (!s.description().isBlank())
                                                        prompt.append(": ").append(s.description());
                                                prompt.append("\n");
                                        }
                                }
                        }
                }

                appendEnvironmentVariables(prompt, sessionUuid);

                // Mandate structured output — scope of valid statuses depends on agent capability.
                appendAvailableToolsSection(prompt);
                if (session != null) {
                        boolean isBackground = session.originMode() == SessionOriginMode.BACKGROUND;
                        boolean canDelegate  = isConciergeSession();
                        boolean canSwitch    = !isBackground;
                        prompt.append(buildResponseMandate(false, isBackground, canDelegate, canSwitch));
                }

                String originLabel = session != null ? session.originMode().name() : "UNKNOWN";
                return logAndReturn(prompt, sessionUuid, originLabel);
        }

        private String logAndReturn(StringBuilder prompt, String sessionUuid, String originLabel) {
                String result = prompt.toString();
                log.debug("System prompt composed [session={}, origin={}, chars={}]:\n{}",
                        sessionUuid, originLabel, result.length(), result);
                return result;
        }

        /**
         * Resolves the {@link ChatClient} for the given provider, falling back to the
         * factory for dynamically-configured providers (OpenAI, Ollama) not in the static registry.
         */
        private ChatClient resolveClient(AiProvider provider) {
                ChatClient base = registry.get(provider);
                if (base == null) {
                        base = chatClientFactory.getBaseClient(provider);
                }
                if (base == null) {
                        throw new IllegalArgumentException(
                                "No ChatClient configured for provider: " + provider
                                + ". Configure credentials in Settings → AI Models.");
                }
                return base;
        }

        /**
         * Returns the model ID stored on the active session, or {@code null} if none set.
         */
        private String resolveSessionModelId() {
                String sessionUuid = ToolExecutionContext.getSessionUuid();
                if (sessionUuid == null || sessionUuid.isBlank()) return null;
                AiSession session = sessionRepo.get(sessionUuid);
                return session != null ? session.modelId() : null;
        }

        /**
         * Builds a mutated {@link ChatClient.Builder} from the shared base client with the
         * composed system prompt and, when an active {@link AgentTemplate} restricts the
         * allowed tools, the filtered tool set applied.  If the session has a specific model
         * override, it is applied as a default option on the builder.
         */
        private ChatClient.Builder buildMutatedClient(ChatClient base) {
                return buildMutatedClientInternal(base, null);
        }

        /**
         * Same as {@link #buildMutatedClient(ChatClient)} but forces a specific {@code modelId},
         * ignoring the session's stored model.  Used by the deprecation fallback path.
         */
        private ChatClient.Builder buildMutatedClientWithModel(ChatClient base, String forcedModel) {
                return buildMutatedClientInternal(base, forcedModel);
        }

        private ChatClient.Builder buildMutatedClientInternal(ChatClient base, String forcedModel) {
                // Resolve which tools to expose for this request: filtered subset when an
                // AgentTemplate is active, or the full secured set otherwise.
                // Tools are always set here (never on the base ChatClient) to prevent
                // Spring AI from seeing duplicates when the builder is mutated.
                String sessionUuid = ToolExecutionContext.getSessionUuid();
                AiSession sessionForSkillCheck = (sessionUuid != null && !sessionUuid.isBlank())
                        ? sessionRepo.get(sessionUuid) : null;
                boolean inSkillFrame = sessionForSkillCheck != null
                        && sessionForSkillCheck.skillStack() != null
                        && !sessionForSkillCheck.skillStack().isEmpty();

                // Resolve which tools to expose for this request: filtered subset when an
                // AgentTemplate is active, or the full secured set otherwise.
                // Tools are always set here (never on the base ChatClient) to prevent
                // Spring AI from seeing duplicates when the builder is mutated.
                ToolCallback[] filtered = resolveFilteredToolCallbacks();
                ToolCallback[] tools = (filtered != null)
                        ? filtered
                        : securedToolCallbackMap.values().toArray(ToolCallback[]::new);

                // Merge session-scoped tools (e.g. completeBackgroundTask for background sessions).
                // These are hidden from the global registry and injected programmatically per session.
                List<ToolCallback> sessionTools = sessionToolStore.getTools(sessionUuid);
                List<ToolCallback> merged = new ArrayList<>(List.of(tools));

                // Track names already present so we never register the same tool name twice.
                // executeSkill is not @Hidden, so it may already be in the secured map when no
                // agent filter is active — the guard prevents the duplicate that crashes Spring AI.
                java.util.Set<String> presentNames = merged.stream()
                        .map(t -> t.getToolDefinition().name())
                        .collect(Collectors.toCollection(java.util.HashSet::new));

                // Inject skill tools and concierge tools only when NOT inside a skill frame.
                if (!inSkillFrame) {
                        // Inject each assigned skill as its own ToolCallback so the AI sees
                        // skills as direct tools rather than going through the generic executeSkill.
                        if (sessionForSkillCheck != null
                                && sessionForSkillCheck.getActiveAgentTemplateId() != null
                                && !sessionForSkillCheck.getActiveAgentTemplateId().isBlank()) {
                                AgentTemplate tpl = agentTemplateRepo.get(
                                        sessionForSkillCheck.getActiveAgentTemplateId());
                                if (tpl != null && tpl.skillUuids() != null && !tpl.skillUuids().isEmpty()) {
                                        int injected = 0;
                                        for (String skillUuid : tpl.skillUuids()) {
                                                sh.vork.skill.Skill skill = skillRepo.get(skillUuid);
                                                if (skill == null) {
                                                        log.warn("Agent skill UUID not found in DB — skipping [skillUuid={}]", skillUuid);
                                                        continue;
                                                }
                                                ToolCallback skillTool = skillToolCallbackFactory.create(skill);
                                                String toolName = skillTool.getToolDefinition().name();
                                                if (presentNames.add(toolName)) {
                                                        merged.add(skillTool);
                                                        injected++;
                                                } else {
                                                        log.warn("Skill tool name collision — skipping [toolName={}, skillUuid={}]",
                                                                toolName, skillUuid);
                                                }
                                        }
                                        log.debug("Agent skill tools injected [session={}, agent={}, count={}]",
                                                sessionUuid, sessionForSkillCheck.getActiveAgentTemplateId(), injected);
                                } else {
                                        log.debug("Agent has no skills assigned — none injected [session={}, agent={}]",
                                                sessionUuid, sessionForSkillCheck.getActiveAgentTemplateId());
                                }
                        } else {
                                log.debug("No active agent template — skill injection skipped [session={}]", sessionUuid);
                        }                        // Inject session-level skills pinned by the user for this specific session
                        List<String> sessionSkillUuids = sessionForSkillCheck != null
                                ? sessionForSkillCheck.sessionSkillUuids() : List.of();
                        if (sessionSkillUuids != null && !sessionSkillUuids.isEmpty()) {
                                int sessionInjected = 0;
                                for (String skillUuid : sessionSkillUuids) {
                                        sh.vork.skill.Skill skill = skillRepo.get(skillUuid);
                                        if (skill == null) {
                                                log.warn("Session skill UUID not found in DB — skipping [skillUuid={}]", skillUuid);
                                                continue;
                                        }
                                        ToolCallback skillTool = skillToolCallbackFactory.create(skill);
                                        String toolName = skillTool.getToolDefinition().name();
                                        if (presentNames.add(toolName)) {
                                                merged.add(skillTool);
                                                sessionInjected++;
                                        }
                                }
                                log.debug("Session skill tools injected [session={}, count={}]", sessionUuid, sessionInjected);
                        }
                        if (presentNames.add("listAvailableTools"))  merged.add(listAvailableToolsCallback);
                        if (isConciergeSession() && presentNames.add("listAgentTemplates"))
                                merged.add(listAgentTemplatesCallback);
                }
                if (!sessionTools.isEmpty()) {
                        int beforeMerge = merged.size();
                        for (ToolCallback st : sessionTools) {
                                // completeBackgroundTask belongs only to the parent agent loop.
                                // Exposing it inside a skill frame lets the AI short-circuit the
                                // whole job instead of returning FINISHED_TURN to the skill sub-loop
                                // and then completing the task from the parent loop.
                                if (inSkillFrame && "completeBackgroundTask".equals(st.getToolDefinition().name())) {
                                        continue;
                                }
                                merged.add(st);
                        }
                        int added = merged.size() - beforeMerge;
                        if (added > 0) {
                                log.debug("Merged {} session-scoped tool(s) [session={}]", added, sessionUuid);
                        }
                }
                if (presentNames.add("recordProgress") && recordProgressCallback != null) {
                        merged.add(recordProgressCallback);
                }
                if (presentNames.add("memory") && memoryCallback != null) {
                        merged.add(memoryCallback);
                }
                if (presentNames.add("getDateTime") && getDateTimeCallback != null) {
                        merged.add(getDateTimeCallback);
                }
                // think is mandatory — always available regardless of skill-frame depth.
                if (presentNames.add("think")) merged.add(thinkCallback);
                tools = merged.toArray(ToolCallback[]::new);

                // Wrap every tool callback with the secret substitutor so that {{KEY}} tokens
                // in any tool's arguments are replaced with real secret values before execution.
                // The wrapper is a no-op fast-path when no {{ tokens are present.
                String secretUsername = (sessionForSkillCheck != null) ? sessionForSkillCheck.username() : null;
                if (secretUsername != null && !secretUsername.isBlank()) {
                        final String uname = secretUsername;
                        tools = java.util.Arrays.stream(tools)
                                .map(t -> (ToolCallback) new sh.vork.ai.security.SecretSubstitutingToolCallback(
                                        t, skillSecretSubstitutor, uname))
                                .toArray(ToolCallback[]::new);
                }

                if (log.isDebugEnabled()) {
                        String toolNames = merged.stream()
                                .map(t -> t.getToolDefinition().name())
                                .collect(Collectors.joining(", "));
                        log.debug("Tools available for AI invocation [session={}, count={}, tools=[{}]]",
                                sessionUuid, merged.size(), toolNames);
                }

                ToolExecutionContext.put(AVAILABLE_TOOL_DETAILS_CONTEXT_KEY, buildToolDetails(merged));

                ChatClient.Builder builder = base.mutate()
                        .defaultSystem(composeSystemPrompt())
                        .defaultToolCallbacks(tools);

                String modelId = (forcedModel != null) ? forcedModel : resolveSessionModelId();
                if (modelId != null && !modelId.isBlank()) {
                        log.debug("Applying model override: {}", modelId);
                        builder.defaultOptions(ChatOptions.builder().model(modelId).build());
                }

                return builder;
        }

        /**
         * Executes {@code callFn} against a mutated client, and on a deprecation/not-found
         * error automatically retries once with the provider's stable fallback model.
         *
         * <p>Triggers on exceptions whose message chain contains {@code 404}, {@code 400},
         * {@code "no longer available"}, {@code "deprecated"}, {@code "not found"}, or
         * Google's {@code "INVALID_ARGUMENT"} status code.
         */
        private String callWithFallback(Function<ChatClient.Builder, String> callFn,
                                        ChatClient base,
                                        AiProvider provider) {
                try {
                        return callFn.apply(buildMutatedClient(base));
                } catch (RuntimeException e) {
                        if (!isModelCompatibilityError(e)) throw e;
                        String fallback = STABLE_FALLBACK_MODELS.getOrDefault(provider, "");
                        String reason = isThoughtSignatureError(e) ? "thought_signature not preserved (thinking model)"
                                                                    : "model unavailable/deprecated";
                        log.warn("Model fallback triggered for provider {} [reason={}, fallback=\"{}\", originalError={}]",
                                provider, reason, fallback, e.getMessage());
                        if (fallback.isBlank()) throw e;
                        return callFn.apply(buildMutatedClientWithModel(base, fallback));
                }
        }

        private static boolean isModelCompatibilityError(RuntimeException e) {
                return isDeprecatedModelError(e) || isThoughtSignatureError(e);
        }

        private static boolean isThoughtSignatureError(RuntimeException e) {
                String msg = collectExceptionMessages(e);
                return containsIgnoreCase(msg, "thought_signature");
        }

        /** Returns {@code true} when the exception looks like a deprecated/removed-model error. */
        private static boolean isDeprecatedModelError(RuntimeException e) {
                String msg = collectExceptionMessages(e);
                // Exclude thought_signature 400s — those are a Spring AI compatibility issue, not deprecation.
                if (containsIgnoreCase(msg, "thought_signature")) return false;
                return msg.contains("404")
                        || msg.contains("400")
                        || containsIgnoreCase(msg, "no longer available")
                        || containsIgnoreCase(msg, "deprecated")
                        || containsIgnoreCase(msg, "not found")
                        || containsIgnoreCase(msg, "INVALID_ARGUMENT");
        }

        private static String collectExceptionMessages(Throwable t) {
                StringBuilder sb = new StringBuilder();
                while (t != null) {
                        if (t.getMessage() != null) sb.append(t.getMessage()).append(' ');
                        t = t.getCause();
                }
                return sb.toString();
        }

        private static boolean containsIgnoreCase(String text, String search) {
                return text.toLowerCase().contains(search.toLowerCase());
        }

        /**
         * Returns a filtered array of tool callbacks for the active agent template, or
         * {@code null} if no filtering is needed (i.e., the default tool set should be used).
         */
        private ToolCallback[] resolveFilteredToolCallbacks() {
                String sessionUuid = ToolExecutionContext.getSessionUuid();
                if (sessionUuid == null || sessionUuid.isBlank()) {
                        return new ToolCallback[0];
                }

                AiSession session = sessionRepo.get(sessionUuid);
                if (session == null) {
                        return new ToolCallback[0];
                }

                // Sessions with an active skill frame: tool set is bounded by the top frame's allowedTools.
                // An empty allowedTools list means no hard tools (the AI exits via FINISHED_TURN).
                if (session.skillStack() != null && !session.skillStack().isEmpty()) {
                        sh.vork.skill.SkillFrame topFrame = session.skillStack().getLast();
                        List<String> allowedToolNames = topFrame.allowedTools();

                        List<String> skillToolNames = expandWithToolDependencies(
                                allowedToolNames != null ? allowedToolNames : List.of());
                        // Skills exit via FINISHED_TURN — completeSkillExecution is no longer injected.
                        // Resolve hard tools from the secured map
                        List<ToolCallback> frameTools = new ArrayList<>();
                        List<String> unresolved = new ArrayList<>();
                        for (String name : skillToolNames) {
                                ToolCallback cb = securedToolCallbackMap.get(name);
                                if (cb != null) {
                                        frameTools.add(cb);
                                } else {
                                        unresolved.add(name);
                                }
                        }
                        // Inject sub-skill tools from the skill's subSkillUuids
                        sh.vork.skill.Skill frameSkill = skillRepo.get(topFrame.skillUuid());
                        if (frameSkill != null && !frameSkill.subSkillUuids().isEmpty()) {
                                java.util.Set<String> frameNames = frameTools.stream()
                                        .map(t -> t.getToolDefinition().name())
                                        .collect(Collectors.toCollection(java.util.HashSet::new));
                                int subInjected = 0;
                                for (String subUuid : frameSkill.subSkillUuids()) {
                                        sh.vork.skill.Skill subSkill = skillRepo.get(subUuid);
                                        if (subSkill == null) {
                                                log.warn("Sub-skill UUID not found in DB — skipping [subUuid={}]", subUuid);
                                                continue;
                                        }
                                        ToolCallback subTool = skillToolCallbackFactory.create(subSkill);
                                        if (frameNames.add(subTool.getToolDefinition().name())) {
                                                frameTools.add(subTool);
                                                subInjected++;
                                        }
                                }
                                log.debug("Sub-skill tools injected [session={}, skill={}, count={}]",
                                        sessionUuid, topFrame.skillName(), subInjected);
                        } else {
                                log.debug("No sub-skills configured for skill frame [session={}, skill={}]",
                                        sessionUuid, topFrame.skillName());
                        }
                        if (!unresolved.isEmpty()) {
                                log.warn("Skill frame has unresolved hard tools [session={}, skill={}, unresolved={}]",
                                        sessionUuid, topFrame.skillName(), unresolved);
                        }
                        log.debug("Skill frame tool filtering [session={}, allowed={}, resolved={}]",
                                sessionUuid, skillToolNames.size(), frameTools.size());
                        return frameTools.toArray(ToolCallback[]::new);
                }

                String agentId = session.getActiveAgentTemplateId();
                if (agentId == null) {
                        return new ToolCallback[0];
                }

                AgentTemplate template = agentTemplateRepo.get(agentId);
                if (template == null) {
                        return new ToolCallback[0];
                }

                List<String> toolNames = new ArrayList<>(
                        template.allowedTools() != null ? template.allowedTools() : List.of());

                // Also include session-level tool IDs pinned by the user for this session
                List<String> sessionToolIds = session.sessionToolIds();
                if (sessionToolIds != null && !sessionToolIds.isEmpty()) {
                        for (String id : sessionToolIds) {
                                if (!toolNames.contains(id)) toolNames.add(id);
                        }
                }

                if (toolNames.isEmpty()) {
                        log.debug("Agent has no allowedTools assigned — exposing no tools [agent={}]", agentId);
                        return new ToolCallback[0];
                }

                toolNames = expandWithToolDependencies(toolNames);

                ToolCallback[] result = toolNames.stream()
                        .map(securedToolCallbackMap::get)
                        .filter(Objects::nonNull)
                        .toArray(ToolCallback[]::new);

                log.debug("Tool filtering active [agent={}, allowedTools={}, resolved={}]",
                        agentId, template.allowedTools().size(), result.length);

                return result.length > 0 ? result : new ToolCallback[0];
        }

        /**
         * Returns {@code true} when the current session's active agent template is named
         * "Concierge" (case-insensitive).  Used to gate injection of the
         * {@code listAgentTemplates} hidden tool.
         */
        private boolean isConciergeSession() {
                String sessionUuid = ToolExecutionContext.getSessionUuid();
                if (sessionUuid == null) return false;
                AiSession session = sessionRepo.get(sessionUuid);
                if (session == null) return false;
                String agentId = session.getActiveAgentTemplateId();
                if (agentId == null) return false;
                AgentTemplate template = agentTemplateRepo.get(agentId);
                return template != null && "Concierge".equalsIgnoreCase(template.name());
        }

        /**
         * Returns the names of tools in {@code toolNames} that are not present in the
         * secured tool callback map.  {@code completeSkillExecution} is excluded because
         * it is a legacy tool that is no longer injected at runtime; its presence in
         * an allowedTools list should not be treated as an error.
         */
        public List<String> findUnresolvableTools(List<String> toolNames) {
                if (toolNames == null || toolNames.isEmpty()) return List.of();
                return expandWithToolDependencies(toolNames).stream()
                        .filter(name -> !name.equals("completeSkillExecution"))
                        .filter(name -> !securedToolCallbackMap.containsKey(name))
                        .toList();
        }

        /**
         * Resolves the set of tool names visible to the current parent-agent turn
         * for a given session. This includes filtered hard tools plus skill-wrapper
         * tools injected from template/session skill assignments.
         */
        public Set<String> resolveVisibleToolNamesForSession(String sessionUuid) {
                if (sessionUuid == null || sessionUuid.isBlank()) {
                        return Set.of();
                }
                if (sessionRepo == null) {
                        return Set.of();
                }

                AiSession session = sessionRepo.get(sessionUuid);
                if (session == null) {
                        return Set.of();
                }

                // Skill frames are handled by the skill sub-loop; parent-history filtering
                // only needs the parent turn tool visibility.
                if (session.skillStack() != null && !session.skillStack().isEmpty()) {
                        return Set.of();
                }

                Set<String> names = new LinkedHashSet<>();

                // Hard tools from active template + session pinned tools
                if (agentTemplateRepo != null) {
                        String agentId = session.getActiveAgentTemplateId();
                        AgentTemplate template = agentId != null ? agentTemplateRepo.get(agentId) : null;
                        if (template != null) {
                                List<String> hard = new ArrayList<>(template.allowedTools() != null
                                        ? template.allowedTools() : List.of());
                                if (session.sessionToolIds() != null && !session.sessionToolIds().isEmpty()) {
                                        for (String id : session.sessionToolIds()) {
                                                if (!hard.contains(id)) hard.add(id);
                                        }
                                }
                                for (String t : expandWithToolDependencies(hard)) {
                                        if (securedToolCallbackMap != null && securedToolCallbackMap.containsKey(t)) {
                                                names.add(t);
                                        }
                                }

                                // Skill wrappers assigned at template level
                                if (skillRepo != null && template.skillUuids() != null) {
                                        for (String skillUuid : template.skillUuids()) {
                                                sh.vork.skill.Skill s = skillRepo.get(skillUuid);
                                                if (s != null && s.toolName() != null && !s.toolName().isBlank()) {
                                                        names.add(s.toolName());
                                                }
                                        }
                                }
                        }
                }

                // Session-level skill wrappers
                if (skillRepo != null && session.sessionSkillUuids() != null) {
                        for (String skillUuid : session.sessionSkillUuids()) {
                                sh.vork.skill.Skill s = skillRepo.get(skillUuid);
                                if (s != null && s.toolName() != null && !s.toolName().isBlank()) {
                                        names.add(s.toolName());
                                }
                        }
                }

                // Session-scoped completeBackgroundTask is attached for background loops.
                if (session.originMode() == SessionOriginMode.BACKGROUND) {
                        names.add("completeBackgroundTask");
                }

                // think is always available.
                names.add("think");
                names.add("memory");
                names.add("getDateTime");

                return Set.copyOf(names);
        }

        private void appendEnvironmentVariables(StringBuilder prompt, String sessionUuid) {
                Map<String, String> envMap = sessionEnvironmentService.getEnv(sessionUuid);
                if (envMap == null || envMap.isEmpty()) {
                        return;
                }

                StringBuilder envBlock = new StringBuilder("\n### ACTIVE SESSION ENVIRONMENT VARIABLES\n");
                new java.util.TreeMap<>(envMap)
                        .forEach((k, v) -> envBlock.append(k).append("=").append(v).append("\n"));
                prompt.append(envBlock);

                String expectedOutput = envMap.get("JOB_EXPECTED_OUTPUT");
                if (expectedOutput != null && !expectedOutput.isBlank()) {
                        prompt.append("\n### HARD REQUIREMENT — EXPECTED OUTPUT\n")
                                .append(expectedOutput).append("\n")
                                .append("You MUST produce this output before invoking completeBackgroundTask. ")
                                .append("Your report field MUST explicitly confirm whether this requirement was met.\n");
                }
        }

        private void appendAvailableToolsSection(StringBuilder prompt) {
                Object toolsObj = ToolExecutionContext.get(AVAILABLE_TOOL_DETAILS_CONTEXT_KEY);
                if (!(toolsObj instanceof List<?> rawList) || rawList.isEmpty()) {
                        return;
                }

                prompt.append("\n\n### TOOL ACCESS POLICY\n");
                prompt.append("AI may only execute tools it has access to which are listed below. ");
                prompt.append("Skills are tools. ONLY use these tools:\n\n");
                for (Object raw : rawList) {
                        if (!(raw instanceof Map<?, ?> item)) {
                                continue;
                        }
                        String name = String.valueOf(item.get("name"));
                        if (name == null || name.isBlank()) {
                                continue;
                        }

                        prompt.append("- `").append(name).append("`");

                        Object descObj = item.get("description");
                        String description = descObj == null ? "" : String.valueOf(descObj).trim();
                        if (!description.isBlank()) {
                                prompt.append("\n  Description: ").append(description);
                        }

                        Object requiredObj = item.get("requiredArgs");
                        if (requiredObj instanceof List<?> requiredArgs && !requiredArgs.isEmpty()) {
                                prompt.append("\n  Required args: ")
                                        .append(requiredArgs.stream().map(String::valueOf).collect(Collectors.joining(", ")));
                        }

                        Object optionalObj = item.get("optionalArgs");
                        if (optionalObj instanceof List<?> optionalArgs && !optionalArgs.isEmpty()) {
                                prompt.append("\n  Optional args: ")
                                        .append(optionalArgs.stream().map(String::valueOf).collect(Collectors.joining(", ")));
                        }

                        prompt.append("\n");
                }
        }

        private List<Map<String, Object>> buildToolDetails(List<ToolCallback> tools) {
                List<Map<String, Object>> details = new ArrayList<>();
                for (ToolCallback callback : tools) {
                        var definition = callback.getToolDefinition();
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("name", definition.name());
                        detail.put("description", definition.description());

                        List<String> requiredArgs = new ArrayList<>();
                        List<String> optionalArgs = new ArrayList<>();
                        parseToolInputSchema(definition.inputSchema(), requiredArgs, optionalArgs);
                        detail.put("requiredArgs", requiredArgs);
                        detail.put("optionalArgs", optionalArgs);

                        details.add(detail);
                }
                return details;
        }

        private void parseToolInputSchema(String schema, List<String> requiredArgs, List<String> optionalArgs) {
                if (schema == null || schema.isBlank()) {
                        return;
                }
                if (objectMapper == null) {
                        return;
                }
                try {
                        JsonNode root = objectMapper.readTree(schema);
                        JsonNode properties = root.get("properties");
                        if (properties == null || !properties.isObject()) {
                                return;
                        }

                        LinkedHashSet<String> required = new LinkedHashSet<>();
                        JsonNode requiredNode = root.get("required");
                        if (requiredNode != null && requiredNode.isArray()) {
                                requiredNode.forEach(node -> {
                                        if (node.isTextual()) {
                                                required.add(node.asText());
                                        }
                                });
                        }

                        var names = properties.fieldNames();
                        while (names.hasNext()) {
                                String name = names.next();
                                if (required.contains(name)) {
                                        requiredArgs.add(name);
                                } else {
                                        optionalArgs.add(name);
                                }
                        }
                } catch (Exception ignored) {
                        // Best-effort metadata extraction for prompt guidance only.
                }
        }

        private List<String> expandWithToolDependencies(List<String> toolNames) {
                if (toolNames == null || toolNames.isEmpty()) {
                        return List.of();
                }

                LinkedHashSet<String> expanded = new LinkedHashSet<>();
                ArrayList<String> queue = new ArrayList<>();
                for (String toolName : toolNames) {
                        if (toolName != null && !toolName.isBlank()) {
                                queue.add(toolName);
                        }
                }

                for (int i = 0; i < queue.size(); i++) {
                        String current = queue.get(i);
                        if (!expanded.add(current)) {
                                continue;
                        }

                        for (String dep : getDeclaredToolDependencies(current)) {
                                if (dep != null && !dep.isBlank() && !expanded.contains(dep)) {
                                        queue.add(dep.trim());
                                }
                        }
                }

                return List.copyOf(expanded);
        }

        private List<String> getDeclaredToolDependencies(String toolName) {
                if (beanFactory == null) {
                        return List.of();
                }
                if (!beanFactory.containsBeanDefinition(toolName)) {
                        return List.of();
                }

                BeanDefinition bd = beanFactory.getBeanDefinition(toolName);
                String factoryBeanName = bd.getFactoryBeanName();
                String factoryMethodName = bd.getFactoryMethodName();
                if (factoryBeanName == null || factoryMethodName == null) {
                        return List.of();
                }

                try {
                        Object factoryBean = beanFactory.getBean(factoryBeanName);
                        Class<?> targetClass = ClassUtils.getUserClass(factoryBean);
                        for (Method method : targetClass.getDeclaredMethods()) {
                                if (!method.getName().equals(factoryMethodName)) {
                                        continue;
                                }
                                ToolDepends depends = method.getAnnotation(ToolDepends.class);
                                if (depends == null || depends.value().length == 0) {
                                        return List.of();
                                }
                                List<String> deps = new ArrayList<>();
                                for (String dep : depends.value()) {
                                        if (dep != null && !dep.isBlank()) {
                                                deps.add(dep.trim());
                                        }
                                }
                                return deps;
                        }
                } catch (Exception ignored) {
                        // Dependency metadata failure must not break runtime tool resolution.
                }

                return List.of();
        }

}

