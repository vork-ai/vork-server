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
        long                createdAt,
    int                 currentRoundCount,
        List<AiChatMessage> messages,
    AiSessionStatus     status
) implements DatabaseEntity {

    /** Backward-compatible constructor for sessions created before the status field. */
    public AiSession(String uuid, String provider, long createdAt, List<AiChatMessage> messages) {
        this(uuid, provider, SessionOriginMode.WEB, "anonymous", createdAt, 0, messages, AiSessionStatus.RUNNING);
    }

    /** Backward-compatible constructor for sessions created before the username field. */
    public AiSession(String uuid, String provider, long createdAt, List<AiChatMessage> messages, String status) {
        this(uuid, provider, SessionOriginMode.WEB, "anonymous", createdAt, 0, messages,
                parseLegacyStatus(status));
    }

    /** Backward-compatible constructor for sessions created before the originMode field. */
    public AiSession(String uuid, String provider, String username, long createdAt,
                     List<AiChatMessage> messages, AiSessionStatus status) {
        this(uuid, provider, SessionOriginMode.WEB, username, createdAt, 0, messages, status);
    }

    /** Backward-compatible constructor for sessions created before enum migration. */
    public AiSession(String uuid, String provider, String username, long createdAt,
                     List<AiChatMessage> messages, String status) {
        this(uuid, provider, SessionOriginMode.WEB, username, createdAt, 0, messages, parseLegacyStatus(status));
    }

    private static AiSessionStatus parseLegacyStatus(String legacyStatus) {
        if (legacyStatus == null || legacyStatus.isBlank()) {
            return AiSessionStatus.RUNNING;
        }
        if ("AWAITING_INPUT".equals(legacyStatus)) {
            return AiSessionStatus.AWAITING_AUTHORIZATION;
        }
        try {
            return AiSessionStatus.valueOf(legacyStatus);
        } catch (IllegalArgumentException ex) {
            return AiSessionStatus.RUNNING;
        }
    }
}
