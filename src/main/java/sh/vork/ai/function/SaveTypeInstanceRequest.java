package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code saveTypeInstance} function tool.
 */
public record SaveTypeInstanceRequest(
        @JsonProperty(required = true, value = "fqn")
        @JsonPropertyDescription("Fully-qualified class name of the type, e.g. sh.vork.generated.Product")
        String fqn,

        @JsonProperty(required = true, value = "json")
        @JsonPropertyDescription(
                "JSON string representing the instance. Must include a uuid field " +
                "(generate a random UUID v4 string if creating a new instance). " +
                "Use getTypeSchema to discover the required fields and their types.")
        String json
) {}
