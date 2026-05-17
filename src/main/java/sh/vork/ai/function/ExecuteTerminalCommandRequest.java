package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record ExecuteTerminalCommandRequest(
        @JsonProperty(required = true, value = "command")
        @JsonPropertyDescription("The terminal command to execute, for example 'ls -la' or 'pwd'.")
        String command,

        @JsonProperty(required = false, value = "host")
        @JsonPropertyDescription("Optional logical host key for the terminal session. Currently ignored for shell creation.")
        String host
) {}