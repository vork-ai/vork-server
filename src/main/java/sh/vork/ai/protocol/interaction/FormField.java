package sh.vork.ai.protocol.interaction;

import java.util.List;

public record FormField(
        String name,
        String type,
        String label,
        String placeholder,
        String value,
        boolean required,
        FieldSource source,
        List<String> options
) {
    public FormField(String name,
                     String type,
                     String label,
                     String placeholder,
                     boolean required,
                     FieldSource source,
                     List<String> options) {
        this(name, type, label, placeholder, null, required, source, options);
    }
}