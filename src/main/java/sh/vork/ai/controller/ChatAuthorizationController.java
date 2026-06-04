package sh.vork.ai.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.protocol.StructuredAgentResponse;
import sh.vork.ai.protocol.interaction.FieldSource;
import sh.vork.ai.protocol.interaction.FormAction;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.security.AuthorizationRuleEngine;
import sh.vork.ai.security.VisualizableTool;
import sh.vork.ai.service.AiOrchestrationService;
import sh.vork.ai.service.ChatService;
import sh.vork.ai.memory.SessionEnvironmentService;
import sh.vork.scheduling.service.AiSchedulerService;
import com.jadaptive.orm.DatabaseRepository;
import sh.vork.scheduling.service.SystemBackgroundAuthentication;

import java.util.concurrent.Executor;

import sh.vork.security.SecureCredentialStore;
import sh.vork.security.VorkUser;
import sh.vork.storage.FileStorageService;

/**
 * Accepts structured user responses to PROMPT_REQUIRED frames and resumes chat execution.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatAuthorizationController {

    private static final Logger log = LoggerFactory.getLogger(ChatAuthorizationController.class);
    private static final int TERMINAL_HISTORY_OUTPUT_MAX_CHARS = 24_000;

    private final DatabaseRepository<AiSession> sessionRepo;
    private final AuthorizationRuleEngine authorizationRuleEngine;
    private final AiOrchestrationService aiService;
    private final SimpMessagingTemplate messaging;
    private final ObjectMapper objectMapper;
    private final Map<String, ToolCallback> toolCallbacksByName;
    private final Executor aiBackgroundExecutor;
    private final AiSchedulerService aiSchedulerService;
    private final FileStorageService fileStorageService;
    private final ChatService chatService;
    private final SecureCredentialStore secureCredentialStore;
    private final SessionEnvironmentService sessionEnvironmentService;

    @Autowired
    public ChatAuthorizationController(DatabaseRepository<AiSession> sessionRepo,
                                       AuthorizationRuleEngine authorizationRuleEngine,
                                       AiOrchestrationService aiService,
                                       SimpMessagingTemplate messaging,
                                       ObjectMapper objectMapper,
                                       List<ToolCallback> toolCallbacks,
                                       @Qualifier("aiBackgroundExecutor") Executor aiBackgroundExecutor,
                                       AiSchedulerService aiSchedulerService,
                                       FileStorageService fileStorageService,
                                       ChatService chatService,
                                       SecureCredentialStore secureCredentialStore,
                                       SessionEnvironmentService sessionEnvironmentService) {
        this.sessionRepo = sessionRepo;
        this.authorizationRuleEngine = authorizationRuleEngine;
        this.aiService = aiService;
        this.messaging = messaging;
        this.fileStorageService = fileStorageService;
        this.objectMapper = objectMapper;
        this.aiBackgroundExecutor = aiBackgroundExecutor;
        this.aiSchedulerService = aiSchedulerService;
        this.chatService = chatService;
        this.secureCredentialStore = secureCredentialStore;
        this.sessionEnvironmentService = sessionEnvironmentService;
        this.toolCallbacksByName = toolCallbacks.stream().collect(
                java.util.stream.Collectors.toMap(
                        t -> t.getToolDefinition().name(),
                        Function.identity(),
                        (a, b) -> a));
    }

    @PostMapping("/respond/{sessionUuid}")
    public ResponseEntity<Map<String, Object>> respond(@PathVariable String sessionUuid,
                                                       @RequestBody InteractionResponse request) {
        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null) {
            throw new IllegalStateException("AI session not found: " + sessionUuid);
        }
        if (session.status() != AiSessionStatus.AWAITING_INPUT) {
            throw new IllegalStateException("Session is not awaiting input: " + sessionUuid);
        }

            ToolExecutionContext.bindSessionUuid(sessionUuid);
            ToolExecutionContext.hydrate(session.environmentVariables());

        AiChatMessage promptMessage = findPromptMessage(session.messages(), request.eventId());
        UiEventFrame promptEvent = readEventFrame(promptMessage.content());
        String correlationEventId = (request.eventId() == null || request.eventId().isBlank())
            ? promptEvent.eventId()
            : request.eventId();

        try (MDC.MDCCloseable _ = MDC.putCloseable("sessionUuid", sessionUuid);
             MDC.MDCCloseable _ = MDC.putCloseable("eventId", correlationEventId)) {
            String action = normalizeAction(request.action());
            String username = resolveUsername();

            String toolName = promptMessage.toolName();
            String toolCallId = promptMessage.toolCallId();
            String argumentsJson = extractPendingArguments(promptMessage, toolCallId);
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalStateException("Missing pending tool name in prompt message");
            }
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalStateException("Missing pending tool call ID in prompt message");
            }

            Map<String, String> incomingFields = request.fields() == null ? Map.of() : request.fields();
            Map<String, FieldSource> sourceByField = buildFieldSourceMap(promptEvent.formSchema());
            Map<String, String> conversationFields = new HashMap<>();
            for (Map.Entry<String, String> entry : incomingFields.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                FieldSource source = sourceByField.getOrDefault(key, FieldSource.CONVERSATION);
                switch (source) {
                    case SECRET -> {
                        if (secureCredentialStore != null) {
                            secureCredentialStore.saveSecret(toPrincipalUser(username), key, value);
                        }
                    }
                    case CONTEXT -> {
                        sessionEnvironmentService.setEnv(sessionUuid, key, value);
                        ToolExecutionContext.put(key, value);
                    }
                    case CONVERSATION -> conversationFields.put(key, value);
                }
            }

            log.info("Resume request received [action={}, tool={}, toolCallId={}, user={}, fieldKeys={}]",
                action,
                toolName,
                toolCallId,
                username,
                incomingFields.keySet());
            log.debug("Resume request conversation fields payload: {}", abbreviate(toJson(conversationFields), 2000));

            if ("AUTHORIZE_TOOL".equalsIgnoreCase(request.intent())) {
                applyAuthorizationAction(action, username, toolName, toolCallId);
            }

                String token;
                try {
                token = toolResponseDataForAction(action, conversationFields, toolName, argumentsJson);
                } catch (ToolSuspensionException ex) {
                String suspendedToolCallId = (toolCallId == null || toolCallId.isBlank())
                    ? "pending-" + UUID.randomUUID()
                    : toolCallId;
                String suspendedArguments = resolveSuspendedArguments(argumentsJson, ex.getArguments());
                String justification = (ex.getJustification() == null || ex.getJustification().isBlank())
                    ? defaultAuthorizationReason(ex.getToolName())
                    : ex.getJustification();

                UiEventFrame suspendedPromptEvent = new UiEventFrame(
                    UUID.randomUUID().toString(),
                    "PROMPT_REQUIRED",
                    "AUTHORIZE_TOOL",
                    justification,
                    ex.getFormSchema());

                List<AiChatMessage> suspendedMessages = new ArrayList<>(session.messages());
                suspendedMessages.add(new AiChatMessage(
                    UUID.randomUUID().toString(),
                    "PROMPT_REQUIRED",
                    toJson(suspendedPromptEvent),
                    System.currentTimeMillis(),
                    null,
                    List.of(new AiChatMessage.ToolCallRef(
                        suspendedToolCallId,
                        "FUNCTION",
                        ex.getToolName(),
                        suspendedArguments)),
                    suspendedToolCallId,
                    ex.getToolName()));

                SessionOriginMode originMode = session.originMode() == null ? SessionOriginMode.WEB : session.originMode();
                sessionRepo.save(new AiSession(
                    session.uuid(),
                    session.provider(),
                    originMode,
                    session.username(),
                    session.name(),
                    session.createdAt(),
                    session.currentRoundCount(),
                    List.copyOf(suspendedMessages),
                    session.environmentVariables(),
                    AiSessionStatus.AWAITING_INPUT,
                    session.agentTemplateStack()));

                messaging.convertAndSend("/topic/chat/" + sessionUuid, suspendedPromptEvent);
                log.info("Tool execution suspended for additional input [tool={}, session={}]",
                    ex.getToolName(), sessionUuid);

                return ResponseEntity.ok(Map.of(
                    "status", "AWAITING_INPUT",
                    "eventType", suspendedPromptEvent.type(),
                    "eventId", suspendedPromptEvent.eventId(),
                    "toolName", ex.getToolName()
                ));
                }
            ToolResponseMessage.ToolResponse toolResponse =
                new ToolResponseMessage.ToolResponse(toolCallId, toolName, token);
            ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                .responses(List.of(toolResponse))
                .metadata(Collections.emptyMap())
                .build();

            String toolPayloadJson = toJson(Map.of(
                "type", "TOOL_RESPONSE",
                "responses", List.of(Map.of(
                    "id", toolResponse.id(),
                    "name", toolResponse.name(),
                    "responseData", toolResponse.responseData())),
                "message", toolResponseMessage.toString(),
                "fields", conversationFields
            ));

            Map<String, Object> terminalTranscript = extractTerminalTranscript(toolName, toolResponse.responseData());
            List<AiChatMessage.AttachmentRef> attachments = null;
            
            if (terminalTranscript != null) {
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", "TOOL_RESPONSE");
                payload.put("responses", List.of(Map.of(
                        "id", toolResponse.id(),
                        "name", toolResponse.name(),
                        "responseData", toolResponse.responseData())));
                payload.put("message", toolResponseMessage.toString());
                payload.put("fields", conversationFields);
                payload.put("terminalTranscript", terminalTranscript);
                toolPayloadJson = toJson(payload);
                
                // Extract file attachment if present
                Object fileUuidObj = terminalTranscript.get("outputFileUuid");
                if (fileUuidObj != null && fileStorageService != null) {
                    String fileUuid = String.valueOf(fileUuidObj);
                    try {
                        sh.vork.storage.StoredFile storedFile = fileStorageService.getMetadata(fileUuid);
                        if (storedFile != null) {
                            attachments = List.of(
                                new AiChatMessage.AttachmentRef(
                                    storedFile.uuid(),
                                    storedFile.originalName(),
                                    storedFile.mimeType()
                                )
                            );
                            log.info("Terminal output file attached [fileUuid={}, fileName={}]", fileUuid, storedFile.originalName());
                        } else {
                            log.warn("Terminal output metadata not found for attachment [fileUuid={}]", fileUuid);
                        }
                    } catch (Exception ex) {
                        log.error("Failed to resolve terminal output attachment metadata [fileUuid={}]: {}", fileUuid, ex.getMessage(), ex);
                    }
                }
            }

            AiChatMessage toolMessage = new AiChatMessage(
                UUID.randomUUID().toString(),
                "TOOL",
                toolPayloadJson,
                System.currentTimeMillis(),
                attachments,
                null,
                toolCallId,
                toolName);

            List<AiChatMessage> updated = new ArrayList<>(session.messages());
            updated.add(toolMessage);

            log.info("Tool response persisted [tool={}, toolCallId={}, payloadSize={}]",
                toolName, toolCallId, toolPayloadJson.length());
            log.debug("Tool response payload: {}", abbreviate(toolPayloadJson, 4000));

            List<Message> history = hydrateHistory(updated);
            SessionOriginMode originMode = session.originMode() == null ? SessionOriginMode.WEB : session.originMode();

            if (originMode == SessionOriginMode.WEB) {
                messaging.convertAndSend("/topic/chat/" + sessionUuid, toolMessage);
                // Persist tool output immediately so a model continuation failure cannot drop this round.
                sessionRepo.save(new AiSession(
                        session.uuid(),
                        session.provider(),
                        originMode,
                        session.username(),
                        session.name(),
                        session.createdAt(),
                        session.currentRoundCount(),
                        List.copyOf(updated),
                        session.environmentVariables(),
                        AiSessionStatus.RUNNING,
                        session.agentTemplateStack()));
            }

            if (originMode == SessionOriginMode.BACKGROUND) {
                sessionRepo.save(new AiSession(
                        session.uuid(),
                        session.provider(),
                        originMode,
                        session.username(),
                        session.name(),
                        session.createdAt(),
                    session.currentRoundCount(),
                        List.copyOf(updated),
                    session.environmentVariables(),
                    AiSessionStatus.RUNNING,
                    session.agentTemplateStack()));

                aiBackgroundExecutor.execute(() -> {
                    ToolExecutionContext.bindSessionUuid(sessionUuid);
                    ToolExecutionContext.hydrate(session.environmentVariables());
                    try {
                        SecurityContextHolder.getContext()
                                .setAuthentication(new SystemBackgroundAuthentication(session.username()));
                        aiSchedulerService.resumeBackgroundSession(sessionUuid);
                    } catch (Exception ex) {
                        log.error("Background resume failed [session={}]: {}", sessionUuid, ex.getMessage(), ex);
                    } finally {
                        SecurityContextHolder.clearContext();
                        ToolExecutionContext.clear();
                    }
                });

                    ToolExecutionContext.clear();

                return ResponseEntity.ok(Map.of(
                        "status", "BACKGROUND_RESUMED",
                        "message", "The background task has resumed processing in an isolated thread pool."
                ));
            }

            log.info("Resuming model call [historyMessages={}]", history.size());
            String finalText = null;
            ToolExecutionContext.bindSessionUuid(sessionUuid);
            ToolExecutionContext.hydrate(session.environmentVariables());
            try {
                String continuationPrompt = "The tool result is already available in the conversation history."
                        + " Summarize the result for the user and provide any concise next-step guidance.";
                final int MAX_RESUME_ITERATIONS = 10;
                for (int resumeIter = 0; resumeIter < MAX_RESUME_ITERATIONS; resumeIter++) {
                    String rawResponse = aiService.generateWithHistory(
                            history, continuationPrompt, resolveProvider(session.provider()));
                    StructuredAgentResponse structured = extractStructured(rawResponse);
                    if ("CONTINUE_TURN".equals(structured.status())) {
                        String progressText = structured.textResponse() != null && !structured.textResponse().isBlank()
                                ? structured.textResponse() : "";
                        if (!progressText.isBlank()) {
                            UiEventFrame progressEvent = new UiEventFrame(
                                    UUID.randomUUID().toString(), "TEXT_RESPONSE", "CHAT_OUTPUT", progressText, null);
                            messaging.convertAndSend("/topic/chat/" + sessionUuid, progressEvent);
                            updated.add(new AiChatMessage(UUID.randomUUID().toString(), "ASSISTANT",
                                    progressText, System.currentTimeMillis(), null, null, null, null));
                        }
                        history.add(new AssistantMessage(progressText.isBlank() ? rawResponse : progressText));
                        continuationPrompt = "Continue executing the task. Use available tools as needed.";
                        log.debug("Resume CONTINUE_TURN [session={}, iter={}]", sessionUuid, resumeIter);
                    } else {
                        finalText = structured.textResponse() != null && !structured.textResponse().isBlank()
                                ? structured.textResponse() : rawResponse;
                        break;
                    }
                }
                if (finalText == null) {
                    finalText = "Processing required too many steps and was interrupted. Please try again.";
                }
            } catch (ToolSuspensionException ex) {
                String simulatedToolCallId = "pending-" + UUID.randomUUID();
                List<AiChatMessage.ToolCallRef> pendingToolCalls = List.of(
                    new AiChatMessage.ToolCallRef(simulatedToolCallId, "FUNCTION", ex.getToolName(), ex.getArguments()));

                String justification = ex.getJustification();
                if (justification == null || justification.isBlank()) {
                    justification = defaultAuthorizationReason(ex.getToolName());
                }

                UiEventFrame suspendedPromptEvent = new UiEventFrame(
                    UUID.randomUUID().toString(),
                    "PROMPT_REQUIRED",
                    "AUTHORIZE_TOOL",
                    justification,
                    ex.getFormSchema());

                updated.add(new AiChatMessage(
                    UUID.randomUUID().toString(),
                    "PROMPT_REQUIRED",
                    toJson(suspendedPromptEvent),
                    System.currentTimeMillis(),
                    null,
                    pendingToolCalls,
                    simulatedToolCallId,
                    ex.getToolName()));

                sessionRepo.save(new AiSession(
                    session.uuid(),
                    session.provider(),
                    originMode,
                    session.username(),
                    session.name(),
                    session.createdAt(),
                    session.currentRoundCount(),
                    List.copyOf(updated),
                    session.environmentVariables(),
                    AiSessionStatus.AWAITING_INPUT,
                    session.agentTemplateStack()));

                messaging.convertAndSend("/topic/chat/" + sessionUuid, suspendedPromptEvent);
                log.info("Resumed call suspended again [tool={}, session={}]", ex.getToolName(), sessionUuid);

                ToolExecutionContext.clear();

                return ResponseEntity.ok(Map.of(
                    "status", "AWAITING_INPUT",
                    "eventType", suspendedPromptEvent.type(),
                    "eventId", suspendedPromptEvent.eventId(),
                    "toolName", ex.getToolName()
                ));
            } catch (Exception ex) {
                log.error("Resumed model call failed [session={}]: {}", sessionUuid, ex.getMessage(), ex);

                UiEventFrame errorEvent = new UiEventFrame(
                    UUID.randomUUID().toString(),
                    "ERROR",
                    "CHAT_ERROR",
                    "Failed to continue after tool execution: " + ex.getMessage(),
                    null);

                updated.add(new AiChatMessage(
                    UUID.randomUUID().toString(),
                    "ERROR",
                    toJson(errorEvent),
                    System.currentTimeMillis(),
                    null,
                    null,
                    null,
                    null));

                sessionRepo.save(new AiSession(
                    session.uuid(),
                    session.provider(),
                    originMode,
                    session.username(),
                    session.name(),
                    session.createdAt(),
                    session.currentRoundCount(),
                    List.copyOf(updated),
                    session.environmentVariables(),
                    AiSessionStatus.RUNNING,
                    session.agentTemplateStack()));

                messaging.convertAndSend("/topic/chat/" + sessionUuid, errorEvent);
                ToolExecutionContext.clear();

                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "ERROR",
                    "message", "Failed to continue session after tool execution"
                ));
            }

            // If the sub-agent (e.g. Computer Administrator) returned FINISHED_TURN but
            // the session still has a parent on the stack (stackDepth > 1), we must pop
            // it and let the parent agent (e.g. Concierge) synthesize the final response.
            // Without this the parent never runs and the session ends prematurely.
            int resumeStackDepth = session.agentTemplateStack().size();
            if (resumeStackDepth > 1 && chatService != null) {
                log.info("Sub-agent FINISHED_TURN at stackDepth={}, delegating to parent agent [session={}]",
                        resumeStackDepth, sessionUuid);
                // Do NOT clear ToolExecutionContext here — continueLoopFromSubAgentReturn
                // needs the session UUID bound so AiOrchestrationService can apply the
                // parent agent's system prompt and tool-filtering restrictions.
                //
                // Remove any leftover 'pending-id' use-once rule before the parent agent
                // runs. That wildcard is only valid for tool calls made during the
                // immediate Spring AI resume above; if it was not consumed there it must
                // not auto-approve tool calls in the parent agent's continuation loop.
                authorizationRuleEngine.removeUseOnceRule("pending-id");
                chatService.continueLoopFromSubAgentReturn(
                        sessionUuid, List.copyOf(updated), finalText, originMode,
                        resolveProvider(session.provider()));
                return ResponseEntity.ok(Map.of(
                        "status", "WEB_RESUMED",
                        "eventType", "TEXT_RESPONSE",
                        "eventId", UUID.randomUUID().toString()
                ));
            }

            UiEventFrame textEvent = new UiEventFrame(
                    UUID.randomUUID().toString(),
                    "TEXT_RESPONSE",
                    "CHAT_OUTPUT",
                    finalText == null ? "" : finalText,
                    null);

            updated.add(new AiChatMessage(
                    UUID.randomUUID().toString(),
                    "TEXT_RESPONSE",
                    toJson(textEvent),
                    System.currentTimeMillis(),
                    null,
                    null,
                    null,
                    null));

            sessionRepo.save(new AiSession(
                    session.uuid(),
                    session.provider(),
                    originMode,
                    session.username(),
                    session.name(),
                    session.createdAt(),
                    session.currentRoundCount(),
                    List.copyOf(updated),
                    session.environmentVariables(),
                    AiSessionStatus.RUNNING,
                    session.agentTemplateStack()));

            if (chatService != null) {
                chatService.maybeGenerateSessionName(session.uuid());
            }

            log.info("Resumption completed [finalTextLength={}]", finalText == null ? 0 : finalText.length());
            log.debug("Resumption final text: {}", abbreviate(finalText == null ? "" : finalText, 4000));

            messaging.convertAndSend("/topic/chat/" + sessionUuid, textEvent);

            ToolExecutionContext.clear();

            return ResponseEntity.ok(Map.of(
                    "status", "WEB_RESUMED",
                    "eventType", textEvent.type(),
                    "eventId", textEvent.eventId()
            ));
        }
    }

        /**
     * Strips markdown fences and parses a raw model response as a
     * {@link StructuredAgentResponse}. Returns a synthetic {@code FINISHED_TURN}
     * record when the response is not structured JSON.
     */
    private StructuredAgentResponse extractStructured(String raw) {
        if (raw == null || raw.isBlank()) {
            return new StructuredAgentResponse("FINISHED_TURN", "", null, null);
        }
        try {
            String json = raw.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("(?s)^```[a-zA-Z]*\\n?", "").replaceAll("(?s)```\\s*$", "").strip();
            }
            return objectMapper.readValue(json, StructuredAgentResponse.class);
        } catch (Exception ignored) {
            return new StructuredAgentResponse("FINISHED_TURN", raw, null, null);
        }
    }

    /**
     * Parses a raw model response, strips any markdown code fence, and returns
     * only the {@code textResponse} field of a {@link StructuredAgentResponse}.
     * Falls back to the raw text when the response is not structured JSON.
     */
    private String extractTextResponse(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            String json = raw.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("(?s)^```[a-zA-Z]*\\n?", "").replaceAll("(?s)```\\s*$", "").strip();
            }
            StructuredAgentResponse structured = objectMapper.readValue(json, StructuredAgentResponse.class);
            if (structured.textResponse() != null && !structured.textResponse().isBlank()) {
                return structured.textResponse();
            }
        } catch (Exception ignored) {
            // Not a structured response — return raw text as-is
        }
        return raw;
    }

    @GetMapping("/authorize/{sessionUuid}")
        public ResponseEntity<Map<String, Object>> authorizeViaLink(@PathVariable String sessionUuid,
                                     @RequestParam(name = "approved", defaultValue = "false") boolean approved,
                                     @RequestParam(name = "policy", required = false) String policy,
                                     @RequestParam(name = "eventId", required = false) String eventId) {
        String action = approved ? policyToAction(policy) : "DENIED";
        log.info("Authorization link invoked [session={}, approved={}, policy={}, resolvedAction={}]",
            sessionUuid, approved, policy, action);

        return respond(sessionUuid,
            new InteractionResponse(
                eventId,
                "AUTHORIZE_TOOL",
                action,
                Map.of("source", "authorize-link")));
        }

        @GetMapping("/authorize/{sessionUuid}/pending")
        public ResponseEntity<Map<String, Object>> pendingAuthorization(@PathVariable String sessionUuid,
                                        @RequestParam(name = "eventId", required = false) String eventId) {
        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                    "status", "NOT_FOUND",
                    "message", "AI session not found",
                    "sessionUuid", sessionUuid));
        }

        try {
            AiChatMessage promptMessage = findPromptMessage(session.messages(), eventId);
            UiEventFrame frame = readEventFrame(promptMessage.content());
            InteractionFormSchema schema = frame.formSchema();
            if (schema == null) {
                throw new IllegalStateException("Missing interaction form schema");
            }
            List<FormAction> actions = schema.actions() == null ? List.of() : schema.actions();
            String toolName = promptMessage.toolName();
            String toolCallId = promptMessage.toolCallId();
            String arguments = extractPendingArguments(promptMessage, toolCallId);
            String displayArguments = formatDisplayArguments(toolName, arguments);
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalStateException("Missing pending tool name in prompt message");
            }
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalStateException("Missing pending tool call ID in prompt message");
            }

            Map<String, Object> response = new HashMap<>();
            response.put("status", "OK");
            response.put("sessionUuid", sessionUuid);
            response.put("sessionStatus", String.valueOf(session.status()));
            response.put("eventId", frame.eventId());
            response.put("toolName", toolName);
            response.put("toolCallId", toolCallId);
            response.put("reasoning", frame.textResponse() == null ? "" : frame.textResponse());
            response.put("arguments", arguments);
            response.put("displayArguments", displayArguments);
            response.put("actions", actions);
            response.put("formSchema", schema);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                    "status", "NOT_FOUND",
                    "message", "No pending authorization prompt found",
                    "sessionUuid", sessionUuid,
                    "sessionStatus", String.valueOf(session.status())));
        }
        }

    private AiChatMessage findPromptMessage(List<AiChatMessage> messages, String eventId) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            AiChatMessage message = messages.get(i);
            if (!"PROMPT_REQUIRED".equals(message.role())) {
                continue;
            }
            UiEventFrame frame = readEventFrame(message.content());
            if (eventId == null || eventId.isBlank() || eventId.equals(frame.eventId())) {
                return message;
            }
        }
        throw new IllegalStateException("No matching suspended prompt found");
    }

    private UiEventFrame readEventFrame(String json) {
        try {
            return objectMapper.readValue(json, UiEventFrame.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize UiEventFrame", e);
        }
    }

    private List<Message> hydrateHistory(List<AiChatMessage> messages) {
        List<Message> history = new ArrayList<>();
        for (AiChatMessage message : messages) {
            switch (message.role()) {
                case "USER" -> history.add(new UserMessage(message.content() == null ? "" : message.content()));
                case "ASSISTANT" -> history.add(new AssistantMessage(message.content() == null ? "" : message.content()));
                case "TOOL" -> history.add(toToolResponseMessage(message));
                default -> {
                    // Skip non-conversation event frames and internal control records.
                }
            }
        }
        return history;
    }

    private Message toToolResponseMessage(AiChatMessage message) {
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(message.content(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            payload = new HashMap<>();
        }

        String responseData = null;
        Object responsesRaw = payload.get("responses");
        if (responsesRaw instanceof List<?> responses && !responses.isEmpty() && responses.get(0) instanceof Map<?, ?> first) {
            Object v = first.get("responseData");
            responseData = v == null ? null : String.valueOf(v);
        }
        if (responseData == null) {
            responseData = message.content();
        }
        responseData = normalizeToolResponseData(responseData);

        ToolResponseMessage.ToolResponse toolResponse = new ToolResponseMessage.ToolResponse(
                message.toolCallId() == null ? "pending-unknown" : message.toolCallId(),
                message.toolName() == null ? "unknown-tool" : message.toolName(),
                responseData);

        return ToolResponseMessage.builder()
                .responses(List.of(toolResponse))
                .metadata(Collections.emptyMap())
                .build();
    }

    private void applyAuthorizationAction(String action,
                                          String username,
                                          String toolName,
                                          String toolCallId) {
        switch (action) {
            case "ONCE", "ALLOW_ONCE" -> {
                // SecuredToolCallback currently uses a synthetic fixed call ID.
                authorizationRuleEngine.addUseOnceRule("pending-id");
                authorizationRuleEngine.addUseOnceRule(toolCallId);
            }
            case "SESSION", "ALLOW_SESSION" -> authorizationRuleEngine.addTemporaryUserRule(username, toolName);
            case "ALWAYS", "ALLOW_ALWAYS" -> authorizationRuleEngine.addPermanentRule(username, toolName);
            case "DENIED", "DENY" -> {
                // No bypass rule written.
            }
            default -> throw new IllegalArgumentException("Unsupported action: " + action);
        }
    }

    private String toolResponseDataForAction(String action,
                                             Map<String, String> fields,
                                             String toolName,
                                             String argumentsJson) {
        switch (action) {
            case "ONCE", "ALLOW_ONCE", "SESSION", "ALLOW_SESSION", "ALWAYS", "ALLOW_ALWAYS" -> {
                String toolResult = executeTool(toolName, argumentsJson);
                if ("executeTerminalCommand".equals(toolName)) {
                    return buildTerminalToolResponse(argumentsJson, toolResult);
                }
                // Prefer raw tool JSON if the tool already returns a JSON object.
                try {
                    objectMapper.readValue(toolResult, new TypeReference<Map<String, Object>>() {});
                    return toolResult;
                } catch (Exception ignored) {
                    return toJson(Map.of(
                            "status", "APPROVED",
                            "action", action,
                            "fields", fields,
                            "result", toolResult
                    ));
                }
            }
            case "DENIED", "DENY" -> {
                return toJson(Map.of(
                        "status", "DENIED",
                        "action", action,
                        "fields", fields,
                        "message", "User denied tool execution"
                ));
            }
            default -> {
                return toJson(Map.of(
                        "status", "UNKNOWN",
                        "action", action,
                        "fields", fields
                ));
            }
        }
    }

    private String buildTerminalToolResponse(String argumentsJson, String toolResult) {
        String command = extractTerminalCommand(argumentsJson);
        String rawOutput = unwrapTerminalToolResult(toolResult);
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "COMPLETED");
        payload.put("command", command);
        String terminalId = null;
        String outputFileUuid = null;
        
        // Extract file UUID from tool result if present
        try {
            Map<String, Object> toolResultObj = objectMapper.readValue(toolResult, new TypeReference<Map<String, Object>>() {});
            Object terminalIdObj = toolResultObj.get("terminalId");
            if (terminalIdObj != null) {
                terminalId = String.valueOf(terminalIdObj);
                payload.put("terminalId", terminalId);
            }
            Object fileUuid = toolResultObj.get("outputFileUuid");
            if (fileUuid != null) {
                outputFileUuid = String.valueOf(fileUuid);
                payload.put("outputFileUuid", outputFileUuid);
            }
        } catch (Exception ignored) {
            // Not a JSON object or doesn't have file UUID, continue
        }

        // If the tool output was routed to a stored file, load it into history payload
        // so the model can reason over real command output on resumed calls.
        if ((rawOutput == null || rawOutput.isBlank()) && outputFileUuid != null && fileStorageService != null) {
            rawOutput = readTerminalOutputFile(outputFileUuid);
        }

        String displayOutput = normalizeTerminalTranscript(command, rawOutput);
        payload.put("rawOutput", compactTerminalHistoryOutput(rawOutput, outputFileUuid));
        payload.put("displayOutput", compactTerminalHistoryOutput(displayOutput, outputFileUuid));
        
        return toJson(payload);
    }

    private String compactTerminalHistoryOutput(String output, String outputFileUuid) {
        if (output == null || output.isBlank()) {
            return "";
        }
        if (output.length() <= TERMINAL_HISTORY_OUTPUT_MAX_CHARS) {
            return output;
        }

        String tail = output.substring(output.length() - TERMINAL_HISTORY_OUTPUT_MAX_CHARS);
        if (outputFileUuid != null && !outputFileUuid.isBlank()) {
            return "[terminal output truncated; full output in attachment " + outputFileUuid + "]\n\n" + tail;
        }
        return "[terminal output truncated]\n\n" + tail;
    }

    private String readTerminalOutputFile(String fileUuid) {
        try (InputStream in = fileStorageService.getContent(fileUuid)) {
            byte[] bytes = in.readAllBytes();
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (text.length() > 200_000) {
                return text.substring(0, 200_000) + "\n... [truncated, full output in attachment " + fileUuid + "]";
            }
            return text;
        } catch (Exception ex) {
            log.warn("Failed to read terminal output attachment for history [fileUuid={}]: {}", fileUuid, ex.getMessage());
            return "";
        }
    }

    private String unwrapTerminalToolResult(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return "";
        }
        try {
            return objectMapper.readValue(toolResult, String.class);
        } catch (Exception ignored) {
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(toolResult, new TypeReference<Map<String, Object>>() {});
            Object output = payload.get("output");
            if (output != null) {
                return String.valueOf(output);
            }
            Object result = payload.get("result");
            if (result != null) {
                return String.valueOf(result);
            }
            Object value = payload.get("value");
            if (value != null) {
                return String.valueOf(value);
            }
            // New file-backed terminal responses may carry no inline output.
            return "";
        } catch (Exception ignored) {
        }
        return toolResult;
    }

    private Map<String, Object> extractTerminalTranscript(String toolName, String responseData) {
        if (!"executeTerminalCommand".equals(toolName) || responseData == null || responseData.isBlank()) {
            return null;
        }

        try {
            Map<String, Object> payload = objectMapper.readValue(responseData, new TypeReference<Map<String, Object>>() {});
            Object command = payload.get("command");
            Object displayOutput = payload.get("displayOutput");
            Object rawOutput = payload.get("rawOutput");
            if (command == null) {
                return null;
            }
            Map<String, Object> transcript = new HashMap<>();
            transcript.put("command", String.valueOf(command));
            transcript.put("output", displayOutput == null ? "" : String.valueOf(displayOutput));
            transcript.put("rawOutput", rawOutput == null ? "" : String.valueOf(rawOutput));

            Object terminalId = payload.get("terminalId");
            if (terminalId != null) {
                transcript.put("terminalId", String.valueOf(terminalId));
            }
            
            // Include file UUID if present
            Object fileUuid = payload.get("outputFileUuid");
            if (fileUuid != null) {
                transcript.put("outputFileUuid", String.valueOf(fileUuid));
            }
            
            return transcript;
        } catch (Exception ex) {
            return null;
        }
    }

    private String extractTerminalCommand(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
            Object command = args.get("command");
            return command == null ? "" : String.valueOf(command);
        } catch (Exception ex) {
            return "";
        }
    }

    private String normalizeTerminalTranscript(String command, String output) {
        String normalized = output == null ? "" : output.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isBlank() || command == null || command.isBlank()) {
            return normalized;
        }

        while (!normalized.isBlank()) {
            int newlineIndex = normalized.indexOf('\n');
            String firstLine = newlineIndex >= 0 ? normalized.substring(0, newlineIndex) : normalized;
            String firstLineWithoutPrompt = firstLine.replaceFirst("^\\s*[$#>%]\\s*", "").trim();
            if (!firstLineWithoutPrompt.contains(command)) {
                break;
            }
            if (newlineIndex < 0) {
                return "";
            }
            normalized = normalized.substring(newlineIndex + 1);
        }

        return normalized;
    }

    private String executeTool(String toolName, String argumentsJson) {
        ToolCallback callback = toolCallbacksByName.get(toolName);
        if (callback == null) {
            throw new IllegalStateException("No tool callback registered for: " + toolName);
        }
        log.info("Executing approved tool [tool={}, argsSize={}]", toolName,
                argumentsJson == null ? 0 : argumentsJson.length());
        log.debug("Tool arguments [tool={}]: {}", toolName, abbreviate(argumentsJson, 4000));

        String result;
        try {
            result = callback.call(argumentsJson);
        } catch (RuntimeException ex) {
            ToolSuspensionException suspension = findCause(ex, ToolSuspensionException.class);
            if (suspension != null) {
                throw suspension;
            }
            throw ex;
        }

        log.info("Tool execution completed [tool={}, resultSize={}]", toolName,
                result == null ? 0 : result.length());
        log.debug("Tool result [tool={}]: {}", toolName, abbreviate(result, 4000));
        return result;
    }

    private static <T extends Throwable> T findCause(Throwable ex, Class<T> type) {
        Throwable current = ex;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private String normalizeToolResponseData(String responseData) {
        if (responseData == null || responseData.isBlank()) {
            return toJson(Map.of("status", "UNKNOWN", "value", ""));
        }
        try {
            objectMapper.readValue(responseData, new TypeReference<Map<String, Object>>() {});
            return responseData; // already JSON object
        } catch (Exception ignored) {
            return toJson(Map.of("status", "LEGACY", "value", responseData));
        }
    }

    private static String normalizeAction(String action) {
        if (action == null || action.isBlank()) {
            return "DENIED";
        }
        return action.trim().toUpperCase(Locale.ROOT);
    }

    private static String resolveSuspendedArguments(String priorArguments, String suspendedArguments) {
        String fallback = (priorArguments == null || priorArguments.isBlank()) ? "{}" : priorArguments;
        if (suspendedArguments == null) {
            return fallback;
        }
        String candidate = suspendedArguments.trim();
        if (candidate.isBlank() || "{}".equals(candidate)) {
            return fallback;
        }
        return suspendedArguments;
    }

    private static String policyToAction(String policy) {
        if (policy == null || policy.isBlank()) {
            return "ONCE";
        }

        return switch (policy.trim().toUpperCase(Locale.ROOT)) {
            case "ONCE", "ALLOW_ONCE" -> "ONCE";
            case "SESSION", "ALLOW_SESSION", "TEMPORARY" -> "SESSION";
            case "ALWAYS", "ALLOW_ALWAYS", "PERMANENT" -> "ALWAYS";
            default -> "ONCE";
        };
    }

    private static String defaultAuthorizationReason(String toolName) {
        if ("compileJavaType".equals(toolName)) {
            return "Approval is required to compile and register a new Java type so it can be used in later steps.";
        }
        return "Approval is required to run this protected action so your request can continue safely.";
    }

    private static String resolveUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return "anonymous";
        }
        return auth.getName();
    }

    private static AiProvider resolveProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return AiProvider.GEMINI;
        }
        try {
            AiProvider resolved = AiProvider.valueOf(provider.toUpperCase(Locale.ROOT));
            return resolved == AiProvider.BACKGROUND_SCHEDULER ? AiProvider.GEMINI : resolved;
        } catch (IllegalArgumentException e) {
            return AiProvider.GEMINI;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize JSON payload", e);
        }
    }

    public record InteractionResponse(
            String eventId,
            String intent,
            String action,
            Map<String, String> fields
    ) {}

    private static VorkUser toPrincipalUser(String username) {
        return new VorkUser(username, "", "USER", 0L, 0L);
    }

    private static Map<String, FieldSource> buildFieldSourceMap(InteractionFormSchema schema) {
        if (schema == null || schema.fields() == null) {
            return Map.of();
        }
        Map<String, FieldSource> map = new HashMap<>();
        for (FormField field : schema.fields()) {
            if (field == null || field.name() == null || field.name().isBlank()) {
                continue;
            }
            map.put(field.name(), field.source() == null ? FieldSource.CONVERSATION : field.source());
        }
        return map;
    }

    private static String extractPendingArguments(AiChatMessage promptMessage, String toolCallId) {
        if (promptMessage.toolCalls() == null || promptMessage.toolCalls().isEmpty()) {
            return "{}";
        }
        for (AiChatMessage.ToolCallRef call : promptMessage.toolCalls()) {
            if (call == null) {
                continue;
            }
            if (toolCallId != null && toolCallId.equals(call.id())) {
                return call.arguments() == null ? "{}" : call.arguments();
            }
        }
        AiChatMessage.ToolCallRef first = promptMessage.toolCalls().get(0);
        return first.arguments() == null ? "{}" : first.arguments();
    }

    private String formatDisplayArguments(String toolName, String arguments) {
        String raw = (arguments == null || arguments.isBlank()) ? "{}" : arguments;
        ToolCallback callback = toolCallbacksByName.get(toolName);
        if (callback instanceof VisualizableTool visualizableTool) {
            try {
                String formatted = visualizableTool.formatAuthorizationDetails(raw);
                if (formatted != null && !formatted.isBlank()) {
                    return formatted;
                }
            } catch (Exception ignored) {
                // Fall through to raw arguments.
            }
        }
        return raw;
    }

    private static String abbreviate(String value, int maxLen) {
        if (value == null) {
            return "<null>";
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + "...<truncated>";
    }
}
