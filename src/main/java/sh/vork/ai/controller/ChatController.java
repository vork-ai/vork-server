package sh.vork.ai.controller;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.service.ChatService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

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

    public ChatController(ChatService chatService, SimpMessagingTemplate messaging) {
        this.chatService = chatService;
        this.messaging   = messaging;
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    @GetMapping("/session")
    public SessionResponse getSession(
            HttpSession httpSession,
            @RequestParam(defaultValue = "GEMINI") AiProvider provider) {
        AiSession session = chatService.getOrCreateSession(httpSession.getId(), provider);
        return new SessionResponse(session.uuid(), session.messages());
    }

    // ── WebSocket / STOMP ─────────────────────────────────────────────────────

    @MessageMapping("/chat.send")
    public void handleChatMessage(ChatRequest request) {
        log.debug("WebSocket message [session={}, length={}]",
                request.sessionUuid(), request.content() == null ? 0 : request.content().length());
        try {
            AiProvider provider = resolveProvider(request.provider());
            AiChatMessage response = chatService.sendMessage(request.sessionUuid(), request.content(), provider);
            messaging.convertAndSend("/topic/chat/" + request.sessionUuid(), response);
        } catch (Exception ex) {
            log.error("Chat error [session={}]: {}", request.sessionUuid(), ex.getMessage(), ex);
            AiChatMessage error = new AiChatMessage(
                    UUID.randomUUID().toString(),
                    "ERROR",
                    "Sorry, something went wrong: " + ex.getMessage(),
                    System.currentTimeMillis());
            messaging.convertAndSend("/topic/chat/" + request.sessionUuid(), error);
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

    record SessionResponse(String sessionUuid, List<AiChatMessage> messages) {}

    record ChatRequest(String sessionUuid, String content, String provider) {}
}
