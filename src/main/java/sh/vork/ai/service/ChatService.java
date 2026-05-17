package sh.vork.ai.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiChatMessage.AttachmentRef;
import sh.vork.ai.entity.AiChatMessage.ToolCallRef;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.security.ToolSuspensionException;
import sh.vork.ai.security.VisualizableTool;
import sh.vork.database.DatabaseRepository;
import sh.vork.scheduling.service.SystemNotificationService;
import sh.vork.storage.FileStorageService;
import sh.vork.storage.StoredFile;

/**
 * Manages AI chat sessions and conversation history.
 *
 * <p>Each browser HTTP session maps to exactly one {@link AiSession} document
 * stored in MongoDB.  All messages are embedded in that document so the full
 * conversation is fetched in a single read.  Both the user message and the AI
 * response are persisted <em>after</em> a successful AI call — partial failures
 * leave the stored conversation in a consistent state.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final DatabaseRepository<AiSession> sessionRepo;
    private final AiOrchestrationService        aiService;
    private final FileStorageService            fileStorageService;
    private final SimpMessagingTemplate         messaging;
    private final ObjectMapper                  objectMapper;
    private final Map<String, ToolCallback>     toolCallbacksByName;
    private final SystemNotificationService     systemNotificationService;
  
    @Autowired
    public ChatService(DatabaseRepository<AiSession> aiSessionRepository,
                       AiOrchestrationService aiOrchestrationService,
                       FileStorageService fileStorageService,
                       SimpMessagingTemplate messaging,
                       ObjectMapper objectMapper,
                   List<ToolCallback> toolCallbacks,
                   SystemNotificationService systemNotificationService) {
        this.sessionRepo         = aiSessionRepository;
        this.aiService           = aiOrchestrationService;
        this.fileStorageService  = fileStorageService;
        this.messaging           = messaging;
        this.objectMapper        = objectMapper;
        this.systemNotificationService = systemNotificationService;
        this.toolCallbacksByName = toolCallbacks.stream().collect(
                Collectors.toMap(
                        t -> t.getToolDefinition().name(),
                        t -> t,
                        (a, b) -> a));
    }

        public ChatService(DatabaseRepository<AiSession> aiSessionRepository,
                   AiOrchestrationService aiOrchestrationService,
                   FileStorageService fileStorageService,
                   SimpMessagingTemplate messaging,
                   ObjectMapper objectMapper,
                   List<ToolCallback> toolCallbacks) {
        this(aiSessionRepository, aiOrchestrationService, fileStorageService, messaging,
            objectMapper, toolCallbacks, (toolName, arguments, sessionUuid, eventId) -> {
            });
        }

    /**
     * Returns the existing {@link AiSession} for the given HTTP session ID,
     * or creates a new one bound to {@code provider}.
     */
    public AiSession getOrCreateSession(String httpSessionId, AiProvider provider) {
        AiSession existing = sessionRepo.get(httpSessionId);
        if (existing != null) {
            log.debug("Resuming AI session [id={}, messages={}]", httpSessionId, existing.messages().size());
            return existing;
        }
        String username = resolveUsername();
        AiSession session = new AiSession(httpSessionId, provider.name(), SessionOriginMode.WEB,
            username, System.currentTimeMillis(), 0, List.of(), AiSessionStatus.RUNNING);
        sessionRepo.save(session);
        log.info("Created AI session [id={}, provider={}, user={}]", httpSessionId, provider, username);
        return session;
    }

    /**
     * Sends {@code content} (and optional file attachments) to the AI with the
     * full conversation history, persists both the user message and the AI response
     * on success, and returns the AI response as an {@link AiChatMessage}.
     *
     * @param sessionUuid     UUID of the target {@link AiSession}
     * @param content         the user's message text (may be blank when files are provided)
     * @param attachmentUuids UUIDs of previously-uploaded files to include
     * @param provider        the AI provider to use
     * @return the AI's response message (role {@code "ASSISTANT"})
     * @throws IllegalStateException if the session is not found
     */
    public AiChatMessage sendMessage(String sessionUuid, String content,
                                     List<String> attachmentUuids, AiProvider provider) {
        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null) {
            throw new IllegalStateException("AI session not found: " + sessionUuid);
        }

        // Build Spring AI message list from stored history
        // AWAITING_AUTHORIZATION and TOOL messages are skipped here; the
        // authorization controller handles resumption via generateFromHistory().
        List<Message> history = hydrateHistory(session.messages());

        log.info("Chat turn [session={}, history={} msgs, attachments={}, provider={}]",
                sessionUuid, history.size(),
                attachmentUuids == null ? 0 : attachmentUuids.size(), provider);

        // Resolve attachments → media objects and attachment refs for persistence
        List<AttachmentRef> refs      = new ArrayList<>();
        List<Media>         media     = new ArrayList<>();
        List<String>        textParts = new ArrayList<>();

        if (attachmentUuids != null) {
            for (String fileUuid : attachmentUuids) {
                StoredFile meta = fileStorageService.getMetadata(fileUuid);
                if (meta == null) {
                    log.warn("Attachment not found, skipping [uuid={}]", fileUuid);
                    continue;
                }
                refs.add(new AttachmentRef(meta.uuid(), meta.originalName(), meta.mimeType()));

                String mime = meta.mimeType().toLowerCase();
                if (mime.startsWith("text/")) {
                    try (InputStream in = fileStorageService.getContent(fileUuid)) {
                        String text = new String(in.readAllBytes());
                        textParts.add("[Attached file: " + meta.originalName() + "]\n" + text);
                    } catch (IOException ex) {
                        log.error("Failed to read text attachment [uuid={}]: {}", fileUuid, ex.getMessage());
                    }
                } else if (FileStorageService.isAiSupported(mime)) {
                    try (InputStream in = fileStorageService.getContent(fileUuid)) {
                        byte[] bytes = in.readAllBytes();
                        media.add(new Media(MimeType.valueOf(meta.mimeType()), new ByteArrayResource(bytes)));
                    } catch (IOException ex) {
                        log.error("Failed to read media attachment [uuid={}]: {}", fileUuid, ex.getMessage());
                    }
                } else {
                    log.info("Attachment MIME type not AI-supported, skipping [uuid={}, mime={}]",
                            fileUuid, meta.mimeType());
                }
            }
        }

        // Build the effective user text (inline text files prepended)
        String effectiveContent = content == null ? "" : content;
        if (!textParts.isEmpty()) {
            effectiveContent = String.join("\n\n", textParts)
                    + (effectiveContent.isBlank() ? "" : "\n\n" + effectiveContent);
        }

        long now = System.currentTimeMillis();
        List<AttachmentRef> userRefs = refs.isEmpty() ? null : Collections.unmodifiableList(refs);
        AiChatMessage userMsg = new AiChatMessage(UUID.randomUUID().toString(), "USER",
                content == null ? "" : content, now, userRefs);

        try {
            String aiContent;
            if (media.isEmpty()) {
                aiContent = safeGenerateWithHistory(history, effectiveContent, provider);
            } else {
                aiContent = safeGenerateWithHistoryAndMedia(history, effectiveContent, media, provider);
            }

            AiChatMessage aiMsg = new AiChatMessage(UUID.randomUUID().toString(), "ASSISTANT",
                    aiContent == null ? "" : aiContent, System.currentTimeMillis(),
                    refs.isEmpty() ? null : Collections.unmodifiableList(refs));

            List<AiChatMessage> updated = new ArrayList<>(session.messages());
            updated.add(userMsg);
            updated.add(aiMsg);

                AiSessionStatus persistedStatus = resolveStatusForReplyPersistence(sessionUuid, AiSessionStatus.RUNNING);
                sessionRepo.save(new AiSession(
                    session.uuid(),
                    session.provider(),
                    session.originMode(),
                    session.username(),
                    session.createdAt(),
                    session.currentRoundCount(),
                    List.copyOf(updated),
                    persistedStatus));

            log.info("Persisted chat turn [session={}, totalMessages={}]", sessionUuid, updated.size());
            return aiMsg;
        } catch (ToolSuspensionException ex) {
            String simulatedToolCallId = "pending-" + UUID.randomUUID();
            List<ToolCallRef> pendingToolCalls = List.of(
                    new ToolCallRef(simulatedToolCallId, "FUNCTION", ex.getToolName(), ex.getArguments()));

            ToolCallback targetTool = toolCallbacksByName.get(ex.getToolName());
            String displayArguments = ex.getArguments();
            if (targetTool instanceof VisualizableTool visualTool) {
                try {
                    displayArguments = visualTool.formatAuthorizationDetails(ex.getArguments());
                } catch (Exception e) {
                    log.warn("Failed to pretty print tool arguments, falling back to raw JSON", e);
                }
            }

            String justification = ex.getReasoning();
            if (justification == null || justification.isBlank()) {
                justification = defaultAuthorizationReason(ex.getToolName());
            }

            String eventId = UUID.randomUUID().toString();
            UiEventFrame promptEvent = new UiEventFrame(
                eventId,
                "PROMPT_REQUIRED",
                "AUTHORIZE_TOOL",
                java.util.Map.of(
                    "toolName", ex.getToolName(),
                    "toolCallId", simulatedToolCallId,
                    "reasoning", justification,
                    "arguments", ex.getArguments(),
                    "displayArguments", displayArguments,
                    "actions", java.util.List.of("ONCE", "SESSION", "ALWAYS", "DENIED")
                ));

            String promptJson;
            try {
            promptJson = objectMapper.writeValueAsString(promptEvent);
            } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize suspension event", e);
            }

                try (MDC.MDCCloseable sid = MDC.putCloseable("sessionUuid", sessionUuid);
                 MDC.MDCCloseable eid = MDC.putCloseable("eventId", eventId)) {
                log.info("Prompt required event created [tool={}, toolCallId={}]",
                    ex.getToolName(), simulatedToolCallId);
                log.debug("Prompt required event payload: {}",
                    promptJson.length() > 4000 ? promptJson.substring(0, 4000) + "...<truncated>" : promptJson);
                }

            AiChatMessage awaiting = new AiChatMessage(
                    UUID.randomUUID().toString(),
                "PROMPT_REQUIRED",
                promptJson,
                    System.currentTimeMillis(),
                    refs.isEmpty() ? null : Collections.unmodifiableList(refs),
                    pendingToolCalls,
                    null,
                    null);

            List<AiChatMessage> updated = new ArrayList<>(session.messages());
            updated.add(userMsg);
            updated.add(awaiting);
                sessionRepo.save(new AiSession(session.uuid(), session.provider(), session.originMode(), session.username(),
                    session.createdAt(), session.currentRoundCount(), List.copyOf(updated), AiSessionStatus.AWAITING_AUTHORIZATION));

                if (provider == AiProvider.BACKGROUND_SCHEDULER) {
                systemNotificationService.notifyOfflineOperator(ex.getToolName(), ex.getArguments(), sessionUuid, eventId);
                }

                messaging.convertAndSend("/topic/chat/" + sessionUuid, promptEvent);

            log.info("Tool suspension caught for tool: {}. Frozen session state [session={}]",
                    ex.getToolName(), sessionUuid);
            return null;
        }
    }

    private String safeGenerateWithHistory(List<Message> history, String effectiveContent, AiProvider provider) {
        try {
            return aiService.generateWithHistory(history, effectiveContent, provider);
        } catch (NoSuchElementException ex) {
            // Guard against occasional provider responses without candidates.
            log.warn("Model returned no candidate for history call; retrying with simplified prompt path [provider={}]", provider);
            try {
                return aiService.generate(effectiveContent, provider);
            } catch (NoSuchElementException ex2) {
                log.error("Model returned no candidate for simplified prompt path [provider={}]", provider);
                return modelUnavailableMessage();
            }
        }
    }

    private String safeGenerateWithHistoryAndMedia(List<Message> history,
                                                   String effectiveContent,
                                                   List<Media> media,
                                                   AiProvider provider) {
        try {
            return aiService.generateWithHistoryAndMedia(history, effectiveContent, media, provider);
        } catch (NoSuchElementException ex) {
            log.warn("Model returned no candidate for media call; retrying with text-only fallback [provider={}]", provider);
            try {
                return aiService.generate(effectiveContent, provider);
            } catch (NoSuchElementException ex2) {
                log.error("Model returned no candidate for text-only fallback after media call [provider={}]", provider);
                return modelUnavailableMessage();
            }
        }
    }

    private static String modelUnavailableMessage() {
        return "I couldn't produce a model response right now due to a transient provider issue. Please try again.";
    }

    private static String defaultAuthorizationReason(String toolName) {
        if ("compileJavaType".equals(toolName)) {
            return "Approval is required to compile and register a new Java type so it can be used in later steps.";
        }
        return "Approval is required to run this protected action so your request can continue safely.";
    }

    private static String resolveUsername() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return "anonymous";
        }
        return auth.getName();
    }

    public void handleAiReply(String sessionUuid, String aiOutput) {
        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null) {
            throw new IllegalStateException("AI session not found: " + sessionUuid);
        }

        AiChatMessage aiMsg = new AiChatMessage(
                UUID.randomUUID().toString(),
                "ASSISTANT",
                aiOutput == null ? "" : aiOutput,
                System.currentTimeMillis(),
                null);

        List<AiChatMessage> updated = new ArrayList<>(session.messages());
        updated.add(aiMsg);
        AiSessionStatus persistedStatus = resolveStatusForReplyPersistence(sessionUuid, AiSessionStatus.RUNNING);
        sessionRepo.save(new AiSession(
                session.uuid(),
                session.provider(),
                session.originMode(),
                session.username(),
                session.createdAt(),
            session.currentRoundCount(),
                List.copyOf(updated),
            persistedStatus));
    }

    private AiSessionStatus resolveStatusForReplyPersistence(String sessionUuid, AiSessionStatus defaultStatus) {
        AiSession latest = sessionRepo.get(sessionUuid);
        if (latest == null) {
            return defaultStatus;
        }
        if (latest.status() == AiSessionStatus.COMPLETED || latest.status() == AiSessionStatus.FAILED_MAX_ROUNDS) {
            return latest.status();
        }
        return defaultStatus;
    }

    private List<Message> hydrateHistory(List<AiChatMessage> messages) {
        List<Message> history = new ArrayList<>();
        for (AiChatMessage message : messages) {
            switch (message.role()) {
                case "USER" -> history.add(new UserMessage(message.content() == null ? "" : message.content()));
                case "ASSISTANT" -> history.add(new AssistantMessage(message.content() == null ? "" : message.content()));
                case "TOOL" -> history.add(toToolResponseMessage(message));
                default -> {
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
            Object value = first.get("responseData");
            responseData = value == null ? null : String.valueOf(value);
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

    private String normalizeToolResponseData(String responseData) {
        if (responseData == null || responseData.isBlank()) {
            return toJson(Map.of("status", "UNKNOWN", "value", ""));
        }
        try {
            objectMapper.readValue(responseData, new TypeReference<Map<String, Object>>() {});
            return responseData;
        } catch (Exception ignored) {
            return toJson(Map.of("status", "LEGACY", "value", responseData));
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chat payload", e);
        }
    }

}
