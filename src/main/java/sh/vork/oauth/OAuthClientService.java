package sh.vork.oauth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.function.OAuthConnectRequest;
import sh.vork.ai.security.encrypt.EncryptionService;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;
import sh.vork.orm.SearchQuery;
import sh.vork.orm.SortOrder;

@Service
public class OAuthClientService {

    private static final Logger log = LoggerFactory.getLogger(OAuthClientService.class);
    private static final long DEFAULT_ACCESS_TOKEN_TTL_MS = 3600_000L;
    private static final long CONNECT_SESSION_TTL_MS = 15 * 60 * 1000L;
    private static final long TOKEN_EXPIRY_SKEW_MS = 60_000L;

    private static final Pattern ACCESS_PLACEHOLDER =
            Pattern.compile("\\{\\{(OAUTH_[A-Z0-9_]+_ACCESS_TOKEN)\\}\\}");

    private final DatabaseRepository<OAuthClient> clientRepository;
    private final DatabaseRepository<OAuthConnectSession> connectSessionRepository;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SecureRandom random = new SecureRandom();

    public OAuthClientService(RepositoryFactory factory,
                              EncryptionService encryptionService,
                              ObjectMapper objectMapper) {
        this.clientRepository = factory.create(OAuthClient.class);
        this.connectSessionRepository = factory.create(OAuthConnectSession.class);
        this.encryptionService = encryptionService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    public Map<String, Object> connectOrEnsure(String username, OAuthConnectRequest req) {
        if (username == null || username.isBlank()) {
            return Map.of("status", "error", "message", "Authenticated user is required");
        }
        if (req == null || req.clientName() == null || req.clientName().isBlank()) {
            return Map.of("status", "error", "message", "clientName is required");
        }

        String normalizedClientName = normalizeClientName(req.clientName());
        String userUuid = username;
        OAuthClient existing = loadClient(userUuid, normalizedClientName);
        OAuthClient merged = mergeConfig(existing, userUuid, normalizedClientName, req);
        if (merged != null) {
            clientRepository.save(merged);
            existing = merged;
        }

        String placeholderToken = accessTokenPlaceholder(normalizedClientName);
        String placeholder = "{{" + placeholderToken + "}}";

        if (!req.isForceReconnect() && existing != null && hasUsableAccessToken(existing)) {
            if (shouldRefresh(existing)) {
                try {
                    existing = refreshAccessToken(existing);
                } catch (Exception ex) {
                    log.warn("oauthConnect refresh failed [user={}, client={}]: {}",
                            username, normalizedClientName, ex.getMessage());
                }
            }
            if (hasUsableAccessToken(existing)) {
                return Map.of(
                        "status", "ready",
                        "clientName", normalizedClientName,
                        "secretKey", placeholderToken,
                        "placeholder", placeholder,
                        "headerExample", "Authorization: Bearer " + placeholder);
            }
        }

        if (!isConnectConfigPresent(existing)) {
            return Map.of(
                    "status", "error",
                    "clientName", normalizedClientName,
                    "message", "OAuth client is not configured. Provide authorizeEndpoint, tokenEndpoint, clientId, and redirectUri.");
        }

        String state = UUID.randomUUID().toString();
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = codeChallenge(codeVerifier);
        List<String> scopes = requestedScopes(req, existing);
        Map<String, String> authorizationParams = requestedAuthorizationParams(req, existing);
        String authorizationUrl = buildAuthorizationUrl(existing, state, codeChallenge, scopes, authorizationParams);
        String aiSessionUuid = ToolExecutionContext.getSessionUuid();

        long now = System.currentTimeMillis();
        connectSessionRepository.save(new OAuthConnectSession(
                state,
                userUuid,
                normalizedClientName,
            aiSessionUuid,
                encryptionService.encrypt(codeVerifier),
                existing.redirectUri(),
                scopes,
                now,
                now + CONNECT_SESSION_TTL_MS));

        return Map.of(
                "status", "connect_required",
                "clientName", normalizedClientName,
                "secretKey", placeholderToken,
                "placeholder", placeholder,
                "authorizationUrl", authorizationUrl,
                "message", "Open authorizationUrl and complete consent. After callback, call oauthConnect again.");
    }

    public Map<String, Object> completeCallback(String state,
                                                String code,
                                                String error) {
        if (error != null && !error.isBlank()) {
            return Map.of("status", "error", "message", "OAuth provider returned error: " + error);
        }
        if (state == null || state.isBlank()) {
            return Map.of("status", "error", "message", "state is required");
        }
        if (code == null || code.isBlank()) {
            return Map.of("status", "error", "message", "code is required");
        }

        OAuthConnectSession connectSession = connectSessionRepository.get(state);
        if (connectSession == null) {
            return Map.of("status", "error", "message", "Invalid or expired oauth state");
        }
        if (connectSession.expiresAt() < System.currentTimeMillis()) {
            connectSessionRepository.delete(state);
            return Map.of("status", "error", "message", "OAuth state expired");
        }

        String normalizedClientName = connectSession.clientName();

        OAuthClient client = loadClient(connectSession.userUuid(), normalizedClientName);
        if (client == null) {
            return Map.of("status", "error", "message", "OAuth client config not found");
        }

        try {
            String codeVerifier = encryptionService.decrypt(connectSession.codeVerifierEncrypted());
            String redirectUri = notBlank(connectSession.redirectUri())
                ? connectSession.redirectUri()
                : client.redirectUri();
            if (notBlank(connectSession.redirectUri()) && notBlank(client.redirectUri())
                && !connectSession.redirectUri().equals(client.redirectUri())) {
            log.warn("OAuth redirect URI changed after authorization start [client={}, state={}, sessionRedirectUri={}, clientRedirectUri={}]",
                normalizedClientName, state, connectSession.redirectUri(), client.redirectUri());
            }

            JsonNode tokenJson = exchangeAuthorizationCode(client, code, codeVerifier, redirectUri);
            OAuthClient updated = applyTokenPayload(client, tokenJson);
            clientRepository.save(updated);
            connectSessionRepository.delete(connectSession.uuid());
            return Map.of(
                    "status", "ok",
                    "clientName", normalizedClientName,
                    "sessionUuid", connectSession.aiSessionUuid() == null ? "" : connectSession.aiSessionUuid(),
                    "secretKey", accessTokenPlaceholder(normalizedClientName),
                    "placeholder", "{{" + accessTokenPlaceholder(normalizedClientName) + "}}");
        } catch (Exception ex) {
            log.error("OAuth callback exchange failed [client={}, state={}]: {}",
                    normalizedClientName, state, ex.getMessage(), ex);
            return Map.of("status", "error", "message", "OAuth token exchange failed: " + ex.getMessage());
        }
    }

    public Map<String, Object> resetClient(String username, String clientName) {
        if (username == null || username.isBlank()) {
            return Map.of("status", "error", "message", "Authenticated user is required");
        }
        if (clientName == null || clientName.isBlank()) {
            return Map.of("status", "error", "message", "clientName is required");
        }

        String normalizedClientName = normalizeClientName(clientName);
        String clientUuid = clientUuid(username, normalizedClientName);

        log.debug("ENTER resetClient: user={}, client={}, clientUuid={}", username, normalizedClientName, clientUuid);

        OAuthClient existing = clientRepository.get(clientUuid);
        if (existing != null) {
            clientRepository.delete(clientUuid);
        }

        int deletedConnectSessions = 0;
        try (var sessions = connectSessionRepository.search(
                0,
                200,
                "createdAt",
                SortOrder.DESC,
                SearchQuery.eq("userUuid", username),
                SearchQuery.eq("clientName", normalizedClientName))) {
            List<OAuthConnectSession> pending = sessions.toList();
            for (OAuthConnectSession session : pending) {
                connectSessionRepository.delete(session.uuid());
                deletedConnectSessions++;
            }
        }

        log.info("EXIT resetClient: user={}, client={}, deletedClient={}, deletedConnectSessions={}",
                username, normalizedClientName, existing != null, deletedConnectSessions);

        return Map.of(
                "status", "ok",
                "clientName", normalizedClientName,
                "deletedClient", existing != null,
                "deletedConnectSessions", deletedConnectSessions,
                "message", "OAuth client state cleared. Next oauthConnect call will start from a fresh configuration flow.");
    }

    public String resolveHeaderValue(String username, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return rawValue;
        }
        Matcher matcher = ACCESS_PLACEHOLDER.matcher(rawValue);
        if (!matcher.find()) {
            return rawValue;
        }

        matcher.reset();
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String tokenName = matcher.group(1);
            String token = resolveAccessTokenForPlaceholder(username, tokenName);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("OAuth token unavailable for placeholder {{" + tokenName
                        + "}}. Call oauthConnect first.");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(token));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public String resolveAccessTokenForPlaceholder(String username, String placeholderKey) {
        if (username == null || username.isBlank()) {
            return null;
        }
        String normalizedClientName = parseClientNameFromPlaceholder(placeholderKey);
        if (normalizedClientName == null) {
            return null;
        }

        OAuthClient client = loadClient(username, normalizedClientName);
        if (client == null) {
            return null;
        }
        if (shouldRefresh(client)) {
            try {
                client = refreshAccessToken(client);
            } catch (Exception ex) {
                log.warn("OAuth refresh failed during placeholder resolution [user={}, client={}]: {}",
                        username, normalizedClientName, ex.getMessage());
            }
        }
        if (!hasUsableAccessToken(client)) {
            return null;
        }
        return encryptionService.decrypt(client.accessTokenEncrypted());
    }

    private OAuthClient refreshAccessToken(OAuthClient client) throws Exception {
        if (client.refreshTokenEncrypted() == null || client.refreshTokenEncrypted().isBlank()) {
            return client;
        }
        if (client.tokenEndpoint() == null || client.tokenEndpoint().isBlank()) {
            return client;
        }

        String refreshToken = encryptionService.decrypt(client.refreshTokenEncrypted());
        if (refreshToken == null || refreshToken.isBlank()) {
            return client;
        }

        StringBuilder form = new StringBuilder();
        form.append("grant_type=refresh_token");
        form.append("&refresh_token=").append(urlEncode(refreshToken));

        String clientId = decryptIfPresent(client.clientIdEncrypted());
        if (clientId != null) {
            form.append("&client_id=").append(urlEncode(clientId));
        }
        String clientSecret = decryptIfPresent(client.clientSecretEncrypted());
        if (clientSecret != null) {
            form.append("&client_secret=").append(urlEncode(clientSecret));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(client.tokenEndpoint()))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Refresh token endpoint returned status " + response.statusCode());
        }

        JsonNode tokenJson = objectMapper.readTree(response.body());
        OAuthClient refreshed = applyTokenPayload(client, tokenJson);
        clientRepository.save(refreshed);
        return refreshed;
    }

    private JsonNode exchangeAuthorizationCode(OAuthClient client,
                                               String code,
                                               String codeVerifier,
                                               String redirectUri) throws Exception {
        StringBuilder form = new StringBuilder();
        form.append("grant_type=authorization_code");
        form.append("&code=").append(urlEncode(code));
        form.append("&redirect_uri=").append(urlEncode(redirectUri));
        form.append("&code_verifier=").append(urlEncode(codeVerifier));

        String clientId = decryptIfPresent(client.clientIdEncrypted());
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("OAuth clientId is missing");
        }
        form.append("&client_id=").append(urlEncode(clientId));

        String clientSecret = decryptIfPresent(client.clientSecretEncrypted());
        if (clientSecret != null) {
            form.append("&client_secret=").append(urlEncode(clientSecret));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(client.tokenEndpoint()))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = response.body() == null ? "" : response.body();
            String details = body.isBlank() ? "" : (": " + body);
            throw new IllegalStateException("Token endpoint returned status " + response.statusCode() + details);
        }
        return objectMapper.readTree(response.body());
    }

    private OAuthClient applyTokenPayload(OAuthClient client, JsonNode tokenJson) {
        String accessToken = textValue(tokenJson, "access_token");
        String refreshToken = textValue(tokenJson, "refresh_token");
        long expiresInSec = longValue(tokenJson, "expires_in", DEFAULT_ACCESS_TOKEN_TTL_MS / 1000L);
        String scopeText = textValue(tokenJson, "scope");

        List<String> scopes = (scopeText != null && !scopeText.isBlank())
                ? List.of(scopeText.split("\\s+"))
                : client.scopes();

        long now = System.currentTimeMillis();
        return new OAuthClient(
                client.uuid(),
                client.userUuid(),
                client.clientName(),
                client.authorizeEndpoint(),
                client.tokenEndpoint(),
                client.clientIdEncrypted(),
                client.clientSecretEncrypted(),
                client.redirectUri(),
                scopes,
                client.authorizationParams(),
                accessToken == null || accessToken.isBlank() ? client.accessTokenEncrypted() : encryptionService.encrypt(accessToken),
                refreshToken == null || refreshToken.isBlank() ? client.refreshTokenEncrypted() : encryptionService.encrypt(refreshToken),
                now + (Math.max(60L, expiresInSec) * 1000L),
                client.createdAt(),
                now);
    }

    private OAuthClient loadClient(String userUuid, String normalizedClientName) {
        return clientRepository.get(clientUuid(userUuid, normalizedClientName));
    }

    private OAuthClient mergeConfig(OAuthClient existing,
                                    String userUuid,
                                    String normalizedClientName,
                                    OAuthConnectRequest req) {
        String requestedRedirectUri = sanitizeRedirectUri(req.redirectUri());
        boolean hasAnyConfigInput = notBlank(req.authorizeEndpoint())
                || notBlank(req.tokenEndpoint())
                || notBlank(req.clientId())
                || req.clientSecret() != null
            || requestedRedirectUri != null
                || (req.scopes() != null && !req.scopes().isEmpty())
                || req.authorizationParams() != null;

        if (!hasAnyConfigInput && existing != null) {
            return null;
        }
        if (!hasAnyConfigInput && existing == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        String authorizeEndpoint = notBlank(req.authorizeEndpoint()) ? req.authorizeEndpoint().trim()
                : existing != null ? existing.authorizeEndpoint() : null;
        String tokenEndpoint = notBlank(req.tokenEndpoint()) ? req.tokenEndpoint().trim()
                : existing != null ? existing.tokenEndpoint() : null;
        String redirectUri = requestedRedirectUri != null ? requestedRedirectUri
                : existing != null ? existing.redirectUri() : null;
        String clientIdEncrypted = notBlank(req.clientId()) ? encryptionService.encrypt(req.clientId().trim())
                : existing != null ? existing.clientIdEncrypted() : null;
        String clientSecretEncrypted = req.clientSecret() != null
                ? (req.clientSecret().isBlank() ? null : encryptionService.encrypt(req.clientSecret().trim()))
                : existing != null ? existing.clientSecretEncrypted() : null;

        List<String> scopes = (req.scopes() != null && !req.scopes().isEmpty())
                ? sanitizeScopes(req.scopes())
                : existing != null ? existing.scopes() : List.of();

        Map<String, String> authorizationParams = req.authorizationParams() != null
            ? sanitizeAuthorizationParams(req.authorizationParams())
            : existing != null ? existing.authorizationParams() : Map.of();

        return new OAuthClient(
                existing != null ? existing.uuid() : clientUuid(userUuid, normalizedClientName),
                userUuid,
                normalizedClientName,
                authorizeEndpoint,
                tokenEndpoint,
                clientIdEncrypted,
                clientSecretEncrypted,
                redirectUri,
                scopes,
                authorizationParams,
                existing != null ? existing.accessTokenEncrypted() : null,
                existing != null ? existing.refreshTokenEncrypted() : null,
                existing != null ? existing.accessTokenExpiresAt() : 0L,
                existing != null ? existing.createdAt() : now,
                now);
    }

    private static boolean shouldRefresh(OAuthClient client) {
        return client != null
                && client.refreshTokenEncrypted() != null
                && !client.refreshTokenEncrypted().isBlank()
                && client.accessTokenExpiresAt() > 0
                && (client.accessTokenExpiresAt() - System.currentTimeMillis()) <= TOKEN_EXPIRY_SKEW_MS;
    }

    private static boolean hasUsableAccessToken(OAuthClient client) {
        return client != null
                && client.accessTokenEncrypted() != null
                && !client.accessTokenEncrypted().isBlank()
                && client.accessTokenExpiresAt() > System.currentTimeMillis();
    }

    private static boolean isConnectConfigPresent(OAuthClient client) {
        return client != null
                && notBlank(client.authorizeEndpoint())
                && notBlank(client.tokenEndpoint())
                && notBlank(client.clientIdEncrypted())
                && notBlank(client.redirectUri())
                && !isUnresolvedRedirectUri(client.redirectUri());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String sanitizeRedirectUri(String redirectUri) {
        if (!notBlank(redirectUri)) {
            return null;
        }
        String trimmed = redirectUri.trim();
        if (isUnresolvedRedirectUri(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private static boolean isUnresolvedRedirectUri(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.contains("<your_ip_address>")
                || (normalized.contains("<") && normalized.contains(">"));
    }

    private List<String> requestedScopes(OAuthConnectRequest req, OAuthClient client) {
        if (req.scopes() != null && !req.scopes().isEmpty()) {
            return sanitizeScopes(req.scopes());
        }
        return client != null ? client.scopes() : List.of();
    }

    private Map<String, String> requestedAuthorizationParams(OAuthConnectRequest req, OAuthClient client) {
        if (req.authorizationParams() != null) {
            return sanitizeAuthorizationParams(req.authorizationParams());
        }
        return client != null ? client.authorizationParams() : Map.of();
    }

    private static List<String> sanitizeScopes(List<String> scopes) {
        List<String> out = new ArrayList<>();
        for (String scope : scopes) {
            if (scope != null && !scope.isBlank()) {
                out.add(scope.trim());
            }
        }
        return out;
    }

    private static Map<String, String> sanitizeAuthorizationParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = entry.getValue();
            out.put(key.trim(), value == null ? "" : value.trim());
        }
        return Map.copyOf(out);
    }

    private String buildAuthorizationUrl(OAuthClient client,
                                         String state,
                                         String codeChallenge,
                                         List<String> scopes,
                                         Map<String, String> authorizationParams) {
        StringBuilder sb = new StringBuilder(client.authorizeEndpoint());
        boolean hasQuery = client.authorizeEndpoint().contains("?");

        hasQuery = appendQueryParam(sb, hasQuery, "response_type", "code");
        hasQuery = appendQueryParam(sb, hasQuery, "client_id", decryptIfPresent(client.clientIdEncrypted()));
        hasQuery = appendQueryParam(sb, hasQuery, "redirect_uri", client.redirectUri());
        if (!scopes.isEmpty()) {
            hasQuery = appendQueryParam(sb, hasQuery, "scope", String.join(" ", scopes));
        }
        hasQuery = appendQueryParam(sb, hasQuery, "code_challenge", codeChallenge);
        hasQuery = appendQueryParam(sb, hasQuery, "code_challenge_method", "S256");
        hasQuery = appendQueryParam(sb, hasQuery, "state", state);

        if (authorizationParams != null && !authorizationParams.isEmpty()) {
            for (Map.Entry<String, String> entry : authorizationParams.entrySet()) {
                hasQuery = appendQueryParam(sb, hasQuery, entry.getKey(), entry.getValue());
            }
        }
        return sb.toString();
    }

    private static boolean appendQueryParam(StringBuilder sb,
                                            boolean hasQuery,
                                            String key,
                                            String value) {
        if (key == null || key.isBlank() || value == null) {
            return hasQuery;
        }
        if (!hasQuery) {
            sb.append('?');
            hasQuery = true;
        } else if (sb.length() > 0) {
            char last = sb.charAt(sb.length() - 1);
            if (last != '?' && last != '&') {
                sb.append('&');
            }
        }
        sb.append(urlEncode(key)).append('=').append(urlEncode(value));
        return hasQuery;
    }

    public static String accessTokenPlaceholder(String normalizedClientName) {
        return "OAUTH_" + normalizedClientName.toUpperCase().replaceAll("[^A-Z0-9]", "_") + "_ACCESS_TOKEN";
    }

    private static String parseClientNameFromPlaceholder(String placeholderKey) {
        if (placeholderKey == null || placeholderKey.isBlank()) {
            return null;
        }
        String normalized = placeholderKey;
        if (normalized.startsWith("{{") && normalized.endsWith("}}")) {
            normalized = normalized.substring(2, normalized.length() - 2);
        }
        if (!normalized.startsWith("OAUTH_") || !normalized.endsWith("_ACCESS_TOKEN")) {
            return null;
        }
        String middle = normalized.substring("OAUTH_".length(), normalized.length() - "_ACCESS_TOKEN".length());
        if (middle.isBlank()) {
            return null;
        }
        return normalizeClientName(middle);
    }

    public static String normalizeClientName(String raw) {
        String n = raw == null ? "" : raw.trim().toLowerCase();
        n = n.replaceAll("[^a-z0-9]+", "_");
        n = n.replaceAll("_+", "_");
        n = n.replaceAll("^_+|_+$", "");
        return n;
    }

    private static String clientUuid(String userUuid, String normalizedClientName) {
        return UUID.nameUUIDFromBytes((userUuid + ":oauth:" + normalizedClientName)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String decryptIfPresent(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        return encryptionService.decrypt(encrypted);
    }

    private String generateCodeVerifier() {
        byte[] bytes = new byte[64];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String codeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate PKCE code challenge", e);
        }
    }

    private static String textValue(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static long longValue(JsonNode node, String field, long fallback) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || !v.isNumber()) {
            return fallback;
        }
        return v.asLong();
    }
}
