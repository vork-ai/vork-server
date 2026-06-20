package sh.vork.oauth;

import java.util.List;

import sh.vork.orm.DatabaseEntity;

/**
 * Short-lived OAuth authorization handshake state.
 */
public record OAuthConnectSession(
        String uuid,
        String userUuid,
        String clientName,
        String codeVerifierEncrypted,
        String redirectUri,
        List<String> scopes,
        long createdAt,
        long expiresAt
) implements DatabaseEntity {

    public OAuthConnectSession {
        if (scopes == null) {
            scopes = List.of();
        }
    }
}
