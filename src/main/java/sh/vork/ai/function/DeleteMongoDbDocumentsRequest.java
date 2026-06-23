package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code deleteMongoDBDocuments} tool.
 */
public record DeleteMongoDbDocumentsRequest(

        @JsonProperty(value = "connectionName")
        @JsonPropertyDescription("Optional saved MongoDB connection profile name. Defaults to 'default'.")
        String connectionName,

        @JsonProperty(value = "collection")
        @JsonPropertyDescription("Optional explicit collection name. If omitted, resolved from query/filter context.")
        String collection,

        @JsonProperty(value = "query")
        @JsonPropertyDescription("Natural language context used for collection resolution and fuzzy filter generation.")
        String query,

        @JsonProperty(value = "filterJson")
        @JsonPropertyDescription("Optional raw MongoDB filter JSON string.")
        String filterJson,

        @JsonProperty(value = "multi")
        @JsonPropertyDescription("When true, delete all matching documents; otherwise delete one. Defaults to false.")
        Boolean multi
) {}
