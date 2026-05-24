package sh.vork.ai.protocol.interaction;

public record FormAction(
        String name,
        String label,
        String variant
) {}