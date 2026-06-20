package sh.vork.ai.function;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input for the {@code oauthConnect} tool.
 */
public record OAuthConnectRequest(

        @JsonProperty(required = true, value = "clientName")
        @JsonPropertyDescription("Logical OAuth client name, e.g. gmail, reddit, xero.")
        String clientName,

        @JsonProperty(value = "authorizeEndpoint")
        @JsonPropertyDescription("OAuth authorization endpoint URL. Required the first time unless already saved.")
        String authorizeEndpoint,

        @JsonProperty(value = "tokenEndpoint")
        @JsonPropertyDescription("OAuth token endpoint URL. Required the first time unless already saved.")
        String tokenEndpoint,

        @JsonProperty(value = "clientId")
        @JsonPropertyDescription("OAuth client ID. Required the first time unless already saved.")
        String clientId,

        @JsonProperty(value = "clientSecret")
        @JsonPropertyDescription("Optional OAuth client secret for confidential clients.")
        String clientSecret,

        @JsonProperty(value = "redirectUri")
        @JsonPropertyDescription("Redirect URI registered with the OAuth provider. Required the first time unless already saved.")
        String redirectUri,

        @JsonProperty(value = "scopes")
        @JsonPropertyDescription("Requested OAuth scopes. If omitted, saved scopes are used.")
        List<String> scopes,

        @JsonProperty(value = "authorizationParams")
        @JsonPropertyDescription("Optional provider-specific authorization URL query parameters (e.g. access_type=offline, prompt=consent).")
        Map<String, String> authorizationParams,

        @JsonProperty(value = "forceReconnect")
        @JsonPropertyDescription("When true, always force a new consent flow even if a token exists.")
        Boolean forceReconnect

) {
    public boolean isForceReconnect() {
        return Boolean.TRUE.equals(forceReconnect);
    }
}
