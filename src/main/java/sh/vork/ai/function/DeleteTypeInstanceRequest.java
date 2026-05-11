package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code deleteTypeInstance} function tool.
 */
public record DeleteTypeInstanceRequest(
        @JsonProperty(required = true, value = "fqn")
        @JsonPropertyDescription("Fully-qualified class name of the type, e.g. sh.vork.generated.Product")
        String fqn,

        @JsonProperty(required = true, value = "uuid")
        @JsonPropertyDescription("UUID of the instance to delete")
        String uuid
) {}
