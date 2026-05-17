package sh.vork.ai.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiChatMessage.AttachmentRef;
import sh.vork.ai.entity.AiSession;
import sh.vork.database.DatabaseRepository;
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
  
    public ChatService(DatabaseRepository<AiSession> aiSessionRepository,
                       AiOrchestrationService aiOrchestrationService,
                       FileStorageService fileStorageService) {
        this.sessionRepo         = aiSessionRepository;
        this.aiService           = aiOrchestrationService;
        this.fileStorageService  = fileStorageService;
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
        AiSession session = new AiSession(httpSessionId, provider.name(), System.currentTimeMillis(), List.of());
        sessionRepo.save(session);
        log.info("Created AI session [id={}, provider={}]", httpSessionId, provider);
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
        List<Message> history = session.messages().stream()
                .filter(m -> "USER".equals(m.role()) || "ASSISTANT".equals(m.role()))
                .map(m -> "USER".equals(m.role())
                        ? (Message) new UserMessage(m.content() == null ? "" : m.content())
                        : new AssistantMessage(m.content() == null ? "" : m.content()))
                .collect(Collectors.toList());

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

        String aiContent;
        if (media.isEmpty()) {
            aiContent = aiService.generateWithHistory(history, effectiveContent, provider);
        } else {
            aiContent = aiService.generateWithHistoryAndMedia(history, effectiveContent, media, provider);
        }

        AiChatMessage aiMsg = new AiChatMessage(UUID.randomUUID().toString(), "ASSISTANT",
                aiContent == null ? "" : aiContent, System.currentTimeMillis(),
                refs.isEmpty() ? null : Collections.unmodifiableList(refs));
        long now = System.currentTimeMillis();
        List<AttachmentRef> userRefs = refs.isEmpty() ? null : Collections.unmodifiableList(refs);
        AiChatMessage userMsg = new AiChatMessage(UUID.randomUUID().toString(), "USER",
                content == null ? "" : content, now, userRefs);

        List<AiChatMessage> updated = new ArrayList<>(session.messages());
        updated.add(userMsg);
        updated.add(aiMsg);
        sessionRepo.save(new AiSession(session.uuid(), session.provider(), session.createdAt(),
                List.copyOf(updated)));

        log.info("Persisted chat turn [session={}, totalMessages={}]", sessionUuid, updated.size());
        return aiMsg;
    }
}
