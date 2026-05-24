package sh.vork.ai.protocol;

import sh.vork.ai.protocol.interaction.InteractionFormSchema;

/**
 * Generic event envelope sent between backend and frontend over WebSocket/REST.
 */
public record UiEventFrame(
        String eventId,
        String type,
        String intent,
        String textResponse,
        InteractionFormSchema formSchema
) {}
