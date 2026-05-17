package sh.vork.ai.entity;

import java.util.List;

/**
 * A single turn in an AI chat session.
 * Embedded inside {@link AiSession} — not a top-level {@code DatabaseEntity}.
 *
 * <p>Role values:
 * <ul>
 *   <li>{@code "USER"} — message sent by the human</li>
 *   <li>{@code "ASSISTANT"} — text response from the model</li>
 *   <li>{@code "AWAITING_AUTHORIZATION"} — model requested a tool call that
 *       needs human approval; {@link #toolCalls()} contains the pending call</li>
 *   <li>{@code "TOOL"} — tool execution result; {@link #toolCallId()} and
 *       {@link #toolName()} identify which call this responds to</li>
 *   <li>{@code "ERROR"} — internal error message shown to the user</li>
 * </ul>
 *
 * @param uuid        unique message ID
 * @param role        one of the role values listed above
 * @param content     raw message text (markdown for assistant turns)
 * @param timestamp   epoch-milliseconds when the message was recorded
 * @param attachments file attachments associated with this message (may be null)
 * @param toolCalls   pending tool calls; non-null for {@code AWAITING_AUTHORIZATION} messages
 * @param toolCallId  tool call ID being responded to; non-null for {@code TOOL} messages
 * @param toolName    name of the tool; non-null for {@code TOOL} messages
 */
public record AiChatMessage(
        String              uuid,
        String              role,
        String              content,
        long                timestamp,
        List<AttachmentRef> attachments,
        List<ToolCallRef>   toolCalls,
        String              toolCallId,
        String              toolName
) {
    /** Backward-compatible constructor for messages without tool-call fields. */
    public AiChatMessage(String uuid, String role, String content,
                         long timestamp, List<AttachmentRef> attachments) {
        this(uuid, role, content, timestamp, attachments, null, null, null);
    }

    /**
     * Slim reference to a stored file embedded in a chat message.
     *
     * @param uuid     the {@link sh.vork.storage.StoredFile} UUID
     * @param name     original filename
     * @param mimeType MIME type of the file
     */
    public record AttachmentRef(String uuid, String name, String mimeType) {}

    /**
     * Reference to a model-requested tool call, stored for conversation replay.
     *
     * @param id        tool call ID assigned by the model
     * @param type      tool type (e.g. {@code "FUNCTION"})
     * @param name      name of the tool
     * @param arguments JSON-encoded argument string from the model
     */
    public record ToolCallRef(String id, String type, String name, String arguments) {}
}
