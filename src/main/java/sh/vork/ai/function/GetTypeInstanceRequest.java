package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code getTypeInstance} function tool.
 */
public record GetTypeInstanceRequest(
        @JsonProperty(required = true, value = "fqn")
        @JsonPropertyDescription("Fully-qualified class name of the record, e.g. sh.vork.generated.Cat")
        String fqn,

        @JsonProperty(required = true, value = "uuid")
        @JsonPropertyDescription("UUID of the record to fetch")
        String uuid
) {}
