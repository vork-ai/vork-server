package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code countTypeInstances} function tool.
 */
public record CountTypeInstancesRequest(
        @JsonProperty(required = true, value = "fqn")
        @JsonPropertyDescription("Fully-qualified class name of the record, e.g. sh.vork.generated.Cat")
        String fqn,

        @JsonProperty(value = "query")
        @JsonPropertyDescription(
                "Optional filter query. For SQL mode: a WHERE-clause expression without WHERE. " +
                "For MONGO mode: a MongoDB filter JSON string.")
        String query,

        @JsonProperty(value = "queryType")
        @JsonPropertyDescription("Optional query format: SQL (default) or MONGO.")
        String queryType
) {}
