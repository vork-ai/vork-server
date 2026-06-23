package sh.vork.ai.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code memory} meta-tool.
 *
 * <p>Stores and retrieves session-scoped key/value memory that is injected into
 * subsequent system prompts via environment-variable hydration.
 */
public record MemoryRequest(
        @JsonProperty(value = "operation")
        @JsonPropertyDescription("Operation to run: set, get, list, or delete. Defaults to set.")
        String operation,

        @JsonProperty(value = "key")
        @JsonPropertyDescription("Memory key for set/get/delete operations, e.g. active_target_alias.")
        String key,

        @JsonProperty(value = "value")
        @JsonPropertyDescription("Memory value for set operation, e.g. node-east-01.")
        String value,

        @JsonProperty(value = "prefix")
        @JsonPropertyDescription("Optional key prefix filter for list operation.")
        String prefix
) {}
