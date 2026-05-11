package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code listTypeInstances} function tool.
 */
public record ListTypeInstancesRequest(
        @JsonProperty(required = true, value = "fqn")
        @JsonPropertyDescription("Fully-qualified class name of the type, e.g. sh.vork.generated.Product")
        String fqn,

        @JsonProperty(value = "page")
        @JsonPropertyDescription("Zero-based page number (default: 0)")
        Integer page,

        @JsonProperty(value = "pageSize")
        @JsonPropertyDescription("Number of entities per page (default: 20)")
        Integer pageSize
) {}
