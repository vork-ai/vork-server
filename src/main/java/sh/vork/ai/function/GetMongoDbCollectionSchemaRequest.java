package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code getMongoDBCollectionSchema} tool.
 */
public record GetMongoDbCollectionSchemaRequest(

        @JsonProperty(value = "connectionName")
        @JsonPropertyDescription("Optional saved MongoDB connection profile name. Defaults to 'default'.")
        String connectionName,

        @JsonProperty(value = "collection")
        @JsonPropertyDescription("Optional explicit MongoDB collection name.")
        String collection,

        @JsonProperty(value = "query")
        @JsonPropertyDescription("Optional natural language context used to resolve collection when collection is omitted.")
        String query,

        @JsonProperty(value = "sampleSize")
        @JsonPropertyDescription("Number of documents to sample for schema inference. Defaults to 20.")
        Integer sampleSize
) {}
