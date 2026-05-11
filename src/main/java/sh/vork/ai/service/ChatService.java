package sh.vork.ai.service;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.database.DatabaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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

    public ChatService(DatabaseRepository<AiSession> aiSessionRepository,
                       AiOrchestrationService aiOrchestrationService) {
        this.sessionRepo = aiSessionRepository;
        this.aiService   = aiOrchestrationService;
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
     * Sends {@code content} to the AI with the full conversation history,
     * persists both the user message and the AI response on success,
     * and returns the AI response as an {@link AiChatMessage}.
     *
     * @param sessionUuid UUID of the target {@link AiSession}
     * @param content     the user's message text
     * @param provider    the AI provider to use
     * @return the AI's response message (role {@code "ASSISTANT"})
     * @throws IllegalStateException if the session is not found
     */
    public AiChatMessage sendMessage(String sessionUuid, String content, AiProvider provider) {
        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null) {
            throw new IllegalStateException("AI session not found: " + sessionUuid);
        }

        // Build Spring AI message list from stored history
        List<Message> history = session.messages().stream()
                .map(m -> "USER".equals(m.role())
                        ? (Message) new UserMessage(m.content())
                        : new AssistantMessage(m.content()))
                .collect(Collectors.toList());

        log.info("Chat turn [session={}, history={} msgs, provider={}]", sessionUuid, history.size(), provider);

        // Call AI — exceptions propagate; nothing is persisted on failure
        String aiContent = aiService.generateWithHistory(history, content, provider);

        // Persist both messages atomically on success
        long   now     = System.currentTimeMillis();
        AiChatMessage userMsg = new AiChatMessage(UUID.randomUUID().toString(), "USER",      content,   now);
        AiChatMessage aiMsg   = new AiChatMessage(UUID.randomUUID().toString(), "ASSISTANT", aiContent, now + 1);

        List<AiChatMessage> updated = new ArrayList<>(session.messages());
        updated.add(userMsg);
        updated.add(aiMsg);
        sessionRepo.save(new AiSession(session.uuid(), session.provider(), session.createdAt(), List.copyOf(updated)));

        log.info("Persisted chat turn [session={}, totalMessages={}]", sessionUuid, updated.size());
        return aiMsg;
    }
}
