package sh.vork.ai.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.AiProvider;
import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiChatMessage.AttachmentRef;
import sh.vork.ai.entity.AiChatMessage.ToolCallRef;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.lifecycle.AgentTemplateSeeder;
import sh.vork.ai.protocol.StructuredAgentResponse;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.SearchQuery;
import sh.vork.orm.SortOrder;
import sh.vork.scheduling.service.SystemBackgroundAuthentication;
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

    private final DatabaseRepository<AiSession>     sessionRepo;
    private final DatabaseRepository<AgentTemplate> agentTemplateRepo;
    private final AiOrchestrationService            aiService;
    private final FileStorageService            fileStorageService;
    private final SimpMessagingTemplate         messaging;
    private final ObjectMapper                  objectMapper;
    private final SystemNotificationService     systemNotificationService;
    private final Executor                      aiBackgroundExecutor;

    private static final String DEFAULT_SESSION_NAME = "Untitled";

    @Autowired
    public ChatService(DatabaseRepository<AiSession> aiSessionRepository,
                       DatabaseRepository<AgentTemplate> agentTemplateRepository,
                       AiOrchestrationService aiOrchestrationService,
                       FileStorageService fileStorageService,
                       SimpMessagingTemplate messaging,
                       ObjectMapper objectMapper,
                       List<ToolCallback> toolCallbacks,
                       SystemNotificationService systemNotificationService,
                       @Qualifier("aiBackgroundExecutor") Executor aiBackgroundExecutor) {
        this.sessionRepo = aiSessionRepository;
        this.agentTemplateRepo = agentTemplateRepository;
        this.aiService = aiOrchestrationService;
        this.fileStorageService = fileStorageService;
        this.messaging = messaging;
        this.objectMapper = objectMapper;
        this.systemNotificationService = systemNotificationService;
        this.aiBackgroundExecutor = aiBackgroundExecutor;
    }

    /**
     * Returns the existing {@link AiSession} for the given HTTP session ID,
     * or creates a new one bound to {@code provider}.
     */
    public AiSession getOrCreateSession(String httpSessionId, AiProvider provider, String modelId) {
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
            username, DEFAULT_SESSION_NAME, System.currentTimeMillis(), 0, List.of(),
            AiSession.defaultEnvironmentVariables(), AiSessionStatus.RUNNING,
            AgentTemplateSeeder.UUID_CONCIERGE, modelId, null, null, null);
        sessionRepo.save(session);
        log.info("Created HTTP session-bound AI session [id={}, provider={}, model={}, user={}]",
            httpSessionId, provider, modelId, username);
        return session;
    }

    public AiSession getOrCreateSession(String httpSessionId, AiProvider provider) {
        return getOrCreateSession(httpSessionId, provider, null);
    }

    public AiSession createNewSession(AiProvider provider, String modelId) {
        String username = resolveUsername();
        String uuid = UUID.randomUUID().toString();
        AiSession session = new AiSession(uuid, provider.name(), SessionOriginMode.WEB,
            username, DEFAULT_SESSION_NAME, System.currentTimeMillis(), 0, List.of(),
            AiSession.defaultEnvironmentVariables(), AiSessionStatus.RUNNING,
            AgentTemplateSeeder.UUID_CONCIERGE, modelId, null, null, null);
        sessionRepo.save(session);
        log.info("Created AI session [id={}, provider={}, model={}, user={}]", uuid, provider, modelId, username);
        return session;
    }

    public AiSession createNewSession(AiProvider provider) {
        return createNewSession(provider, null);
    }

    /**
     * Creates a new AI session for a Telegram user.
     *
     * @param username  the authenticated Vork username (from UserNotificationMedia)
     * @param configId  UUID of the NotificationProviderConfig (which bot received the message)
     * @param chatId    Telegram chat ID (used to reply)
     * @param botToken  Telegram bot token (stored in session env so the consumer can reply)
     * @return the newly created session
     */
    public AiSession createTelegramSession(String username, String configId,
                                            String chatId, String botToken, String providerName) {
        String uuid = UUID.randomUUID().toString();
        Map<String, String> env = new java.util.HashMap<>(AiSession.defaultEnvironmentVariables());
        env.put("TELEGRAM_CONFIG_ID", configId);
        env.put("TELEGRAM_CHAT_ID",   chatId);
        env.put("TELEGRAM_BOT_TOKEN", botToken);
        AiSession session = new AiSession(uuid, providerName,
                SessionOriginMode.TELEGRAM, username, DEFAULT_SESSION_NAME,
                System.currentTimeMillis(), 0, List.of(),
                java.util.Collections.unmodifiableMap(env),
                AiSessionStatus.RUNNING, AgentTemplateSeeder.UUID_CONCIERGE, null, null, null, null);
        sessionRepo.save(session);
        log.info("Created Telegram session [id={}, user={}, chatId={}]", uuid, username, chatId);
        return session;
    }

    public AiSession createSlackSession(String username, String configId,
                                         String channelId, String botToken, String providerName) {
        String uuid = UUID.randomUUID().toString();
        Map<String, String> env = new java.util.HashMap<>(AiSession.defaultEnvironmentVariables());
        env.put("SLACK_CONFIG_ID",  configId);
        env.put("SLACK_CHANNEL_ID", channelId);
        env.put("SLACK_BOT_TOKEN",  botToken);
        AiSession session = new AiSession(uuid, providerName,
                SessionOriginMode.SLACK, username, DEFAULT_SESSION_NAME,
                System.currentTimeMillis(), 0, List.of(),
                java.util.Collections.unmodifiableMap(env),
                AiSessionStatus.RUNNING, AgentTemplateSeeder.UUID_CONCIERGE, null, null, null, null);
        sessionRepo.save(session);
        log.info("Created Slack session [id={}, user={}, channelId={}]", uuid, username, channelId);
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
            AiSession.defaultEnvironmentVariables(),
                session.status(),
                session.activeAgentTemplateId(),
                session.modelId(),
                session.skillStack(),
                session.sessionSkillUuids(),
                session.sessionToolIds());
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
        return sendMessage(sessionUuid, content, attachmentUuids, provider, true);
    }

    /**
     * Sends content to the AI and optionally persists the triggering USER message.
     *
     * @param persistUserMessage when false, the invocation USER message is used only
     *                           to trigger model execution and is not stored in chat history
     */
    public AiChatMessage sendMessage(String sessionUuid, String content,
                                     List<String> attachmentUuids, AiProvider provider,
                                     boolean persistUserMessage) {
        AiSession session = getSessionForCurrentUser(sessionUuid);

        // Build Spring AI message list from stored history.
        // When a skill frame is active, only messages since the skill started are included
        // so the skill AI does not see unrelated background-job context.
        List<Message> history = buildHistoryForSession(session);

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

        if (log.isDebugEnabled()) {
            log.debug("AI call prompt [session={}]: {}", sessionUuid,
                    effectiveContent.length() > 500 ? effectiveContent.substring(0, 500) + "…" : effectiveContent);
            StringBuilder historyDump = new StringBuilder();
            for (int i = 0; i < history.size(); i++) {
                Message m = history.get(i);
                String text = m.getText();
                historyDump.append("  [").append(i).append("] ").append(m.getMessageType())
                        .append(": ").append(text == null ? "<null>" : text.length() > 200 ? text.substring(0, 200) + "…" : text)
                        .append("\n");
            }
            log.debug("AI call history [{} msgs, session={}]:\n{}", history.size(), sessionUuid, historyDump);
        }

        ToolExecutionContext.bindSessionUuid(sessionUuid);
        ToolExecutionContext.hydrate(session.environmentVariables());
        try {
            return executeAgentLoop(session, history, effectiveContent, media, provider, userMsg, refs,
                    persistUserMessage);
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

                try (MDC.MDCCloseable _ = MDC.putCloseable("sessionUuid", sessionUuid);
                 MDC.MDCCloseable _ = MDC.putCloseable("eventId", eventId)) {
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
            if (persistUserMessage) {
                updated.add(userMsg);
            }
            updated.add(awaiting);
                // Reload session to capture agent stack changes made by executeAgentLoop
                // (e.g. a delegation that pushed a sub-agent before the suspension fired).
                AiSession frozenSession = sessionRepo.get(sessionUuid);
                if (frozenSession == null) { frozenSession = session; }
                sessionRepo.save(new AiSession(frozenSession.uuid(), frozenSession.provider(), frozenSession.originMode(), frozenSession.username(),
                    frozenSession.name(), frozenSession.createdAt(), frozenSession.currentRoundCount(), List.copyOf(updated),
                    frozenSession.environmentVariables(), AiSessionStatus.AWAITING_INPUT, frozenSession.activeAgentTemplateId(),
                    frozenSession.modelId(), frozenSession.skillStack(), frozenSession.sessionSkillUuids(), frozenSession.sessionToolIds()));

                if (provider == AiProvider.BACKGROUND_SCHEDULER) {
                systemNotificationService.notifyOfflineOperator(ex.getToolName(), ex.getArguments(), sessionUuid, eventId);
                }

                messaging.convertAndSend("/topic/chat/" + sessionUuid, promptEvent);

            log.info("Tool suspension caught for tool: {}. Frozen session state [session={}]",
                    ex.getToolName(), sessionUuid);
            return null;
        } finally {
            ToolExecutionContext.clear();
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

        // Ensure tools running on non-web threads (Telegram, background) can resolve the principal
        SecurityContext prevCtx = SecurityContextHolder.getContext();
        boolean needsCtx = prevCtx.getAuthentication() == null
                || prevCtx.getAuthentication().getName() == null
                || prevCtx.getAuthentication().getName().isBlank();
        if (needsCtx) {
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new SystemBackgroundAuthentication(username));
            SecurityContextHolder.setContext(ctx);
        }
        try {
            return sendMessageWithSession(session, content, attachmentUuids, provider);
        } finally {
            if (needsCtx) {
                SecurityContextHolder.setContext(prevCtx);
            }
        }
    }

    /**
     * Internal helper that performs the send operation with an already-verified session.
     */
    private AiChatMessage sendMessageWithSession(AiSession session, String content,
                                                  List<String> attachmentUuids, AiProvider provider) {
        String sessionUuid = session.uuid();
        // Fall back to the provider stored in the session when none is supplied by the caller
        AiProvider effectiveProvider = provider;
        if (effectiveProvider == null && session.provider() != null && !session.provider().isBlank()) {
            try { effectiveProvider = AiProvider.valueOf(session.provider()); }
            catch (IllegalArgumentException ignored) {}
        }
        final AiProvider resolvedProvider = effectiveProvider;
        ToolExecutionContext.bindSessionUuid(sessionUuid);
        ToolExecutionContext.hydrate(session.environmentVariables());
        try {
            // Skill frames get a filtered view of history; normal sessions get the full history.
            List<Message> history = buildHistoryForSession(session);

            log.info("Chat turn [session={}, history={} msgs, attachments={}, provider={}]",
                    sessionUuid, history.size(),
                    attachmentUuids == null ? 0 : attachmentUuids.size(), resolvedProvider);

            List<AttachmentRef> refs = new ArrayList<>();
            List<Media> media = new ArrayList<>();
            List<String> textParts = new ArrayList<>();

            if (attachmentUuids != null) {
                for (String fileUuid : attachmentUuids) {
                    StoredFile meta = fileStorageService.getMetadata(fileUuid);
                    if (meta == null) {
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
                return executeAgentLoop(session, history, effectiveContent, media, resolvedProvider, userMsg, refs,
                    true);
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

                try (MDC.MDCCloseable _ = MDC.putCloseable("sessionUuid", sessionUuid);
                     MDC.MDCCloseable _ = MDC.putCloseable("eventId", eventId)) {
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
                // Reload session to capture agent stack changes made by executeAgentLoop
                // (e.g. a delegation that pushed a sub-agent before the suspension fired).
                AiSession frozenSession = sessionRepo.get(sessionUuid);
                if (frozenSession == null) { frozenSession = session; }
                sessionRepo.save(new AiSession(
                        frozenSession.uuid(),
                        frozenSession.provider(),
                        frozenSession.originMode(),
                        frozenSession.username(),
                        frozenSession.name(),
                        frozenSession.createdAt(),
                        frozenSession.currentRoundCount(),
                        List.copyOf(updated),
                        frozenSession.environmentVariables(),
                        AiSessionStatus.AWAITING_INPUT,
                        frozenSession.activeAgentTemplateId(),
                        frozenSession.modelId(),
                        frozenSession.skillStack(),
                        frozenSession.sessionSkillUuids(),
                        frozenSession.sessionToolIds()));

                if (provider == AiProvider.BACKGROUND_SCHEDULER) {
                    systemNotificationService.notifyOfflineOperator(ex.getToolName(), ex.getArguments(), sessionUuid, eventId);
                }

                messaging.convertAndSend("/topic/chat/" + sessionUuid, promptEvent);

                log.info("Tool suspension caught for tool: {}. Frozen session state [session={}]",
                        ex.getToolName(), sessionUuid);
                return null;
            }
        } finally {
            ToolExecutionContext.clear();
        }
    }

    // -------------------------------------------------------------------------
    // Structured agent response loop
    // -------------------------------------------------------------------------

    /**
     * Executes the agent handoff state machine for a single user turn.
     *
     * <p>Each iteration calls the model and parses a {@link StructuredAgentResponse}.
     * The loop continues until a FINISHED_TURN at stack depth&nbsp;1 or the
     * safety iteration limit is reached.
     */
    private AiChatMessage executeAgentLoop(
            AiSession initialSession,
            List<Message> history,
            String initialPrompt,
            List<Media> media,
            AiProvider provider,
            AiChatMessage userMsg,
            List<AttachmentRef> refs,
            boolean persistUserMessage) {

        String sessionUuid = initialSession.uuid();
        String currentPrompt = initialPrompt;
        final int MAX_ITERATIONS = 15;
        List<AiChatMessage> transitionMsgs = new ArrayList<>();

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            AiSession currentSession = sessionRepo.get(sessionUuid);
            if (currentSession == null) {
                currentSession = initialSession;
            }
            log.debug("Agent loop iteration {} [session={}, agent={}]",
                    i, sessionUuid, currentSession.getActiveAgentTemplateId());

            String rawResponse;
            try {
                rawResponse = (i == 0 && !media.isEmpty())
                        ? safeGenerateWithHistoryAndMedia(history, currentPrompt, media, provider)
                        : safeGenerateWithHistory(history, currentPrompt, provider);
            } catch (sh.vork.skill.SkillActivatedException ex) {
                log.info("Skill activated in agent loop [session={}, skill={}, uuid={}]",
                        sessionUuid, ex.getSkillName(), ex.getSkillUuid());
                String skillOutput = executeSkillSubLoop(sessionUuid, history, ex, provider);
                String skillResultMsg = "Skill '" + ex.getSkillName() + "' completed. Output: " + skillOutput;
                history.add(new AssistantMessage(skillResultMsg));
                transitionMsgs.add(new AiChatMessage(UUID.randomUUID().toString(), "ASSISTANT",
                        skillResultMsg, System.currentTimeMillis(), null));
                currentPrompt = "The skill completed successfully. Continue based on the output above.";
                continue;
            }

            StructuredAgentResponse structured = parseStructuredResponse(rawResponse);
            log.debug("Agent loop response [session={}, status={}, iteration={}]",
                    sessionUuid, structured.status(), i);

            if ("CONTINUE_TURN".equals(structured.status())) {
                String progressText = structured.textResponse() != null && !structured.textResponse().isBlank()
                        ? structured.textResponse() : rawResponse;
                UiEventFrame progressEvent = new UiEventFrame(UUID.randomUUID().toString(),
                        "TEXT_RESPONSE", "CHAT_OUTPUT", progressText, null);
                messaging.convertAndSend("/topic/chat/" + sessionUuid, progressEvent);
                transitionMsgs.add(new AiChatMessage(UUID.randomUUID().toString(), "ASSISTANT",
                        progressText, System.currentTimeMillis(), null));
                history.add(new AssistantMessage(progressText));
                currentPrompt = "Continue executing the task. Use available tools as needed.";
                log.debug("CONTINUE_TURN progress broadcast [session={}, iteration={}]", sessionUuid, i);
                continue;
            }

            if ("DELEGATE_TURN".equals(structured.status())) {
                String targetId = resolveAgentByName(structured.targetAgent(), sessionUuid);
                if (targetId != null) {
                    AiSession current = sessionRepo.get(sessionUuid);
                    if (current == null) {
                        current = currentSession;
                    }
                    sessionRepo.save(new AiSession(current.uuid(), current.provider(), current.originMode(),
                            current.username(), current.name(), current.createdAt(), current.currentRoundCount(),
                            current.messages(), current.environmentVariables(), current.status(), targetId,
                            current.modelId(), current.skillStack(), current.sessionSkillUuids(), current.sessionToolIds()));
                    log.info("Agent switched [session={}, target={}, newAgent={}]",
                            sessionUuid, structured.targetAgent(), targetId);

                    broadcastAndAccumulateTransition(sessionUuid,
                            "Changed to " + structured.targetAgent(), transitionMsgs);
                    // Notify the frontend to update the agent dropdown
                    messaging.convertAndSend("/topic/chat/" + sessionUuid,
                            new UiEventFrame(UUID.randomUUID().toString(),
                                    "AGENT_SWITCH", "AGENT_SWITCH", targetId, null));

                    history.add(new AssistantMessage(
                            structured.textResponse() != null ? structured.textResponse() : rawResponse));
                    currentPrompt = structured.delegationInstructions() != null
                            ? structured.delegationInstructions()
                            : "Proceed with the assigned task.";
                    continue;
                }
                log.warn("Agent loop: target agent not found, treating as FINISHED_TURN [target={}, session={}]",
                        structured.targetAgent(), sessionUuid);
            }

            if ("SWITCH_AGENT".equals(structured.status())) {
                String targetId = resolveAgentByName(structured.targetAgent(), sessionUuid);
                if (targetId != null) {
                    AiSession current = sessionRepo.get(sessionUuid);
                    if (current == null) {
                        current = currentSession;
                    }
                    sessionRepo.save(new AiSession(current.uuid(), current.provider(), current.originMode(),
                            current.username(), current.name(), current.createdAt(), current.currentRoundCount(),
                            current.messages(), current.environmentVariables(), current.status(), targetId,
                            current.modelId(), current.skillStack(), current.sessionSkillUuids(), current.sessionToolIds()));
                    String agentDisplayName = resolveAgentNameById(targetId);
                    broadcastAndAccumulateTransition(sessionUuid,
                            "Changed to " + agentDisplayName, transitionMsgs);
                    messaging.convertAndSend("/topic/chat/" + sessionUuid,
                            new UiEventFrame(UUID.randomUUID().toString(),
                                    "AGENT_SWITCH", "AGENT_SWITCH", targetId, null));
                    log.info("SWITCH_AGENT: active agent changed [session={}, target={}, newAgentId={}]",
                            sessionUuid, structured.targetAgent(), targetId);
                } else {
                    log.warn("SWITCH_AGENT: target agent not found, treating as FINISHED_TURN [target={}, session={}]",
                            structured.targetAgent(), sessionUuid);
                }
                // Fall through to FINISHED_TURN — the AI's textResponse is returned to the user
            }

            // FINISHED_TURN (or unresolvable DELEGATE_TURN / SWITCH_AGENT falls through here) — always terminal
            AiSession latest = sessionRepo.get(sessionUuid);
            if (latest == null) {
                latest = currentSession;
            }

                String finalText = structured.textResponse() != null && !structured.textResponse().isBlank()
                    ? structured.textResponse()
                    : rawResponse;
                if (latest.originMode() == SessionOriginMode.BACKGROUND
                    && latest.status() == AiSessionStatus.COMPLETED
                    && latest.environmentVariables() != null) {
                String completionReport = latest.environmentVariables().get("JOB_COMPLETION_REPORT");
                if (completionReport != null && !completionReport.isBlank()) {
                    finalText = completionReport;
                }
                }

                AiChatMessage aiMsg = new AiChatMessage(
                    UUID.randomUUID().toString(), "ASSISTANT",
                    finalText, System.currentTimeMillis(),
                    refs.isEmpty() ? null : Collections.unmodifiableList(refs));

            List<AiChatMessage> updated = new ArrayList<>(latest.messages());
            if (persistUserMessage) {
                updated.add(userMsg);
            }
            updated.addAll(transitionMsgs);
            updated.add(aiMsg);

            // If a skill frame is active, mark the session COMPLETED so that
            // BackgroundOrchestrationEngine.executeSkillTurn can detect that the
            // skill has finished and exit its loop cleanly.
            boolean hasActiveSkillFrame = latest.skillStack() != null && !latest.skillStack().isEmpty();
                boolean isBackgroundSession = latest.originMode() == SessionOriginMode.BACKGROUND;
                AiSessionStatus persistedStatus = hasActiveSkillFrame
                    ? AiSessionStatus.COMPLETED
                    : (isBackgroundSession
                    ? resolveStatusForReplyPersistence(sessionUuid, AiSessionStatus.COMPLETED)
                    : resolveStatusForReplyPersistence(sessionUuid, AiSessionStatus.RUNNING));
            sessionRepo.save(new AiSession(
                    latest.uuid(), latest.provider(), latest.originMode(),
                    latest.username(), latest.name(), latest.createdAt(),
                    latest.currentRoundCount(), List.copyOf(updated),
                    latest.environmentVariables(), persistedStatus,
                    latest.activeAgentTemplateId(), latest.modelId(),
                    latest.skillStack(), latest.sessionSkillUuids(), latest.sessionToolIds()));

            maybeGenerateSessionName(sessionUuid);
            log.info("Agent loop completed [session={}, iterations={}]", sessionUuid, i + 1);
            return aiMsg;
        }

        // Safety: max iterations exhausted
        log.warn("Agent loop exhausted max iterations [session={}, max={}]", sessionUuid, MAX_ITERATIONS);
        String timeoutMsg = "Processing required too many steps and was interrupted. Please try again.";
        AiChatMessage aiMsg = new AiChatMessage(
                UUID.randomUUID().toString(), "ASSISTANT",
                timeoutMsg, System.currentTimeMillis(), null);
        AiSession latest = sessionRepo.get(sessionUuid);
        if (latest == null) {
            latest = initialSession;
        }
        List<AiChatMessage> updated = new ArrayList<>(latest.messages());
        if (persistUserMessage) {
            updated.add(userMsg);
        }
        updated.add(aiMsg);
        sessionRepo.save(new AiSession(
                latest.uuid(), latest.provider(), latest.originMode(),
                latest.username(), latest.name(), latest.createdAt(),
                latest.currentRoundCount(), List.copyOf(updated),
                latest.environmentVariables(), AiSessionStatus.RUNNING,
                latest.activeAgentTemplateId(), latest.modelId(),
                latest.skillStack(), latest.sessionSkillUuids(), latest.sessionToolIds()));
        return aiMsg;
    }

    /**
     * Switches the active agent for a session by agent name.
     * Resolves the agent template by display name and updates the session.
     *
     * @param sessionUuid the session to update
     * @param agentName   display name of the target {@link sh.vork.ai.agent.AgentTemplate}
     * @return the UUID of the newly active agent template, or {@code null} if not found
     */
    public String switchActiveAgentByName(String sessionUuid, String agentName) {
        String targetId = resolveAgentByName(agentName, sessionUuid);
        if (targetId == null) {
            log.warn("switchActiveAgentByName: agent not found [name={}, session={}]", agentName, sessionUuid);
            return null;
        }
        return applyAgentSwitch(sessionUuid, targetId);
    }

    /**
     * Switches the active agent for a session by template UUID.
     * Validates that the session belongs to the current user before switching.
     *
     * @param sessionUuid      the session to update
     * @param agentTemplateId  UUID of the target {@link sh.vork.ai.agent.AgentTemplate}
     * @return the UUID of the newly active agent template, or {@code null} if not found / not owned
     */
    public String switchActiveAgentById(String sessionUuid, String agentTemplateId) {
        if (agentTemplateRepo.get(agentTemplateId) == null) {
            log.warn("switchActiveAgentById: template not found [id={}, session={}]", agentTemplateId, sessionUuid);
            return null;
        }
        return applyAgentSwitch(sessionUuid, agentTemplateId);
    }

    /**
     * Updates the provider and model stored in a session.
     * Validates that the session belongs to the current user.
     */
    public AiSession updateSessionModel(String sessionUuid, String provider, String modelId) {
        AiSession session = getSessionForCurrentUser(sessionUuid);
        AiSession updated = new AiSession(
                session.uuid(), provider != null ? provider : session.provider(),
                session.originMode(), session.username(), session.name(),
                session.createdAt(), session.currentRoundCount(), session.messages(),
                session.environmentVariables(), session.status(),
                session.activeAgentTemplateId(), modelId, session.skillStack(),
                session.sessionSkillUuids(), session.sessionToolIds());
        sessionRepo.save(updated);
        log.info("Session model updated [session={}, provider={}, model={}]", sessionUuid, provider, modelId);
        return updated;
    }

    /**
     * Returns all configured {@link sh.vork.ai.agent.AgentTemplate} records.
     */
    public List<AgentTemplate> listAgentTemplates() {
        try (var stream = agentTemplateRepo.list(0, Integer.MAX_VALUE)) {
            return stream.toList();
        }
    }

    /** Adds a skill UUID to the session's {@code sessionSkillUuids} list. */
    public AiSession addSessionSkill(String sessionUuid, String skillUuid) {
        AiSession session = getSessionForCurrentUser(sessionUuid);
        if (session.sessionSkillUuids().contains(skillUuid)) {
            return session; // already present — idempotent
        }
        List<String> updated = new ArrayList<>(session.sessionSkillUuids());
        updated.add(skillUuid);
        AiSession saved = new AiSession(
                session.uuid(), session.provider(), session.originMode(),
                session.username(), session.name(), session.createdAt(),
                session.currentRoundCount(), session.messages(),
                session.environmentVariables(), session.status(),
                session.activeAgentTemplateId(), session.modelId(),
                session.skillStack(), List.copyOf(updated), session.sessionToolIds());
        sessionRepo.save(saved);
        log.info("Session skill added [session={}, skill={}]", sessionUuid, skillUuid);
        return saved;
    }

    /** Removes a skill UUID from the session's {@code sessionSkillUuids} list. */
    public AiSession removeSessionSkill(String sessionUuid, String skillUuid) {
        AiSession session = getSessionForCurrentUser(sessionUuid);
        List<String> updated = session.sessionSkillUuids().stream()
                .filter(id -> !id.equals(skillUuid))
                .toList();
        AiSession saved = new AiSession(
                session.uuid(), session.provider(), session.originMode(),
                session.username(), session.name(), session.createdAt(),
                session.currentRoundCount(), session.messages(),
                session.environmentVariables(), session.status(),
                session.activeAgentTemplateId(), session.modelId(),
                session.skillStack(), List.copyOf(updated), session.sessionToolIds());
        sessionRepo.save(saved);
        log.info("Session skill removed [session={}, skill={}]", sessionUuid, skillUuid);
        return saved;
    }

    /** Adds a tool bean ID to the session's {@code sessionToolIds} list. */
    public AiSession addSessionTool(String sessionUuid, String toolId) {
        AiSession session = getSessionForCurrentUser(sessionUuid);
        if (session.sessionToolIds().contains(toolId)) {
            return session;
        }
        List<String> updated = new ArrayList<>(session.sessionToolIds());
        updated.add(toolId);
        AiSession saved = new AiSession(
                session.uuid(), session.provider(), session.originMode(),
                session.username(), session.name(), session.createdAt(),
                session.currentRoundCount(), session.messages(),
                session.environmentVariables(), session.status(),
                session.activeAgentTemplateId(), session.modelId(),
                session.skillStack(), session.sessionSkillUuids(), List.copyOf(updated));
        sessionRepo.save(saved);
        log.info("Session tool added [session={}, tool={}]", sessionUuid, toolId);
        return saved;
    }

    /** Removes a tool bean ID from the session's {@code sessionToolIds} list. */
    public AiSession removeSessionTool(String sessionUuid, String toolId) {
        AiSession session = getSessionForCurrentUser(sessionUuid);
        List<String> updated = session.sessionToolIds().stream()
                .filter(id -> !id.equals(toolId))
                .toList();
        AiSession saved = new AiSession(
                session.uuid(), session.provider(), session.originMode(),
                session.username(), session.name(), session.createdAt(),
                session.currentRoundCount(), session.messages(),
                session.environmentVariables(), session.status(),
                session.activeAgentTemplateId(), session.modelId(),
                session.skillStack(), session.sessionSkillUuids(), List.copyOf(updated));
        sessionRepo.save(saved);
        log.info("Session tool removed [session={}, tool={}]", sessionUuid, toolId);
        return saved;
    }


     /*
     * <p>The skill's context (tools + system prompt) is stored in the top
     * {@link sh.vork.skill.SkillFrame} on the session's {@code skillStack}.
     * The sub-loop calls the model in a loop until either:
     * <ul>
     *   <li>The model returns {@code FINISHED_TURN} — the primary exit path (pops frame, uses textResponse), or</li>
     *   <li>The skill's stack frame is popped by a legacy tool call (backward-compat fallback), or</li>
     *   <li>A {@link ToolSuspensionException} is thrown (propagates to parent session freeze), or</li>
     *   <li>Maximum iterations are exhausted.</li>
     * </ul>
     */
    public String executeSkillSubLoop(String sessionUuid, List<Message> history,
                                       sh.vork.skill.SkillActivatedException ex,
                                       AiProvider provider) {
        final int MAX_SKILL_ITERATIONS = 20;
        final int MAX_SKILL_DEPTH = 5;
        String currentPrompt = ex.getInitialPrompt();

        broadcastAndPersistSkillEvent(sessionUuid, "Running skill: " + ex.getSkillName());

        // Hard-reject before starting the sub-loop if any tools the skill requires are not registered.
        // Without this check the model would be handed no usable tools, might hallucinate a result,
        // and the caller would get garbage back with no indication of what went wrong.
        AiSession sessionAtStart = sessionRepo.get(sessionUuid);
        if (sessionAtStart != null && sessionAtStart.skillStack() != null && !sessionAtStart.skillStack().isEmpty()) {
            sh.vork.skill.SkillFrame frame = sessionAtStart.skillStack().getLast();
            List<String> unresolvable = aiService.findUnresolvableTools(
                    frame.allowedTools() != null ? frame.allowedTools() : List.of());
            if (!unresolvable.isEmpty()) {
                List<sh.vork.skill.SkillFrame> poppedStack = new ArrayList<>(sessionAtStart.skillStack());
                poppedStack.removeLast();
                sessionRepo.save(new AiSession(
                        sessionAtStart.uuid(), sessionAtStart.provider(), sessionAtStart.originMode(),
                        sessionAtStart.username(), sessionAtStart.name(), sessionAtStart.createdAt(),
                        sessionAtStart.currentRoundCount(), sessionAtStart.messages(),
                        sessionAtStart.environmentVariables(), sessionAtStart.status(),
                        sessionAtStart.getActiveAgentTemplateId(), sessionAtStart.modelId(),
                        List.copyOf(poppedStack), sessionAtStart.sessionSkillUuids(), sessionAtStart.sessionToolIds()));
                broadcastAndPersistSkillEvent(sessionUuid, "Skill failed: " + ex.getSkillName());
                log.warn("Skill aborted — required tools not available [session={}, skill={}, missing={}]",
                        sessionUuid, ex.getSkillName(), unresolvable);
                return "I cannot execute the skill '" + ex.getSkillName() + "' because it requires tools "
                        + "that are not configured: " + unresolvable + ". "
                        + "Please ensure these tools are available and try again.";
            }
        }

        // Compute the stack depth at which THIS skill's frame has been popped, so that the
        // skillComplete check works correctly for nested skills. At sub-loop entry the frame is
        // already on the stack (pushed by SkillService.executeSkill). The skill is done when the
        // stack shrinks back to (initialDepth - 1). Using isEmpty() is wrong for nested skills
        // because the parent frame(s) keep the stack non-empty.
        int skillCompleteDepth = (sessionAtStart != null && sessionAtStart.skillStack() != null)
                ? sessionAtStart.skillStack().size() - 1 : 0;

        // Skills are sandboxed executions. Use an empty history so prior conversation turns
        // (including results from previous skill runs on the same targets) cannot bleed into
        // the skill sub-loop and cause the model to recycle stale data instead of running
        // the skill's actual commands.
        List<Message> skillHistory = new ArrayList<>();

        for (int i = 0; i < MAX_SKILL_ITERATIONS; i++) {
            // Re-bind the session context at the top of every iteration. SecuredToolCallback
            // previously cleared ToolExecutionContext in its finally block after each tool
            // execution; without this rebind the second+ iterations would see sessionUuid=null
            // and bypass all skill-frame restrictions. With SecuredToolCallback now preserving
            // the context when it was already bound, this serves as an additional safety net.
            ToolExecutionContext.bindSessionUuid(sessionUuid);
            log.debug("Skill sub-loop iteration {} [session={}, skill={}]", i, sessionUuid, ex.getSkillName());

            // safeGenerateWithHistory reads the session and applies the skill frame's tools/prompt
            String rawResponse;
            try {
                rawResponse = safeGenerateWithHistory(skillHistory, currentPrompt, provider);
            } catch (sh.vork.skill.SkillActivatedException nestedEx) {
                // A sub-skill was invoked from within this skill sub-loop.
                // Check depth before recursing so we can't blow the call stack.
                AiSession depthCheck = sessionRepo.get(sessionUuid);
                int currentDepth = (depthCheck != null && depthCheck.skillStack() != null)
                        ? depthCheck.skillStack().size() : 0;
                if (currentDepth >= MAX_SKILL_DEPTH) {
                    log.error("Max skill depth ({}) reached — aborting nested skill [session={}, outer={}, inner={}]",
                            MAX_SKILL_DEPTH, sessionUuid, ex.getSkillName(), nestedEx.getSkillName());
                    // Pop the spurious frame
                    AiSession current = sessionRepo.get(sessionUuid);
                    if (current != null && current.skillStack() != null && current.skillStack().size() > 1) {
                        List<sh.vork.skill.SkillFrame> poppedStack = new ArrayList<>(current.skillStack());
                        poppedStack.removeLast();
                        sessionRepo.save(new AiSession(
                                current.uuid(), current.provider(), current.originMode(),
                                current.username(), current.name(), current.createdAt(),
                                current.currentRoundCount(), current.messages(),
                                current.environmentVariables(), current.status(),
                                current.activeAgentTemplateId(), current.modelId(),
                                List.copyOf(poppedStack), current.sessionSkillUuids(), current.sessionToolIds()));
                    }
                    broadcastAndPersistSkillEvent(sessionUuid, "Skill error: " + nestedEx.getSkillName());
                    String errMsg = "Error: maximum skill nesting depth (" + MAX_SKILL_DEPTH + ") reached. Sub-skill aborted.";
                    skillHistory.add(new AssistantMessage(errMsg));
                    currentPrompt = "The nested skill could not run (max depth). Continue without it.";
                    continue;
                }
                log.debug("Nested skill activated — entering recursive sub-loop [session={}, outer={}, inner={}, depth={}]",
                        sessionUuid, ex.getSkillName(), nestedEx.getSkillName(), currentDepth);
                String nestedResult = executeSkillSubLoop(sessionUuid, skillHistory, nestedEx, provider);
                skillHistory.add(new AssistantMessage(
                        "Skill '" + nestedEx.getSkillName() + "' result: " + nestedResult));
                currentPrompt = "The nested skill completed. Continue with your task.";
                continue;
            }

            // Check if the skill frame was popped by a legacy completeSkillExecution call during this turn.
            // Skills now exit via FINISHED_TURN, but this guard remains as a safety fallback.
            AiSession afterTurn = sessionRepo.get(sessionUuid);
            boolean skillComplete = afterTurn == null
                    || afterTurn.skillStack() == null
                    || afterTurn.skillStack().size() <= skillCompleteDepth;

            if (skillComplete) {
                // Legacy path: frame was popped by a completeSkillExecution tool call.
                Object storedOutput = sh.vork.ai.context.ToolExecutionContext.get("__skill_output__");
                String output = storedOutput != null ? storedOutput.toString() : rawResponse;
                log.info("Skill sub-loop complete via tool call (legacy) [session={}, skill={}, iterations={}]",
                        sessionUuid, ex.getSkillName(), i + 1);
                broadcastAndPersistSkillEvent(sessionUuid, "Skill completed: " + ex.getSkillName());
                return output;
            }

            StructuredAgentResponse structured = parseStructuredResponse(rawResponse);
            if ("FINISHED_TURN".equals(structured.status())) {
                // Primary exit path: the skill returned FINISHED_TURN with its output in textResponse.
                String output = structured.textResponse() != null ? structured.textResponse() : rawResponse;

                // Pop the skill frame from the stack so the parent agent loop can continue.
                AiSession current = sessionRepo.get(sessionUuid);
                if (current != null && current.skillStack() != null && !current.skillStack().isEmpty()) {
                    List<sh.vork.skill.SkillFrame> poppedStack = new ArrayList<>(current.skillStack());
                    poppedStack.removeLast();
                    sessionRepo.save(new AiSession(
                            current.uuid(), current.provider(), current.originMode(),
                            current.username(), current.name(), current.createdAt(),
                            current.currentRoundCount(), current.messages(),
                            current.environmentVariables(), current.status(),
                            current.activeAgentTemplateId(), current.modelId(),
                            List.copyOf(poppedStack), current.sessionSkillUuids(), current.sessionToolIds()));
                }
                log.info("Skill sub-loop complete via FINISHED_TURN [session={}, skill={}, iterations={}, outputLength={}]",
                        sessionUuid, ex.getSkillName(), i + 1, output.length());
                broadcastAndPersistSkillEvent(sessionUuid, "Skill completed: " + ex.getSkillName());
                return output;
            }

            // Anything else: treat as finished
            String result = structured.textResponse() != null && !structured.textResponse().isBlank()
                    ? structured.textResponse() : rawResponse;
            log.info("Skill sub-loop finished (unrecognised status={}) [session={}, skill={}, iterations={}]",
                    structured.status(), sessionUuid, ex.getSkillName(), i + 1);
            broadcastAndPersistSkillEvent(sessionUuid, "Skill completed: " + ex.getSkillName());
            return result;
        }

        log.warn("Skill sub-loop exhausted max iterations [session={}, skill={}, max={}]",
                sessionUuid, ex.getSkillName(), MAX_SKILL_ITERATIONS);
        broadcastAndPersistSkillEvent(sessionUuid, "Skill timed out: " + ex.getSkillName());
        return "Skill '" + ex.getSkillName() + "' execution timed out.";
    }

    private String applyAgentSwitch(String sessionUuid, String agentTemplateId) {
        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null) {
            log.warn("applyAgentSwitch: session not found [session={}]", sessionUuid);
            return null;
        }
        sessionRepo.save(new AiSession(session.uuid(), session.provider(), session.originMode(),
                session.username(), session.name(), session.createdAt(), session.currentRoundCount(),
                session.messages(), session.environmentVariables(), session.status(), agentTemplateId,
                session.modelId(), session.skillStack(), session.sessionSkillUuids(), session.sessionToolIds()));
        UiEventFrame switchEvent = new UiEventFrame(UUID.randomUUID().toString(),
                "AGENT_SWITCH", "AGENT_SWITCH", agentTemplateId, null);
        messaging.convertAndSend("/topic/chat/" + sessionUuid, switchEvent);
        log.info("Active agent switched [session={}, newAgentId={}]", sessionUuid, agentTemplateId);
        return agentTemplateId;
    }

    /**
     * Broadcasts an agent transition event over WebSocket and appends a
     * persistent {@link AiChatMessage} record to {@code accumulator} so the
     * transition is visible when the session history is reloaded.
     */
    private void broadcastAndAccumulateTransition(String sessionUuid, String text,
                                                   List<AiChatMessage> accumulator) {
        UiEventFrame event = new UiEventFrame(
                UUID.randomUUID().toString(), "AGENT_TRANSITION", "AGENT_TRANSITION", text, null);
        messaging.convertAndSend("/topic/chat/" + sessionUuid, event);
        accumulator.add(new AiChatMessage(
                UUID.randomUUID().toString(), "AGENT_TRANSITION",
                text, System.currentTimeMillis(), null));
        log.debug("Agent transition [session={}, text={}]", sessionUuid, text);
    }

    private void broadcastAndPersistSkillEvent(String sessionUuid, String text) {
        UiEventFrame event = new UiEventFrame(
                UUID.randomUUID().toString(), "SKILL_TRANSITION", "SKILL_TRANSITION", text, null);
        messaging.convertAndSend("/topic/chat/" + sessionUuid, event);
        AiSession session = sessionRepo.get(sessionUuid);
        if (session != null) {
            List<AiChatMessage> updated = new ArrayList<>(session.messages());
            updated.add(new AiChatMessage(
                    UUID.randomUUID().toString(), "SKILL_TRANSITION",
                    text, System.currentTimeMillis(), null));
            sessionRepo.save(new AiSession(
                    session.uuid(), session.provider(), session.originMode(),
                    session.username(), session.name(), session.createdAt(),
                    session.currentRoundCount(), List.copyOf(updated),
                    session.environmentVariables(), session.status(),
                    session.activeAgentTemplateId(), session.modelId(), session.skillStack(),
                    session.sessionSkillUuids(), session.sessionToolIds()));
        }
        log.debug("Skill transition [session={}, text={}]", sessionUuid, text);
    }

    /**
     * Resolves the display name of an {@link AgentTemplate} by UUID.
     * Falls back to a generic label when the template cannot be found.
     */
    private String resolveAgentNameById(String agentTemplateId) {
        if (agentTemplateId == null || agentTemplateId.isBlank()) {
            return "Concierge";
        }
        AgentTemplate template = agentTemplateRepo.get(agentTemplateId);
        return template != null ? template.name() : "Concierge";
    }

    /**
     * Parses raw model output into a {@link StructuredAgentResponse}.
     * Strips markdown code fences if present. On parse failure returns a
     * synthetic FINISHED_TURN with the raw text as textResponse.
     *
     * <p>When the parsed record's {@code textResponse} is null or blank (i.e.
     * the model used a non-standard field name such as {@code result},
     * {@code response}, or {@code message}), the method attempts to recover
     * the value from those alternate fields before returning.
     */
    /**
     * Extracts the plain {@code textResponse} field from a raw model response that
     * may or may not be a structured JSON envelope. Safe to call from outside the
     * agent loop (e.g. the welcome message or session-title generation paths).
     *
     * @param raw the model's raw output string
     * @return the human-readable text; falls back to {@code raw} if not parseable
     */
    public String extractTextResponse(String raw) {
        StructuredAgentResponse parsed = parseStructuredResponse(raw);
        return parsed.textResponse() != null && !parsed.textResponse().isBlank()
                ? parsed.textResponse()
                : raw;
    }

    private StructuredAgentResponse parseStructuredResponse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return new StructuredAgentResponse("FINISHED_TURN", "", null, null);
        }
        try {
            String json = rawResponse.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("(?s)^```[a-zA-Z]*\\n?", "").replaceAll("(?s)```\\s*$", "").strip();
            }
            // If a thinking model leaks a reasoning prefix, skip to the first '{'.
            if (!json.startsWith("{")) {
                int brace = json.indexOf('{');
                if (brace > 0) {
                    json = json.substring(brace).strip();
                }
            }
            StructuredAgentResponse parsed = objectMapper.readValue(json, StructuredAgentResponse.class);

            // If textResponse is missing, try common alternate field names the model might use
            // (e.g. "result", "response", "message"). This avoids leaking raw JSON to the UI.
            if (parsed.textResponse() == null || parsed.textResponse().isBlank()) {
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);
                String alt = extractAlternateTextField(node);
                if (alt != null && !alt.isBlank()) {
                    log.debug("parseStructuredResponse: textResponse absent, recovered from alternate field [status={}]",
                            parsed.status());
                    parsed = new StructuredAgentResponse(
                            parsed.status(), alt, parsed.targetAgent(), parsed.delegationInstructions());
                }
            }
            return parsed;
        } catch (Exception e) {
            log.warn("Failed to parse StructuredAgentResponse, treating as FINISHED_TURN [preview={}]",
                    rawResponse.length() > 200 ? rawResponse.substring(0, 200) : rawResponse);
            return new StructuredAgentResponse("FINISHED_TURN", rawResponse, null, null);
        }
    }

    /**
     * Tries to extract a human-readable text value from well-known alternate field
     * names that models occasionally use instead of {@code textResponse}.
     * Returns {@code null} when none match.
     */
    private static String extractAlternateTextField(com.fasterxml.jackson.databind.JsonNode node) {
        for (String field : List.of("result", "response", "message", "content", "output", "text", "reply")) {
            com.fasterxml.jackson.databind.JsonNode n = node.get(field);
            if (n != null && n.isTextual() && !n.asText().isBlank()) {
                return n.asText();
            }
        }
        return null;
    }

    /**
     * Resolves an {@link AgentTemplate} UUID by display {@code name}.
     * Returns {@code null} when no template with that name exists.
     */
    private String resolveAgentByName(String targetName, String sessionUuid) {
        if (targetName == null || targetName.isBlank()) {
            log.warn("resolveAgentByName: null/blank target name [session={}]", sessionUuid);
            return null;
        }
        try (var stream = agentTemplateRepo.search(0, 1, "name", SortOrder.ASC,
                SearchQuery.eq("name", targetName))) {
            return stream.findFirst()
                    .map(AgentTemplate::uuid)
                    .orElseGet(() -> {
                        log.warn("resolveAgentByName: no template found [name={}, session={}]",
                                targetName, sessionUuid);
                        return null;
                    });
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
                session.environmentVariables(),
                persistedStatus,
                session.activeAgentTemplateId(),
                session.modelId(),
                session.skillStack(),
                session.sessionSkillUuids(),
                session.sessionToolIds()));

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

    /**
     * Builds the Spring AI message history for a session, filtering to only skill-scoped messages
     * when an active skill frame is present on the stack.
     *
     * <p>Skills run in an isolated context: they should only see tool outputs and messages from
     * their own execution, not the full background-job history.  The top {@link SkillFrame}'s
     * {@code startMessageCount} marks the first relevant message; everything before it is
     * hidden from the skill AI.
     */
    private List<Message> buildHistoryForSession(AiSession session) {
        List<AiChatMessage> msgs = session.messages();
        boolean skillFrameActive = session.skillStack() != null && !session.skillStack().isEmpty();
        if (skillFrameActive) {
            sh.vork.skill.SkillFrame topFrame = session.skillStack().getLast();
            Integer startIdx = topFrame.startMessageCount();
            if (startIdx == null) {
                // Legacy frame without startMessageCount: use empty history so the skill
                // does not see unrelated background-job messages.
                log.debug("Skill frame has no startMessageCount — using empty history [session={}, skill={}]",
                        session.uuid(), topFrame.skillName());
                msgs = List.of();
            } else if (startIdx > 0 && startIdx < msgs.size()) {
                msgs = msgs.subList(startIdx, msgs.size());
                log.debug("History filtered for skill frame [startIdx={}, total={}, filtered={}, skill={}]",
                        startIdx, session.messages().size(), msgs.size(), topFrame.skillName());
            }
            // startIdx == 0 means the skill started at the beginning — use full history as-is.
        }

        // Outside skill frames, replay TOOL messages only when the tool remains
        // visible to the current session. This preserves useful context for
        // in-scope tools while preventing leakage from inaccessible tool names.
        Set<String> visibleToolNames = skillFrameActive
                ? null
                : aiService.resolveVisibleToolNamesForSession(session.uuid());
        return hydrateHistory(msgs, skillFrameActive, visibleToolNames);
    }

    private List<Message> hydrateHistory(List<AiChatMessage> messages,
                                         boolean includeAllToolMessages,
                                         Set<String> visibleToolNames) {
        List<Message> history = new ArrayList<>();
        for (AiChatMessage message : messages) {
            switch (message.role()) {
                case "USER" -> history.add(new UserMessage(message.content() == null ? "" : message.content()));
                case "ASSISTANT" -> history.add(new AssistantMessage(message.content() == null ? "" : message.content()));
                case "TOOL" -> {
                    String toolName = message.toolName();
                    if (includeAllToolMessages
                            || (toolName != null && visibleToolNames != null && visibleToolNames.contains(toolName))) {
                        appendToolReplay(history, message);
                    }
                }
                default -> {
                }
            }
        }
        return history;
    }

    private void appendToolReplay(List<Message> history, AiChatMessage message) {
        boolean canEmitFunctionCall = !history.isEmpty()
                && (history.getLast().getMessageType() == MessageType.USER
                || history.getLast().getMessageType() == MessageType.TOOL);
        if (canEmitFunctionCall) {
            history.add(toSyntheticToolCallMessage(message));
            history.add(toToolResponseMessage(message));
            return;
        }
        history.add(toToolReplayTextMessage(message));
    }

    private Message toSyntheticToolCallMessage(AiChatMessage message) {
        String toolName = message.toolName() == null ? "unknown-tool" : message.toolName();
        String toolCallId = message.toolCallId() == null ? "pending-unknown" : message.toolCallId();

        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                toolCallId,
                "FUNCTION",
                toolName,
                "{}");

        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();
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

    private Message toToolReplayTextMessage(AiChatMessage message) {
        String toolName = message.toolName() == null ? "unknown-tool" : message.toolName();
        String toolCallId = message.toolCallId() == null ? "pending-unknown" : message.toolCallId();
        String responseData = extractToolResponseData(message);
        String replayText = "Tool '" + toolName + "' (callId=" + toolCallId + ") result:\n" + responseData;
        return new AssistantMessage(replayText);
    }

    private String extractToolResponseData(AiChatMessage message) {
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
        return normalizeToolResponseData(responseData);
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
            StructuredAgentResponse titleParsed = parseStructuredResponse(candidate);
            String titleText = titleParsed.textResponse() != null && !titleParsed.textResponse().isBlank()
                    ? titleParsed.textResponse() : candidate;
            String sanitized = sanitizeSessionTitle(titleText);

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
                    latest.environmentVariables(),
                    latest.status(),
                    latest.activeAgentTemplateId(),
                    latest.modelId(),
                    latest.skillStack(),
                    latest.sessionSkillUuids(),
                    latest.sessionToolIds()));
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
