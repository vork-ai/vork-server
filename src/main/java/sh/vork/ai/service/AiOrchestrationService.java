package sh.vork.ai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.springframework.stereotype.Service;

import sh.vork.ai.AiProvider;
import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.config.AiConfig;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.memory.SessionEnvironmentService;
import sh.vork.ai.provider.AiChatClientFactory;
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

        private static final String STRUCTURED_RESPONSE_MANDATE = """

            ### CORE OUTPUT REQUIREMENT
            You MUST return your output as a single valid JSON object matching the StructuredAgentResponse schema.
            No markdown fences, no explanation outside the JSON. Your entire response must be parsable JSON:
            {
              "status": "FINISHED_TURN | DELEGATE_TURN | CONTINUE_TURN | SWITCH_AGENT",
              "textResponse": "<your human-readable message to the user or supervisor>",
              "targetAgent": "<exact agent display name, or null>",
              "delegationInstructions": "<full self-contained task for the sub-agent, or null>"
            }
            1. If your goal is completed or you are returning a result to a supervisor, set status to "FINISHED_TURN".
            2. If you need to delegate a job to a specialized expert agent, set status to "DELEGATE_TURN",
               populate "targetAgent" with their exact display name, and write explicit, comprehensive
               task parameters inside "delegationInstructions".
            3. If you have made meaningful progress and want to inform the user before continuing execution,
               set status to "CONTINUE_TURN". Your textResponse will be shown to the user immediately and
               you will be invoked again automatically — do NOT stop and wait for a user reply.
            4. If the user explicitly asks to switch to a different agent, set status to "SWITCH_AGENT",
               set "targetAgent" to the exact display name of the desired agent, and write a brief
               confirmation message in "textResponse". The session active agent will be updated and the
               user will see a confirmation — you do NOT need to do any work for the new agent.
            """.stripIndent();

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
        private final ToolCallback listSkillsCallback;
        private final ToolCallback executeSkillCallback;
        private final ToolCallback listAvailableToolsCallback;
        private final ToolCallback listAgentTemplatesCallback;

        public AiOrchestrationService(Map<AiProvider, ChatClient> chatClientRegistry,
                                                                  AiChatClientFactory chatClientFactory,
                                                                  SessionEnvironmentService sessionEnvironmentService,
                                                                  DatabaseRepository<AiSession> aiSessionRepository,
                                                                  DatabaseRepository<AgentTemplate> agentTemplateRepository,
                                                                  DatabaseRepository<sh.vork.skill.Skill> skillRepository,
                                                                  Map<String, ToolCallback> securedToolCallbackMap,
                                                                  SessionToolStore sessionToolStore,
                                                                  @org.springframework.beans.factory.annotation.Qualifier("listSkills") ToolCallback listSkillsCallback,
                                                                  @org.springframework.beans.factory.annotation.Qualifier("executeSkill") ToolCallback executeSkillCallback,
                                                                  @org.springframework.beans.factory.annotation.Qualifier("listAvailableTools") ToolCallback listAvailableToolsCallback,
                                                                  @org.springframework.beans.factory.annotation.Qualifier("listAgentTemplates") ToolCallback listAgentTemplatesCallback) {
                this.registry = chatClientRegistry;
                this.chatClientFactory = chatClientFactory;
                this.sessionEnvironmentService = sessionEnvironmentService;
                this.sessionRepo = aiSessionRepository;
                this.agentTemplateRepo = agentTemplateRepository;
                this.skillRepo = skillRepository;
                this.securedToolCallbackMap = securedToolCallbackMap;
                this.sessionToolStore = sessionToolStore;
                this.listSkillsCallback = listSkillsCallback;
                this.executeSkillCallback = executeSkillCallback;
                this.listAvailableToolsCallback = listAvailableToolsCallback;
                this.listAgentTemplatesCallback = listAgentTemplatesCallback;
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
                + "1. You MUST complete this skill by calling the completeSkillExecution tool. "
                +    "Responding with plain text alone does NOT complete the skill — the tool call is required.\n"
                + "2. If the user message contains a REQUIRED OUTPUT FORMAT section, the 'output' argument "
                +    "you pass to completeSkillExecution MUST follow that template exactly — same structure, "
                +    "same fields, no omissions and no additions.\n"
                + "3. Do not end a turn without either using an available tool or calling completeSkillExecution. "
                +    "Use your tools to gather whatever information you need, then call completeSkillExecution.";

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
                                if (skillPrompt != null && !skillPrompt.isBlank()) {
                                        prompt.append("\n\n### SKILL DIRECTIVES\n").append(skillPrompt);
                                }
                                prompt.append("\n\n").append(SKILL_OPERATIONAL_PROTOCOL);
                                return logAndReturn(prompt, sessionUuid, "SKILL"); // skip env-var block and structured mandate
                        }

                        String agentId = session.getActiveAgentTemplateId();
                        if (agentId != null) {
                                AgentTemplate template = agentTemplateRepo.get(agentId);
                                if (template != null && !template.systemPrompt().isBlank()) {
                                        prompt.append("\n\n### ACTIVE AGENT DIRECTIVES\n").append(template.systemPrompt());
                                }

                                // Inject available skills only when explicitly assigned
                                if (template != null
                                        && template.skillUuids() != null
                                        && !template.skillUuids().isEmpty()) {
                                        appendAvailableSkillsSection(prompt, template.skillUuids());
                                }
                        }
                }

                // Inject session environment variables
                Map<String, String> envMap = sessionEnvironmentService.getEnv(sessionUuid);
                if (envMap != null && !envMap.isEmpty()) {
                        StringBuilder envBlock = new StringBuilder("\n### ACTIVE SESSION ENVIRONMENT VARIABLES\n");
                        envMap.forEach((k, v) -> envBlock.append(k).append("=").append(v).append("\n"));
                        prompt.append(envBlock);

                        // Append expected output as a hard protocol rule if defined for this job
                        String expectedOutput = envMap.get("JOB_EXPECTED_OUTPUT");
                        if (expectedOutput != null && !expectedOutput.isBlank()) {
                                prompt.append("\n### HARD REQUIREMENT — EXPECTED OUTPUT\n")
                                        .append(expectedOutput).append("\n")
                                        .append("You MUST produce this output before invoking completeBackgroundTask. ")
                                        .append("Your report field MUST explicitly confirm whether this requirement was met.\n");
                        }
                }

                // Mandate structured output for interactive (non-background, non-skill) sessions
                if (session != null && session.originMode() != SessionOriginMode.BACKGROUND
                        && (session.skillStack() == null || session.skillStack().isEmpty())) {
                        prompt.append(STRUCTURED_RESPONSE_MANDATE);
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
         * Appends a "AVAILABLE SKILLS" section to {@code prompt} listing each skill's
         * name, parameters, and output template so the agent knows exactly how to call
         * {@code executeSkill}.
         */
        private void appendAvailableSkillsSection(StringBuilder prompt, List<String> skillUuids) {
                List<sh.vork.skill.Skill> skills = skillUuids.stream()
                        .map(skillRepo::get)
                        .filter(Objects::nonNull)
                        .toList();
                if (skills.isEmpty()) return;
                appendSkillsToPrompt(prompt, skills);
        }

        private void appendSkillsToPrompt(StringBuilder prompt, List<sh.vork.skill.Skill> skills) {
                prompt.append("\n\n### AVAILABLE SKILLS\n");
                prompt.append("Use the `executeSkill` tool to invoke any skill listed below. ");
                prompt.append("Supply ALL declared parameters — if a value is missing, ");
                prompt.append("ask the user before calling the tool.\n\n");

                for (sh.vork.skill.Skill skill : skills) {
                        prompt.append("**").append(skill.name()).append("**")
                              .append(" (skillUuid: `").append(skill.uuid()).append("`)\n");
                        if (!skill.description().isBlank()) {
                                prompt.append("  Description: ").append(skill.description()).append("\n");
                        }
                        if (!skill.parameters().isEmpty()) {
                                prompt.append("  Parameters:\n");
                                for (sh.vork.skill.SkillParameter p : skill.parameters()) {
                                        prompt.append("    - `").append(p.name())
                                              .append("` (").append(p.type()).append(")");
                                        if (!p.description().isBlank()) {
                                                prompt.append(": ").append(p.description());
                                        }
                                        prompt.append("\n");
                                }
                        } else {
                                prompt.append("  Parameters: none\n");
                        }
                        if (!skill.outputTemplate().isBlank()) {
                                prompt.append("  Output Template:\n");
                                for (String line : skill.outputTemplate().split("\n", -1)) {
                                        prompt.append("    ").append(line).append("\n");
                                }
                        }
                        prompt.append("\n");
                }
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
                ToolCallback[] filtered = resolveFilteredToolCallbacks();
                ToolCallback[] tools = (filtered != null)
                        ? filtered
                        : securedToolCallbackMap.values().toArray(ToolCallback[]::new);

                // Merge session-scoped tools (e.g. completeBackgroundTask for background sessions).
                // These are hidden from the global registry and injected programmatically per session.
                String sessionUuid = ToolExecutionContext.getSessionUuid();
                List<ToolCallback> sessionTools = sessionToolStore.getTools(sessionUuid);
                List<ToolCallback> merged = new ArrayList<>(List.of(tools));

                // Track names already present so we never register the same tool name twice.
                // executeSkill is not @Hidden, so it may already be in the secured map when no
                // agent filter is active — the guard prevents the duplicate that crashes Spring AI.
                java.util.Set<String> presentNames = merged.stream()
                        .map(t -> t.getToolDefinition().name())
                        .collect(Collectors.toCollection(java.util.HashSet::new));

                // Always inject always-on tools by direct bean reference so they are available
                // regardless of allowedTools configuration.
                if (presentNames.add("listSkills"))          merged.add(listSkillsCallback);
                if (presentNames.add("executeSkill"))        merged.add(executeSkillCallback);
                if (presentNames.add("listAvailableTools"))  merged.add(listAvailableToolsCallback);
                if (isConciergeSession() && presentNames.add("listAgentTemplates"))
                        merged.add(listAgentTemplatesCallback);
                if (!sessionTools.isEmpty()) {
                        merged.addAll(sessionTools);
                        log.debug("Merged {} session-scoped tool(s) [session={}]", sessionTools.size(), sessionUuid);
                }
                tools = merged.toArray(ToolCallback[]::new);

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
                        return null;
                }

                AiSession session = sessionRepo.get(sessionUuid);
                if (session == null) {
                        return null;
                }

                // Sessions with an active skill frame: tool set is bounded by the top frame's allowedTools.
                // An empty allowedTools list means only completeSkillExecution (never fall through to all-tools).
                if (session.skillStack() != null && !session.skillStack().isEmpty()) {
                        sh.vork.skill.SkillFrame topFrame = session.skillStack().getLast();
                        List<String> allowedToolNames = topFrame.allowedTools();
                        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
                                log.debug("Skill frame has no allowedTools — exposing only completeSkillExecution [session={}]", sessionUuid);
                                ToolCallback completeSkillTool = securedToolCallbackMap.get("completeSkillExecution");
                                return completeSkillTool != null ? new ToolCallback[]{completeSkillTool} : new ToolCallback[0];
                        }
                        // Always include completeSkillExecution so the skill can terminate
                        List<String> skillToolNames = new ArrayList<>(allowedToolNames);
                        if (!skillToolNames.contains("completeSkillExecution")) {
                                skillToolNames.add("completeSkillExecution");
                        }
                        ToolCallback[] result = skillToolNames.stream()
                                .map(securedToolCallbackMap::get)
                                .filter(Objects::nonNull)
                                .toArray(ToolCallback[]::new);
                        log.debug("Skill frame tool filtering [session={}, allowed={}, resolved={}]",
                                sessionUuid, skillToolNames.size(), result.length);
                        return result;
                }

                String agentId = session.getActiveAgentTemplateId();
                if (agentId == null) {
                        return null;
                }

                AgentTemplate template = agentTemplateRepo.get(agentId);
                if (template == null) {
                        return new ToolCallback[0];
                }

                List<String> toolNames = new ArrayList<>(
                        template.allowedTools() != null ? template.allowedTools() : List.of());

                // Auto-inject executeSkill when the agent has skills assigned —
                // NOTE: executeSkill is also injected directly in buildMutatedClientInternal
                // via bean reference. The toolNames injection here keeps it in the filtered
                // list for agents that explicitly include it in allowedTools.
                if (template.skillUuids() != null && !template.skillUuids().isEmpty()) {
                        if (!toolNames.contains("executeSkill")) {
                                toolNames.add("executeSkill");
                        }
                }

                if (toolNames.isEmpty()) {
                        log.debug("Agent has no allowedTools assigned — exposing no tools [agent={}]", agentId);
                        return new ToolCallback[0];
                }

                ToolCallback[] result = toolNames.stream()
                        .map(securedToolCallbackMap::get)
                        .filter(Objects::nonNull)
                        .toArray(ToolCallback[]::new);

                log.debug("Tool filtering active [agent={}, allowedTools={}, resolved={}]",
                        agentId, template.allowedTools().size(), result.length);

                return result.length > 0 ? result : null;
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

}

