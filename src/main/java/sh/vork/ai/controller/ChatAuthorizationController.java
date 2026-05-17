package sh.vork.ai.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.security.AuthorizationRuleEngine;
import sh.vork.ai.service.AiOrchestrationService;
import sh.vork.scheduling.service.AiSchedulerService;
import sh.vork.database.DatabaseRepository;
import sh.vork.scheduling.service.SystemBackgroundAuthentication;

import java.util.concurrent.Executor;

/**
 * Accepts structured user responses to PROMPT_REQUIRED frames and resumes chat execution.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatAuthorizationController {

    private static final Logger log = LoggerFactory.getLogger(ChatAuthorizationController.class);

    private final DatabaseRepository<AiSession> sessionRepo;
    private final AuthorizationRuleEngine authorizationRuleEngine;
    private final AiOrchestrationService aiService;
    private final SimpMessagingTemplate messaging;
    private final ObjectMapper objectMapper;
    private final Map<String, ToolCallback> toolCallbacksByName;
    private final Executor aiBackgroundExecutor;
    private final AiSchedulerService aiSchedulerService;

    public ChatAuthorizationController(DatabaseRepository<AiSession> sessionRepo,
                                       AuthorizationRuleEngine authorizationRuleEngine,
                                       AiOrchestrationService aiService,
                                       SimpMessagingTemplate messaging,
                                       ObjectMapper objectMapper,
                                       List<ToolCallback> toolCallbacks,
                                       @Qualifier("aiBackgroundExecutor") Executor aiBackgroundExecutor,
                                       AiSchedulerService aiSchedulerService) {
        this.sessionRepo = sessionRepo;
        this.authorizationRuleEngine = authorizationRuleEngine;
        this.aiService = aiService;
        this.messaging = messaging;
        this.objectMapper = objectMapper;
        this.aiBackgroundExecutor = aiBackgroundExecutor;
        this.aiSchedulerService = aiSchedulerService;
        this.toolCallbacksByName = toolCallbacks.stream().collect(
                java.util.stream.Collectors.toMap(
                        t -> t.getToolDefinition().name(),
                        Function.identity(),
                        (a, b) -> a));
    }

    @PostMapping("/respond/{sessionUuid}")
    public ResponseEntity<Map<String, Object>> respond(@PathVariable String sessionUuid,
                                                       @RequestBody AuthorizationResponseRequest request) {
        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null) {
            throw new IllegalStateException("AI session not found: " + sessionUuid);
        }
        if (session.status() != AiSessionStatus.AWAITING_AUTHORIZATION) {
            throw new IllegalStateException("Session is not awaiting input: " + sessionUuid);
        }

        AiChatMessage promptMessage = findPromptMessage(session.messages(), request.eventId());
        UiEventFrame promptEvent = readEventFrame(promptMessage.content());
        String correlationEventId = (request.eventId() == null || request.eventId().isBlank())
            ? promptEvent.eventId()
            : request.eventId();

        try (MDC.MDCCloseable sid = MDC.putCloseable("sessionUuid", sessionUuid);
             MDC.MDCCloseable eid = MDC.putCloseable("eventId", correlationEventId)) {

            String toolName = String.valueOf(promptEvent.payload().get("toolName"));
            String toolCallId = String.valueOf(promptEvent.payload().get("toolCallId"));
            String action = normalizeAction(request.action());
            String username = resolveUsername();
            Map<String, Object> fields = request.fields() == null ? Map.of() : request.fields();

            log.info("Resume request received [action={}, tool={}, toolCallId={}, user={}, fieldKeys={}]",
                action,
                toolName,
                toolCallId,
                username,
                fields.keySet());
            log.debug("Resume request fields payload: {}", abbreviate(toJson(fields), 2000));

            applyAuthorizationAction(action, username, toolName, toolCallId);

            String argumentsJson = String.valueOf(promptEvent.payload().getOrDefault("arguments", "{}"));
            String token = toolResponseDataForAction(action, fields, toolName, argumentsJson);
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
                "fields", fields
            ));

            Map<String, Object> terminalTranscript = extractTerminalTranscript(toolName, toolResponse.responseData());
            if (terminalTranscript != null) {
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", "TOOL_RESPONSE");
                payload.put("responses", List.of(Map.of(
                        "id", toolResponse.id(),
                        "name", toolResponse.name(),
                        "responseData", toolResponse.responseData())));
                payload.put("message", toolResponseMessage.toString());
                payload.put("fields", fields);
                payload.put("terminalTranscript", terminalTranscript);
                toolPayloadJson = toJson(payload);
            }

            List<AiChatMessage> updated = new ArrayList<>(session.messages());
            updated.add(new AiChatMessage(
                UUID.randomUUID().toString(),
                "TOOL",
                toolPayloadJson,
                System.currentTimeMillis(),
                null,
                null,
                toolCallId,
                toolName));

            log.info("Tool response persisted [tool={}, toolCallId={}, payloadSize={}]",
                toolName, toolCallId, toolPayloadJson.length());
            log.debug("Tool response payload: {}", abbreviate(toolPayloadJson, 4000));

            List<Message> history = hydrateHistory(updated);
            SessionOriginMode originMode = session.originMode() == null ? SessionOriginMode.WEB : session.originMode();

            if (originMode == SessionOriginMode.BACKGROUND) {
                sessionRepo.save(new AiSession(
                        session.uuid(),
                        session.provider(),
                        originMode,
                        session.username(),
                        session.createdAt(),
                    session.currentRoundCount(),
                        List.copyOf(updated),
                    AiSessionStatus.RUNNING));

                aiBackgroundExecutor.execute(() -> {
                    try {
                        SecurityContextHolder.getContext()
                                .setAuthentication(new SystemBackgroundAuthentication(session.username()));
                        aiSchedulerService.resumeBackgroundSession(sessionUuid);
                    } catch (Exception ex) {
                        log.error("Background resume failed [session={}]: {}", sessionUuid, ex.getMessage(), ex);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });

                return ResponseEntity.ok(Map.of(
                        "status", "BACKGROUND_RESUMED",
                        "message", "The background task has resumed processing in an isolated thread pool."
                ));
            }

            log.info("Resuming model call [historyMessages={}]", history.size());
            String finalText = aiService.generateWithHistory(history, "Please continue based on the tool response.",
                    resolveProvider(session.provider()));

            UiEventFrame textEvent = new UiEventFrame(
                    UUID.randomUUID().toString(),
                    "TEXT_RESPONSE",
                    "CHAT_OUTPUT",
                    Map.of("content", finalText == null ? "" : finalText));

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
                    session.createdAt(),
                    session.currentRoundCount(),
                    List.copyOf(updated),
                    AiSessionStatus.RUNNING));

            log.info("Resumption completed [finalTextLength={}]", finalText == null ? 0 : finalText.length());
            log.debug("Resumption final text: {}", abbreviate(finalText == null ? "" : finalText, 4000));

            messaging.convertAndSend("/topic/chat/" + sessionUuid, textEvent);
            return ResponseEntity.ok(Map.of(
                    "status", "WEB_RESUMED",
                    "eventType", textEvent.type(),
                    "eventId", textEvent.eventId()
            ));
        }
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
            new AuthorizationResponseRequest(
                eventId,
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
            Map<String, Object> payload = frame.payload() == null ? Map.of() : frame.payload();
            Object actions = payload.getOrDefault("actions", List.of("ONCE", "SESSION", "ALWAYS", "DENIED"));

            return ResponseEntity.ok(Map.of(
                "status", "OK",
                "sessionUuid", sessionUuid,
                "sessionStatus", String.valueOf(session.status()),
                "eventId", frame.eventId(),
                "toolName", String.valueOf(payload.getOrDefault("toolName", "unknown-tool")),
                "toolCallId", String.valueOf(payload.getOrDefault("toolCallId", "pending-id")),
                "reasoning", String.valueOf(payload.getOrDefault("reasoning", "")),
                "arguments", String.valueOf(payload.getOrDefault("arguments", "{}")),
                "displayArguments", String.valueOf(payload.getOrDefault("displayArguments", payload.getOrDefault("arguments", "{}"))),
                "actions", actions));
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
                                             Map<String, Object> fields,
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
        String displayOutput = normalizeTerminalTranscript(command, rawOutput);
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "COMPLETED");
        payload.put("command", command);
        payload.put("rawOutput", rawOutput);
        payload.put("displayOutput", displayOutput);
        return toJson(payload);
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
            Object result = payload.get("result");
            if (result != null) {
                return String.valueOf(result);
            }
            Object value = payload.get("value");
            if (value != null) {
                return String.valueOf(value);
            }
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

        String result = callback.call(argumentsJson);

        log.info("Tool execution completed [tool={}, resultSize={}]", toolName,
                result == null ? 0 : result.length());
        log.debug("Tool result [tool={}]: {}", toolName, abbreviate(result, 4000));
        return result;
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

    public record AuthorizationResponseRequest(
            String eventId,
            String action,
            Map<String, Object> fields
    ) {}

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
