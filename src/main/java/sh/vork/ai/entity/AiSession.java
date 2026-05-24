package sh.vork.ai.entity;

import sh.vork.database.DatabaseEntity;

import java.util.List;

/**
 * An AI chat session tied to an HTTP session.
 *
 * <p>Messages are embedded directly inside the document so that the full
 * conversation is loaded in a single MongoDB read.  The {@code uuid} is
 * the HTTP session ID, which allows {@link sh.vork.ai.service.ChatService}
 * to look up the session without a secondary index.
 *
 * @param uuid      HTTP session ID (also the MongoDB {@code _id})
 * @param provider  name of the {@link sh.vork.ai.AiProvider} in use
 * @param originMode execution origin mode for this session
 * @param username  owning principal for the session
 * @param name      human-friendly session label (non-unique)
 * @param createdAt epoch-milliseconds when the session was created
 * @param currentRoundCount number of autonomous background rounds already executed
 * @param messages  ordered list of conversation turns
 * @param status    lifecycle state enum for autonomous/background execution tracking
 */
public record AiSession(
        String              uuid,
        String              provider,
    SessionOriginMode   originMode,
    String              username,
    String              name,
        long                createdAt,
    int                 currentRoundCount,
        List<AiChatMessage> messages,
    AiSessionStatus     status
) implements DatabaseEntity {

    public AiSession {
        if (originMode == null) {
            originMode = SessionOriginMode.WEB;
        }
        if (username == null || username.isBlank()) {
            username = "anonymous";
        }
        if (name == null || name.isBlank()) {
            name = "Untitled";
        }
        if (messages == null) {
            messages = List.of();
        }
        if (status == null) {
            status = AiSessionStatus.RUNNING;
        }
    }
}
