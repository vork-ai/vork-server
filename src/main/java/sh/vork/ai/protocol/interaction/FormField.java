package sh.vork.ai.protocol.interaction;

import java.util.List;

public record FormField(
        String name,
        String type,
        String label,
        String placeholder,
        boolean required,
        FieldSource source,
        List<String> options
) {}