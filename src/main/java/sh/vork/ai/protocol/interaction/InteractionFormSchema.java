package sh.vork.ai.protocol.interaction;

import java.util.List;

public record InteractionFormSchema(
        String intent,
        String title,
        String description,
        List<FormField> fields,
        List<FormAction> actions
) {}