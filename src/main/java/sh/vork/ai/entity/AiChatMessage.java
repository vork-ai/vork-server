package sh.vork.ai.entity;

/**
 * A single turn in an AI chat session.
 * Embedded inside {@link AiSession} — not a top-level {@code DatabaseEntity}.
 *
 * @param uuid      unique message ID
 * @param role      {@code "USER"} or {@code "ASSISTANT"}
 * @param content   raw message text
 * @param timestamp epoch-milliseconds when the message was recorded
 */
public record AiChatMessage(
        String uuid,
        String role,
        String content,
        long   timestamp
) {}
