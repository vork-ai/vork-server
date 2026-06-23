package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code listMongoDBCollections} tool.
 */
public record ListMongoDbCollectionsRequest(

        @JsonProperty(value = "connectionName")
        @JsonPropertyDescription("Optional saved MongoDB connection profile name. Defaults to 'default'.")
        String connectionName
) {}
