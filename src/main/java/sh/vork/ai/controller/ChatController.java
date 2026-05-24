package sh.vork.ai.controller;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.service.AiOrchestrationService;
import sh.vork.ai.service.ChatService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Comparator;

/**
 * Handles both HTTP session initialisation and WebSocket chat messages.
 *
 * <h3>HTTP</h3>
 * {@code GET /api/chat/session} — called on page load.  Returns the session UUID
 * and full message history so the browser can render prior turns.
 *
 * <h3>WebSocket / STOMP</h3>
 * Client sends to {@code /app/chat.send}; the server broadcasts the AI response
 * to {@code /topic/chat/{sessionUuid}}.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService            chatService;
    private final SimpMessagingTemplate  messaging;
    private final AiOrchestrationService aiOrchestrationService;

    private static final String WELCOME_PROMPT =
            "You are Vork the Concierge, an intelligent assistant that can perform tasks for the user " +
            "that utilise the tools and skills you have access to. You can also access additional agents " +
            "that a user has created to perform specific roles. This is the start of a new session, " +
            "provide a short introduction to introduce yourself to the user and your capabilities.";

    public ChatController(ChatService chatService, SimpMessagingTemplate messaging,
                          AiOrchestrationService aiOrchestrationService) {
        this.chatService = chatService;
        this.messaging   = messaging;
        this.aiOrchestrationService = aiOrchestrationService;
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    @GetMapping("/session")
    public SessionResponse getSession(
            HttpSession httpSession,
            @RequestParam(defaultValue = "GEMINI") AiProvider provider,
            @RequestParam(required = false) String sessionUuid) {
        AiSession session = (sessionUuid == null || sessionUuid.isBlank())
                ? chatService.getOrCreateSession(httpSession.getId(), provider)
                : chatService.getSessionForCurrentUser(sessionUuid);
        return new SessionResponse(session.uuid(), session.name(), session.provider(), session.messages());
    }

    @GetMapping("/session/new")
    public SessionResponse createSession(
            @RequestParam(defaultValue = "GEMINI") AiProvider provider) {
        AiSession session = chatService.createNewSession(provider);
        return new SessionResponse(session.uuid(), session.name(), session.provider(), session.messages());
    }

    @GetMapping("/sessions")
    public List<SessionSummaryResponse> listSessions() {
        return chatService.listSessionsForCurrentUser()
                .stream()
                .sorted(Comparator.comparingLong(AiSession::createdAt).reversed())
                .map(session -> new SessionSummaryResponse(
                        session.uuid(),
                        session.name(),
                        session.provider(),
                        session.createdAt(),
                        session.messages() == null ? 0 : session.messages().size()))
                .toList();
    }

            @PostMapping("/session/{sessionUuid}/name")
            public SessionSummaryResponse renameSession(@PathVariable String sessionUuid,
                                @RequestBody RenameSessionRequest request) {
            AiSession session = chatService.renameSessionForCurrentUser(sessionUuid,
                request == null ? null : request.name());
            return new SessionSummaryResponse(
                session.uuid(),
                session.name(),
                session.provider(),
                session.createdAt(),
                session.messages() == null ? 0 : session.messages().size());
            }

    @GetMapping("/welcome")
    public Map<String, String> getWelcomeMessage(
            @RequestParam(defaultValue = "GEMINI") String provider) {
        AiProvider aiProvider = resolveProvider(provider);
        String content = aiOrchestrationService.generate(WELCOME_PROMPT, aiProvider);
        return Map.of("content", content);
    }

    // ── WebSocket / STOMP ─────────────────────────────────────────────────────

    @MessageMapping("/chat.send")
    public void handleChatMessage(ChatRequest request, java.security.Principal principal) {
        String sid = request == null ? null : request.sessionUuid();
        try (MDC.MDCCloseable sidCtx = MDC.putCloseable("sessionUuid", sid == null ? "<null>" : sid)) {
            log.debug("WebSocket message received [length={}, attachments={}]",
                request.content() == null ? 0 : request.content().length(),
                request.attachmentUuids() == null ? 0 : request.attachmentUuids().size());
            try {
            String username = (principal != null && principal.getName() != null) ? principal.getName() : "anonymous";
            AiProvider provider = resolveProvider(request.provider());
            AiChatMessage response = chatService.sendMessageAsUser(
                username, request.sessionUuid(), request.content(), request.attachmentUuids(), provider);
            if (response != null) {
                UiEventFrame frame = new UiEventFrame(
                    UUID.randomUUID().toString(),
                    "TEXT_RESPONSE",
                    "CHAT_OUTPUT",
                    response.content(),
                    null);
                messaging.convertAndSend("/topic/chat/" + request.sessionUuid(), frame);
            }
            } catch (Exception ex) {
            log.error("Chat error: {}", ex.getMessage(), ex);
            UiEventFrame frame = new UiEventFrame(
                UUID.randomUUID().toString(),
                "ERROR",
                "CHAT_ERROR",
                "Sorry, something went wrong: " + ex.getMessage(),
                null);
            messaging.convertAndSend("/topic/chat/" + request.sessionUuid(), frame);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AiProvider resolveProvider(String name) {
        if (name == null || name.isBlank()) return AiProvider.GEMINI;
        try {
            return AiProvider.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown provider '{}', defaulting to GEMINI", name);
            return AiProvider.GEMINI;
        }
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    record SessionResponse(String sessionUuid, String sessionName, String provider, List<AiChatMessage> messages) {}

    record SessionSummaryResponse(String sessionUuid, String sessionName, String provider,
                                  long createdAt, int messageCount) {}

    record RenameSessionRequest(String name) {}

    record ChatRequest(String sessionUuid, String content, String provider, List<String> attachmentUuids) {}
}
