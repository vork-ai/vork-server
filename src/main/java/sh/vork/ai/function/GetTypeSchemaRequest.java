package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code getTypeSchema} function tool.
 */
public record GetTypeSchemaRequest(
        @JsonProperty(required = true, value = "fqn")
        @JsonPropertyDescription("Fully-qualified class name of the custom type, e.g. sh.vork.generated.Product")
        String fqn
) {}
