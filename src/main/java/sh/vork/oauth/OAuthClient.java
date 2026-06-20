package sh.vork.oauth;

import java.util.List;
import java.util.Map;

import sh.vork.orm.DatabaseEntity;

/**
 * User-scoped OAuth client configuration and token state.
 *
 * <p>Token values and client credentials are stored encrypted. Runtime code is
 * responsible for decrypting only when needed for HTTP calls or token refresh.
 */
public record OAuthClient(
        String uuid,
        String userUuid,
        String clientName,
        String authorizeEndpoint,
        String tokenEndpoint,
        String clientIdEncrypted,
        String clientSecretEncrypted,
        String redirectUri,
        List<String> scopes,
        Map<String, String> authorizationParams,
        String accessTokenEncrypted,
        String refreshTokenEncrypted,
        long accessTokenExpiresAt,
        long createdAt,
        long updatedAt
) implements DatabaseEntity {

    public OAuthClient {
        if (scopes == null) {
            scopes = List.of();
        }
        if (authorizationParams == null) {
            authorizationParams = Map.of();
        }
    }
}
