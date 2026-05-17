package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code searchTypeInstances} AI tool.
 *
 * <p>Supports two query formats, controlled by {@code queryType}:
 * <ul>
 *   <li><b>SQL</b> (default) — a SQL-like WHERE clause without the {@code WHERE} keyword.
 *       Examples: {@code "name = 'Alice'"}, {@code "age > 18 AND active = true"},
 *       {@code "address.city = 'London'"}, {@code "status IN ('active', 'pending')"},
 *       {@code "name LIKE '%ali%'"}.</li>
 *   <li><b>MONGO</b> — a raw MongoDB filter document as a JSON string.
 *       Example: {@code "{\"status\":\"active\",\"age\":{\"$gt\":18}}"}.</li>
 * </ul>
 */
public record SearchTypeInstancesRequest(

        @JsonProperty(required = true, value = "fqn")
        @JsonPropertyDescription("Fully-qualified class name of the type to search, "
                + "e.g. sh.vork.generated.Product")
        String fqn,

        @JsonProperty(required = true, value = "query")
        @JsonPropertyDescription(
                "The search query. "
                + "For SQL (default): a WHERE-clause expression without the WHERE keyword, "
                + "e.g. \"name = 'Alice' AND age > 18\", \"status IN ('active','pending')\", "
                + "\"address.city = 'London'\", \"name LIKE '%ali%'\". "
                + "For MONGO: a MongoDB filter document as a JSON string, "
                + "e.g. \"{\\\"status\\\":\\\"active\\\",\\\"age\\\":{\\\"$gt\\\":18}}\".")
        String query,

        @JsonProperty(value = "queryType")
        @JsonPropertyDescription(
                "Query format: SQL (default, recommended for end users) or MONGO "
                + "(for direct MongoDB queries). Case-insensitive.")
        String queryType,

        @JsonProperty(value = "sortField")
        @JsonPropertyDescription(
                "Field to sort results by. Defaults to \"uuid\" when omitted.")
        String sortField,

        @JsonProperty(value = "sortOrder")
        @JsonPropertyDescription(
                "Sort direction: ASC (ascending, default) or DESC (descending).")
        String sortOrder,

        @JsonProperty(value = "page")
        @JsonPropertyDescription(
                "Zero-based page number. Defaults to 0 when omitted.")
        Integer page,

        @JsonProperty(value = "pageSize")
        @JsonPropertyDescription(
                "Maximum number of entities to return per page. Defaults to 20 when omitted.")
        Integer pageSize
) {}
