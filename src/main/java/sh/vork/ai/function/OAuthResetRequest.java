package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input for the {@code oauthReset} tool.
 */
public record OAuthResetRequest(

        @JsonProperty(required = true, value = "clientName")
        @JsonPropertyDescription("Logical OAuth client name to reset, e.g. gmail, reddit, xero.")
        String clientName

) {
}
