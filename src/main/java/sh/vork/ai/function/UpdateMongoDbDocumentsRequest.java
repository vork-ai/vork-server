package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code updateMongoDBDocuments} tool.
 */
public record UpdateMongoDbDocumentsRequest(

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

        @JsonProperty(required = true, value = "updateJson")
        @JsonPropertyDescription("MongoDB update document JSON string, e.g. {\"$set\":{\"status\":\"active\"}}.")
        String updateJson,

        @JsonProperty(value = "multi")
        @JsonPropertyDescription("When true, update all matching documents; otherwise update one. Defaults to false.")
        Boolean multi,

        @JsonProperty(value = "upsert")
        @JsonPropertyDescription("When true, insert when no document matches filter. Defaults to false.")
        Boolean upsert
) {}
