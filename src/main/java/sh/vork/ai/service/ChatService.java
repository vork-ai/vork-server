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
import org.springframework.beans.factory.annotation.Qualifier;
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
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.database.DatabaseRepository;
import sh.vork.database.SearchQuery;
import sh.vork.database.SortOrder;
import sh.vork.scheduling.service.SystemNotificationService;
import sh.vork.storage.FileStorageService;
import sh.vork.storage.StoredFile;

import java.util.concurrent.Executor;

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
    private final SystemNotificationService     systemNotificationService;
    private final Executor                      aiBackgroundExecutor;

    private static final String DEFAULT_SESSION_NAME = "Untitled";
  
    @Autowired
    public ChatService(DatabaseRepository<AiSession> aiSessionRepository,
                       AiOrchestrationService aiOrchestrationService,
                       FileStorageService fileStorageService,
                       SimpMessagingTemplate messaging,
                       ObjectMapper objectMapper,
                   List<ToolCallback> toolCallbacks,
                       SystemNotificationService systemNotificationService,
                       @Qualifier("aiBackgroundExecutor") Executor aiBackgroundExecutor) {
        this.sessionRepo         = aiSessionRepository;
        this.aiService           = aiOrchestrationService;
        this.fileStorageService  = fileStorageService;
        this.messaging           = messaging;
        this.objectMapper        = objectMapper;
        this.systemNotificationService = systemNotificationService;
        this.aiBackgroundExecutor = aiBackgroundExecutor;
    }

    /**
     * Returns the existing {@link AiSession} for the given HTTP session ID,
     * or creates a new one bound to {@code provider}.
     */
    public AiSession getOrCreateSession(String httpSessionId, AiProvider provider) {
        if (httpSessionId == null || httpSessionId.isBlank()) {
            throw new IllegalArgumentException("httpSessionId is required");
        }

        AiSession existing = sessionRepo.get(httpSessionId);
        if (existing != null) {
            String username = resolveUsername();
            if (!username.equals(existing.username())) {
                throw new IllegalStateException("Access denied for session: " + httpSessionId);
            }
            log.debug("Resuming HTTP session-bound AI session [id={}, messages={}]",
                existing.uuid(), existing.messages().size());
            return existing;
        }

        String username = resolveUsername();
        AiSession session = new AiSession(httpSessionId, provider.name(), SessionOriginMode.WEB,
            username, DEFAULT_SESSION_NAME, System.currentTimeMillis(), 0, List.of(), AiSessionStatus.RUNNING);
        sessionRepo.save(session);
        log.info("Created HTTP session-bound AI session [id={}, provider={}, user={}]",
            httpSessionId, provider, username);
        return session;
    }

    public AiSession createNewSession(AiProvider provider) {
        String username = resolveUsername();
        String uuid = UUID.randomUUID().toString();
        AiSession session = new AiSession(uuid, provider.name(), SessionOriginMode.WEB,
            username, DEFAULT_SESSION_NAME, System.currentTimeMillis(), 0, List.of(), AiSessionStatus.RUNNING);
        sessionRepo.save(session);
        log.info("Created AI session [id={}, provider={}, user={}]", uuid, provider, username);
        return session;
    }

    public AiSession getSessionForCurrentUser(String sessionUuid) {
        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null) {
            throw new IllegalStateException("AI session not found: " + sessionUuid);
        }
        String username = resolveUsername();
        if (!username.equals(session.username())) {
            throw new IllegalStateException("Access denied for session: " + sessionUuid);
        }
        return session;
    }

    public List<AiSession> listSessionsForCurrentUser() {
        String username = resolveUsername();
        try (var stream = sessionRepo.search(0, 200, "createdAt", SortOrder.DESC,
                SearchQuery.eq("username", username),
                SearchQuery.eq("originMode", SessionOriginMode.WEB.name()))) {
            return stream.collect(Collectors.toList());
        }
    }

    public AiSession renameSessionForCurrentUser(String sessionUuid, String requestedName) {
        AiSession session = getSessionForCurrentUser(sessionUuid);
        String name = requestedName == null ? "" : requestedName.trim();
        if (name.isBlank()) {
            name = DEFAULT_SESSION_NAME;
        }
        if (name.length() > 60) {
            name = name.substring(0, 60).trim();
        }

        AiSession renamed = new AiSession(
                session.uuid(),
                session.provider(),
                session.originMode(),
                session.username(),
                name,
                session.createdAt(),
                session.currentRoundCount(),
                session.messages(),
                session.status());
        sessionRepo.save(renamed);
        return renamed;
    }

    public void maybeGenerateSessionName(String sessionUuid) {
        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null) {
            return;
        }
        if (session.originMode() != SessionOriginMode.WEB) {
            return;
        }
        if (!DEFAULT_SESSION_NAME.equalsIgnoreCase(session.name())) {
            return;
        }
        if (!hasFullRoundTrip(session.messages())) {
            return;
        }

        aiBackgroundExecutor.execute(() -> generateAndPersistSessionName(sessionUuid));
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
        AiSession session = getSessionForCurrentUser(sessionUuid);

        // Build Spring AI message list from stored history
        // AWAITING_INPUT and TOOL messages are skipped here; the
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
                    session.name(),
                    session.createdAt(),
                    session.currentRoundCount(),
                    List.copyOf(updated),
                    persistedStatus));

            maybeGenerateSessionName(session.uuid());

            log.info("Persisted chat turn [session={}, totalMessages={}]", sessionUuid, updated.size());
            return aiMsg;
        } catch (ToolSuspensionException ex) {
            String simulatedToolCallId = "pending-" + UUID.randomUUID();
            List<ToolCallRef> pendingToolCalls = List.of(
                    new ToolCallRef(simulatedToolCallId, "FUNCTION", ex.getToolName(), ex.getArguments()));

            String justification = ex.getJustification();
            if (justification == null || justification.isBlank()) {
                justification = defaultAuthorizationReason(ex.getToolName());
            }

            String eventId = UUID.randomUUID().toString();
            UiEventFrame promptEvent = new UiEventFrame(
                eventId,
                "PROMPT_REQUIRED",
                "AUTHORIZE_TOOL",
                justification,
                ex.getFormSchema());

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
                    simulatedToolCallId,
                    ex.getToolName());

            List<AiChatMessage> updated = new ArrayList<>(session.messages());
            updated.add(userMsg);
            updated.add(awaiting);
                sessionRepo.save(new AiSession(session.uuid(), session.provider(), session.originMode(), session.username(),
                    session.name(), session.createdAt(), session.currentRoundCount(), List.copyOf(updated), AiSessionStatus.AWAITING_INPUT));

                if (provider == AiProvider.BACKGROUND_SCHEDULER) {
                systemNotificationService.notifyOfflineOperator(ex.getToolName(), ex.getArguments(), sessionUuid, eventId);
                }

                messaging.convertAndSend("/topic/chat/" + sessionUuid, promptEvent);

            log.info("Tool suspension caught for tool: {}. Frozen session state [session={}]",
                    ex.getToolName(), sessionUuid);
            return null;
        }
    }

    /**
     * Sends a message as an explicitly-specified user (for WebSocket handlers where
     * the SecurityContext may not be available on the message handler thread).
     * 
     * @param username the authenticated username to associate with this chat
     * @param sessionUuid UUID of the target session
     * @param content the user's message text
     * @param attachmentUuids UUIDs of files to include
     * @param provider the AI provider to use
     * @return the AI's response message
     * @throws IllegalStateException if the session is not found or username doesn't match
     */
    public AiChatMessage sendMessageAsUser(String username, String sessionUuid, String content,
                                           List<String> attachmentUuids, AiProvider provider) {
        // Verify session exists and belongs to this user
        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null) {
            throw new IllegalStateException("AI session not found: " + sessionUuid);
        }
        if (!username.equals(session.username())) {
            throw new IllegalStateException("Access denied for session: " + sessionUuid);
        }

        // Delegate to the existing logic by temporarily establishing SecurityContext if needed
        // For now, we'll inline the send logic to avoid SecurityContext dependency
        return sendMessageWithSession(session, content, attachmentUuids, provider);
    }

    /**
     * Internal helper that performs the send operation with an already-verified session.
     */
    private AiChatMessage sendMessageWithSession(AiSession session, String content,
                                                  List<String> attachmentUuids, AiProvider provider) {
        String sessionUuid = session.uuid();
        
        // Build Spring AI message list from stored history
        List<Message> history = hydrateHistory(session.messages());

        log.info("Chat turn [session={}, history={} msgs, attachments={}, provider={}]",
                sessionUuid, history.size(),
                attachmentUuids == null ? 0 : attachmentUuids.size(), provider);

        // Resolve attachments
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
                }
            }
        }

        // Build effective content
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
                session.name(),
                session.createdAt(),
                session.currentRoundCount(),
                List.copyOf(updated),
                persistedStatus));

            maybeGenerateSessionName(session.uuid());

            log.info("Persisted chat turn [session={}, totalMessages={}]", sessionUuid, updated.size());
            return aiMsg;
        } catch (ToolSuspensionException ex) {
            String simulatedToolCallId = "pending-" + UUID.randomUUID();
            List<ToolCallRef> pendingToolCalls = List.of(
                    new ToolCallRef(simulatedToolCallId, "FUNCTION", ex.getToolName(), ex.getArguments()));

            String justification = ex.getJustification();
            if (justification == null || justification.isBlank()) {
                justification = defaultAuthorizationReason(ex.getToolName());
            }

            String eventId = UUID.randomUUID().toString();
            UiEventFrame promptEvent = new UiEventFrame(
                eventId,
                "PROMPT_REQUIRED",
                "AUTHORIZE_TOOL",
                justification,
                ex.getFormSchema());

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
                    simulatedToolCallId,
                    ex.getToolName());

            List<AiChatMessage> updated = new ArrayList<>(session.messages());
            updated.add(userMsg);
            updated.add(awaiting);
                sessionRepo.save(new AiSession(session.uuid(), session.provider(), session.originMode(), session.username(),
                    session.name(), session.createdAt(), session.currentRoundCount(), List.copyOf(updated), AiSessionStatus.AWAITING_INPUT));

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
                session.name(),
                session.createdAt(),
            session.currentRoundCount(),
                List.copyOf(updated),
            persistedStatus));

        maybeGenerateSessionName(session.uuid());
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

    private boolean hasFullRoundTrip(List<AiChatMessage> messages) {
        boolean sawUser = false;
        boolean sawAi = false;
        for (AiChatMessage message : messages) {
            if ("USER".equals(message.role())) {
                sawUser = true;
            }
            if ("ASSISTANT".equals(message.role()) || "TEXT_RESPONSE".equals(message.role())) {
                sawAi = true;
            }
            if (sawUser && sawAi) {
                return true;
            }
        }
        return false;
    }

    private void generateAndPersistSessionName(String sessionUuid) {
        try {
            AiSession current = sessionRepo.get(sessionUuid);
            if (current == null || !DEFAULT_SESSION_NAME.equalsIgnoreCase(current.name())) {
                return;
            }
            if (!hasFullRoundTrip(current.messages())) {
                return;
            }

            String prompt = buildSessionTitlePrompt(current.messages());
            String candidate = aiService.generate(prompt, AiProvider.GEMINI);
            String sanitized = sanitizeSessionTitle(candidate);

            if (sanitized.isBlank()) {
                return;
            }

            AiSession latest = sessionRepo.get(sessionUuid);
            if (latest == null || !DEFAULT_SESSION_NAME.equalsIgnoreCase(latest.name())) {
                return;
            }

            sessionRepo.save(new AiSession(
                    latest.uuid(),
                    latest.provider(),
                    latest.originMode(),
                    latest.username(),
                    sanitized,
                    latest.createdAt(),
                    latest.currentRoundCount(),
                    latest.messages(),
                    latest.status()));
            log.info("Session title generated [session={}, title={}]", sessionUuid, sanitized);
        } catch (Exception ex) {
            log.warn("Failed to auto-name session [session={}]: {}", sessionUuid, ex.getMessage());
        }
    }

    private String buildSessionTitlePrompt(List<AiChatMessage> messages) {
        StringBuilder transcript = new StringBuilder();
        int count = 0;
        for (AiChatMessage message : messages) {
            if (!("USER".equals(message.role())
                    || "ASSISTANT".equals(message.role())
                    || "TEXT_RESPONSE".equals(message.role()))) {
                continue;
            }
            String speaker = "USER".equals(message.role()) ? "User" : "Assistant";
            String content = message.content() == null ? "" : message.content().trim();
            if (content.isBlank()) {
                continue;
            }
            if (content.length() > 200) {
                content = content.substring(0, 200);
            }
            transcript.append(speaker).append(": ").append(content).append("\n");
            count++;
            if (count >= 6) {
                break;
            }
        }

        return """
                You are naming a chat conversation.
                Based on the transcript below, return a short title of 2 to 5 words.
                Rules:
                - Plain text only.
                - No quotes.
                - No punctuation at the end.

                Transcript:
                """ + transcript;
    }

    private String sanitizeSessionTitle(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.trim().replaceAll("[\\r\\n]+", " ");
        cleaned = cleaned.replaceAll("^\"+|\"+$", "").trim();
        if (cleaned.length() > 60) {
            cleaned = cleaned.substring(0, 60).trim();
        }
        if (cleaned.isBlank()) {
            return "";
        }
        return cleaned;
    }

}
