package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code listEnumValues} function tool.
 *
 * <p>The model supplies the fully-qualified name of an enum class and receives
 * back the list of declared constants.
 */
public record ListEnumValuesRequest(
        @JsonProperty(required = true, value = "fqn")
        @JsonPropertyDescription("Fully-qualified class name of the enum, e.g. sh.vork.generated.Status")
        String fqn
) {}
