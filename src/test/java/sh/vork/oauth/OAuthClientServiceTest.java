package sh.vork.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.function.OAuthConnectRequest;
import sh.vork.ai.security.encrypt.EncryptionService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;

@ExtendWith(MockitoExtension.class)
class OAuthClientServiceTest {

    @Mock
    private RepositoryFactory repositoryFactory;

    @Mock
    private DatabaseRepository<OAuthClient> clientRepository;

    @Mock
    private DatabaseRepository<OAuthConnectSession> connectSessionRepository;

    @Mock
    private EncryptionService encryptionService;

    private OAuthClientService service;

    @BeforeEach
    void setUp() {
        when(repositoryFactory.create(OAuthClient.class)).thenReturn(clientRepository);
        when(repositoryFactory.create(OAuthConnectSession.class)).thenReturn(connectSessionRepository);
        service = new OAuthClientService(repositoryFactory, encryptionService, new ObjectMapper());
    }

    @Test
    void connectOrEnsureReturnsReadyWhenTokenAlreadyValid() {
        OAuthClient existing = new OAuthClient(
                "uuid-1",
                "alice",
                "github",
                "https://github.com/login/oauth/authorize",
                "https://github.com/login/oauth/access_token",
                "enc:client-id",
                null,
                "https://app.example.com/api/oauth/callback",
                List.of("repo"),
                Map.of(),
                "enc:access-token",
                null,
                System.currentTimeMillis() + 10 * 60 * 1000,
                System.currentTimeMillis(),
                System.currentTimeMillis());

        when(clientRepository.get(anyString())).thenReturn(existing);

        Map<String, Object> result = service.connectOrEnsure(
                "alice",
                new OAuthConnectRequest("github", null, null, null, null, null, null, null, false));

        assertEquals("ready", result.get("status"));
        assertEquals("{{OAUTH_GITHUB_ACCESS_TOKEN}}", result.get("placeholder"));
        verify(connectSessionRepository, never()).save(any());
    }

    @Test
    void connectOrEnsureReturnsConnectRequiredWhenConfigExistsWithoutToken() {
        OAuthClient existing = new OAuthClient(
                "uuid-1",
                "alice",
                "github",
                "https://github.com/login/oauth/authorize",
                "https://github.com/login/oauth/access_token",
                "enc:client-id",
                null,
                "https://app.example.com/api/oauth/callback",
                List.of("repo", "read:user"),
                Map.of(),
                null,
                null,
                0,
                System.currentTimeMillis(),
                System.currentTimeMillis());

        when(clientRepository.get(anyString())).thenReturn(existing);
        when(encryptionService.decrypt("enc:client-id")).thenReturn("client-id");
        when(encryptionService.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));

        Map<String, Object> result = service.connectOrEnsure(
                "alice",
                new OAuthConnectRequest("github", null, null, null, null, null, null, null, false));

        assertEquals("connect_required", result.get("status"));
        assertTrue(String.valueOf(result.get("authorizationUrl")).contains("code_challenge_method=S256"));
        assertEquals("{{OAUTH_GITHUB_ACCESS_TOKEN}}", result.get("placeholder"));
        verify(connectSessionRepository).save(any(OAuthConnectSession.class));
    }

    @Test
    void resolveHeaderValueReplacesAccessTokenPlaceholder() {
        OAuthClient existing = new OAuthClient(
                "uuid-1",
                "alice",
                "github",
                "https://github.com/login/oauth/authorize",
                "https://github.com/login/oauth/access_token",
                "enc:client-id",
                null,
                "https://app.example.com/api/oauth/callback",
                List.of("repo"),
                Map.of(),
                "enc:access-token",
                null,
                System.currentTimeMillis() + 10 * 60 * 1000,
                System.currentTimeMillis(),
                System.currentTimeMillis());

        when(clientRepository.get(anyString())).thenReturn(existing);
        when(encryptionService.decrypt("enc:access-token")).thenReturn("token-value-123");

        String resolved = service.resolveHeaderValue(
                "alice",
                "Bearer {{OAUTH_GITHUB_ACCESS_TOKEN}}");

        assertEquals("Bearer token-value-123", resolved);
    }

    @Test
    void resolveHeaderValueThrowsWhenPlaceholderCannotBeResolved() {
        when(clientRepository.get(anyString())).thenReturn(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.resolveHeaderValue("alice", "Bearer {{OAUTH_GITHUB_ACCESS_TOKEN}}"));

        assertTrue(ex.getMessage().contains("Call oauthConnect first"));
    }

        @Test
        void resetClientClearsClientAndPendingSessions() {
                OAuthClient existing = new OAuthClient(
                                "uuid-1",
                                "alice",
                                "gmail",
                                "https://accounts.google.com/o/oauth2/v2/auth",
                                "https://oauth2.googleapis.com/token",
                                "enc:client-id",
                                null,
                                "https://app.example.com/api/oauth/callback",
                                List.of("https://www.googleapis.com/auth/gmail.readonly"),
                                Map.of(),
                                "enc:access-token",
                                "enc:refresh-token",
                                System.currentTimeMillis() + 10 * 60 * 1000,
                                System.currentTimeMillis(),
                                System.currentTimeMillis());

                OAuthConnectSession pending = new OAuthConnectSession(
                                "state-1",
                                "alice",
                                "gmail",
                                "enc:verifier",
                                "https://app.example.com/api/oauth/callback",
                                List.of("https://www.googleapis.com/auth/gmail.readonly"),
                                System.currentTimeMillis(),
                                System.currentTimeMillis() + 60_000);

                when(clientRepository.get(anyString())).thenReturn(existing);
                when(connectSessionRepository.search(anyInt(), anyInt(), anyString(), eq(sh.vork.orm.SortOrder.DESC), any(sh.vork.orm.SearchQuery.class), any(sh.vork.orm.SearchQuery.class)))
                                .thenReturn(java.util.stream.Stream.of(pending));

                Map<String, Object> result = service.resetClient("alice", "gmail");

                assertEquals("ok", result.get("status"));
                assertEquals("gmail", result.get("clientName"));
                assertEquals(Boolean.TRUE, result.get("deletedClient"));
                assertEquals(1, result.get("deletedConnectSessions"));
                verify(clientRepository).delete(anyString());
                verify(connectSessionRepository).delete("state-1");
        }

        @Test
        void resetClientRequiresClientName() {
                Map<String, Object> result = service.resetClient("alice", " ");
                assertEquals("error", result.get("status"));
                assertTrue(String.valueOf(result.get("message")).contains("clientName is required"));
        }

        @Test
        void connectOrEnsureIncludesAuthorizationParamsInAuthorizationUrl() {
                OAuthClient existing = new OAuthClient(
                                "uuid-1",
                                "alice",
                                "gmail",
                                "https://accounts.google.com/o/oauth2/v2/auth",
                                "https://oauth2.googleapis.com/token",
                                "enc:client-id",
                                null,
                                "https://app.example.com/api/oauth/callback",
                                List.of("https://www.googleapis.com/auth/gmail.readonly"),
                                Map.of(),
                                null,
                                null,
                                0,
                                System.currentTimeMillis(),
                                System.currentTimeMillis());

                when(clientRepository.get(anyString())).thenReturn(existing);
                when(encryptionService.decrypt("enc:client-id")).thenReturn("client-id");
                when(encryptionService.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));

                Map<String, Object> result = service.connectOrEnsure(
                                "alice",
                                new OAuthConnectRequest(
                                                "gmail",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                Map.of("access_type", "offline", "prompt", "consent"),
                                                false));

                assertEquals("connect_required", result.get("status"));
                String authUrl = String.valueOf(result.get("authorizationUrl"));
                assertTrue(authUrl.contains("access_type=offline"));
                assertTrue(authUrl.contains("prompt=consent"));
        }

        @Test
        void connectOrEnsureIgnoresUnresolvedRedirectUriTemplateInput() {
                OAuthClient existing = new OAuthClient(
                                "uuid-1",
                                "alice",
                                "gmail",
                                "https://accounts.google.com/o/oauth2/v2/auth",
                                "https://oauth2.googleapis.com/token",
                                "enc:client-id",
                                null,
                                "https://app.example.com/api/oauth/callback",
                                List.of("https://www.googleapis.com/auth/gmail.readonly"),
                                Map.of(),
                                null,
                                null,
                                0,
                                System.currentTimeMillis(),
                                System.currentTimeMillis());

                when(clientRepository.get(anyString())).thenReturn(existing);
                when(encryptionService.decrypt("enc:client-id")).thenReturn("client-id");
                when(encryptionService.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));

                Map<String, Object> result = service.connectOrEnsure(
                                "alice",
                                new OAuthConnectRequest(
                                                "gmail",
                                                null,
                                                null,
                                                null,
                                                null,
                                                "https://<your_ip_address>/api/oauth/callback",
                                                null,
                                                Map.of("access_type", "offline"),
                                                false));

                assertEquals("connect_required", result.get("status"));
                String authUrl = String.valueOf(result.get("authorizationUrl"));
                assertTrue(authUrl.contains("redirect_uri=https%3A%2F%2Fapp.example.com%2Fapi%2Foauth%2Fcallback"));
                assertFalse(authUrl.contains("%3Cyour_ip_address%3E"));
        }

}
