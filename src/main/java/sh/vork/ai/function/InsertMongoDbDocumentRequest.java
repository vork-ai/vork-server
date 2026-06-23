package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code insertMongoDBDocument} tool.
 */
public record InsertMongoDbDocumentRequest(

        @JsonProperty(value = "connectionName")
        @JsonPropertyDescription("Optional saved MongoDB connection profile name. Defaults to 'default'.")
        String connectionName,

        @JsonProperty(required = true, value = "collection")
        @JsonPropertyDescription("Target MongoDB collection name.")
        String collection,

        @JsonProperty(required = true, value = "documentJson")
        @JsonPropertyDescription("MongoDB document as JSON string to insert.")
        String documentJson
) {}
