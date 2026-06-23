package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code createMongoDBConnection} tool.
 */
public record CreateMongoDbConnectionRequest(

        @JsonProperty(value = "connectionName")
        @JsonPropertyDescription("Optional connection profile name. Defaults to 'default'.")
        String connectionName,

        @JsonProperty(value = "connectionString")
        @JsonPropertyDescription("Optional MongoDB connection URI, e.g. mongodb://user:pass@host:27017/database")
        String connectionString,

        @JsonProperty(value = "host")
        @JsonPropertyDescription("MongoDB host when connectionString is omitted. Defaults to localhost.")
        String host,

        @JsonProperty(value = "port")
        @JsonPropertyDescription("MongoDB port when connectionString is omitted. Defaults to 27017.")
        Integer port,

        @JsonProperty(value = "database")
        @JsonPropertyDescription("Default database to use for operations. Required when connectionString omits a database.")
        String database,

        @JsonProperty(value = "authDatabase")
        @JsonPropertyDescription("Authentication database name. Optional; defaults to admin.")
        String authDatabase,

        @JsonProperty(value = "username")
        @JsonPropertyDescription("MongoDB username. Reserved for secure user form flow; do not provide directly in normal tool calls.")
        String username,

        @JsonProperty(value = "password")
        @JsonPropertyDescription("MongoDB password. Reserved for secure user form flow; do not provide directly in normal tool calls.")
        String password,

        @JsonProperty(value = "credentialPromptComplete")
        @JsonPropertyDescription("Internal flag set by secure user form submission. Do not set this directly.")
        Boolean credentialPromptComplete
) {}
