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
 * @param createdAt epoch-milliseconds when the session was created
 * @param messages  ordered list of conversation turns
 */
public record AiSession(
        String              uuid,
        String              provider,
        long                createdAt,
        List<AiChatMessage> messages
) implements DatabaseEntity {}
