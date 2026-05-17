package sh.vork.ai.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record CompleteBackgroundTaskRequest(
        @JsonProperty(required = false, value = "sessionUuid")
        @JsonPropertyDescription("Optional session UUID override. Normally inferred from execution context.")
        String sessionUuid
) {}
