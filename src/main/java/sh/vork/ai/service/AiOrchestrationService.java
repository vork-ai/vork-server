package sh.vork.ai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import com.jadaptive.orm.DatabaseRepository;

import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.config.AiConfig;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.AiProvider;
import sh.vork.ai.memory.SessionEnvironmentService;

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
BACKGROUND OPERATIONAL PROTOCOL: You are executing autonomously in an isolated background thread. You must perform all necessary analysis and tool calls across multiple message rounds without expecting further human input. Once you have validated that the requested objective is entirely satisfied (e.g., your types compile successfully and records are saved), you MUST invoke the completeBackgroundTask tool to cleanly finalize the run. Do not exit without invoking this tool.
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

        private final Map<AiProvider, ChatClient> registry;
        private final SessionEnvironmentService sessionEnvironmentService;
        private final DatabaseRepository<AiSession> sessionRepo;
        private final DatabaseRepository<AgentTemplate> agentTemplateRepo;
        private final Map<String, ToolCallback> securedToolCallbackMap;

        public AiOrchestrationService(Map<AiProvider, ChatClient> chatClientRegistry,
                                                                  SessionEnvironmentService sessionEnvironmentService,
                                                                  DatabaseRepository<AiSession> aiSessionRepository,
                                                                  DatabaseRepository<AgentTemplate> agentTemplateRepository,
                                                                  Map<String, ToolCallback> securedToolCallbackMap) {
                this.registry = chatClientRegistry;
                this.sessionEnvironmentService = sessionEnvironmentService;
                this.sessionRepo = aiSessionRepository;
                this.agentTemplateRepo = agentTemplateRepository;
                this.securedToolCallbackMap = securedToolCallbackMap;
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
        ChatClient base = registry.get(provider);
        if (base == null) {
            throw new IllegalArgumentException(
                    "No ChatClient configured for provider: " + provider
                    + ". Add a ChatClient @Bean and an entry in AiConfig.chatClientRegistry().");
        }

        // mutate() seeds a fresh builder from the shared client's config so
        // per-request changes (e.g. additional tools, system prompt override)
        // never bleed into other concurrent calls.
        log.info("Generating response [provider={}] prompt=\"{}\"...",
                provider, userPrompt.length() > 120 ? userPrompt.substring(0, 120) + "…" : userPrompt);

        String response = buildMutatedClient(base)
                .build()
                .prompt()
                .user(withBackgroundDirective(userPrompt, provider))
                .call()
                .content();

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
        ChatClient base = registry.get(provider);
        if (base == null) {
            throw new IllegalArgumentException(
                    "No ChatClient configured for provider: " + provider
                    + ". Add a ChatClient @Bean and an entry in AiConfig.chatClientRegistry().");
        }

        log.info("Generating chat response [provider={}, history={} msgs]...", provider, conversationHistory.size());

        String response = buildMutatedClient(base)
                .build()
                .prompt()
                .messages(conversationHistory.toArray(Message[]::new))
                .user(withBackgroundDirective(newUserMessage, provider))
                .call()
                .content();

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
        ChatClient base = registry.get(provider);
        if (base == null) {
            throw new IllegalArgumentException(
                    "No ChatClient configured for provider: " + provider
                    + ". Add a ChatClient @Bean and an entry in AiConfig.chatClientRegistry().");
        }

        log.info("Generating chat response with media [provider={}, history={} msgs, media={}]",
                provider, conversationHistory.size(), media.size());

        List<Message> allMessages = new ArrayList<>(conversationHistory);
        String effectiveText = (userText == null || userText.isBlank()) ? "Please analyse the attached file(s)." : userText;
        effectiveText = withBackgroundDirective(effectiveText, provider);
        allMessages.add(UserMessage.builder().text(effectiveText).media(media).build());

        String response = buildMutatedClient(base)
                .build()
                .prompt()
                .messages(allMessages.toArray(Message[]::new))
                .call()
                .content();

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

        private String composeSystemPrompt() {
                String sessionUuid = ToolExecutionContext.getSessionUuid();
                StringBuilder prompt = new StringBuilder(AiConfig.BASE_SYSTEM_PROMPT);

                if (sessionUuid == null || sessionUuid.isBlank()) {
                        return prompt.toString();
                }

                // Inject active agent template directives
                AiSession session = sessionRepo.get(sessionUuid);
                if (session != null) {
                        String agentId = session.getActiveAgentTemplateId();
                        if (agentId != null) {
                                AgentTemplate template = agentTemplateRepo.get(agentId);
                                if (template != null && !template.systemPrompt().isBlank()) {
                                        prompt.append("\n\n### ACTIVE AGENT DIRECTIVES\n").append(template.systemPrompt());
                                }
                        }
                }

                // Inject session environment variables
                Map<String, String> envMap = sessionEnvironmentService.getEnv(sessionUuid);
                if (envMap != null && !envMap.isEmpty()) {
                        StringBuilder envBlock = new StringBuilder("\n### ACTIVE SESSION ENVIRONMENT VARIABLES\n");
                        envMap.forEach((k, v) -> envBlock.append(k).append("=").append(v).append("\n"));
                        prompt.append(envBlock);
                }

                // Mandate structured output for interactive (non-background) sessions
                if (session == null || session.originMode() != SessionOriginMode.BACKGROUND) {
                        prompt.append(STRUCTURED_RESPONSE_MANDATE);
                }

                return prompt.toString();
        }

        /**
         * Builds a mutated {@link ChatClient.Builder} from the shared base client with the
         * composed system prompt and, when an active {@link AgentTemplate} restricts the
         * allowed tools, the filtered tool set applied.
         */
        private ChatClient.Builder buildMutatedClient(ChatClient base) {
                // Resolve which tools to expose for this request: filtered subset when an
                // AgentTemplate is active, or the full secured set otherwise.
                // Tools are always set here (never on the base ChatClient) to prevent
                // Spring AI from seeing duplicates when the builder is mutated.
                ToolCallback[] filtered = resolveFilteredToolCallbacks();
                ToolCallback[] tools = (filtered != null)
                        ? filtered
                        : securedToolCallbackMap.values().toArray(ToolCallback[]::new);

                return base.mutate()
                        .defaultSystem(composeSystemPrompt())
                        .defaultToolCallbacks(tools);
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

                String agentId = session.getActiveAgentTemplateId();
                if (agentId == null) {
                        return null;
                }

                AgentTemplate template = agentTemplateRepo.get(agentId);
                if (template == null || template.allowedTools() == null || template.allowedTools().isEmpty()) {
                        return null;
                }

                ToolCallback[] result = template.allowedTools().stream()
                        .map(securedToolCallbackMap::get)
                        .filter(Objects::nonNull)
                        .toArray(ToolCallback[]::new);

                log.debug("Tool filtering active [agent={}, allowedTools={}, resolved={}]",
                        agentId, template.allowedTools().size(), result.length);

                return result.length > 0 ? result : null;
        }

}
