package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code searchMongoDBDocuments} tool.
 */
public record SearchMongoDbDocumentsRequest(

        @JsonProperty(value = "connectionName")
        @JsonPropertyDescription("Optional saved MongoDB connection profile name. Defaults to 'default'.")
        String connectionName,

        @JsonProperty(value = "collection")
        @JsonPropertyDescription("Optional explicit collection name. If omitted, the tool resolves it from query/filter context.")
        String collection,

        @JsonProperty(value = "query")
        @JsonPropertyDescription("Natural language search prompt used for collection resolution and fuzzy text matching.")
        String query,

        @JsonProperty(value = "filterJson")
        @JsonPropertyDescription("Optional raw MongoDB filter JSON string. When provided, it is used directly.")
        String filterJson,

        @JsonProperty(value = "projectionJson")
        @JsonPropertyDescription("Optional MongoDB projection JSON string.")
        String projectionJson,

        @JsonProperty(value = "sortField")
        @JsonPropertyDescription("Optional sort field. Defaults to _id.")
        String sortField,

        @JsonProperty(value = "sortOrder")
        @JsonPropertyDescription("Sort direction: ASC (default) or DESC.")
        String sortOrder,

        @JsonProperty(value = "page")
        @JsonPropertyDescription("Zero-based page number. Defaults to 0.")
        Integer page,

        @JsonProperty(value = "pageSize")
        @JsonPropertyDescription("Maximum number of documents per page. Defaults to 20.")
        Integer pageSize
) {}
