package sh.vork.ssl;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for active ACME HTTP-01 challenge tokens.
 *
 * <p>The ACME server validates domain ownership by requesting:
 * <pre>http://{domain}/.well-known/acme-challenge/{token}</pre>
 *
 * <p>This store holds the token → authorization-string mapping while the
 * challenge is pending.  Entries are removed once the challenge succeeds or fails.
 */
@Component
public class AcmeChallengeStore {

    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    /**
     * Registers a challenge token with its authorization string.
     *
     * @param token         the challenge token (path segment)
     * @param authorization the key-authorisation string to serve as the response body
     */
    public void put(String token, String authorization) {
        store.put(token, authorization);
    }

    /**
     * Returns the key-authorisation string for the given token, or {@code null} if absent.
     */
    public String get(String token) {
        return store.get(token);
    }

    /** Removes the challenge entry after completion (success or failure). */
    public void remove(String token) {
        store.remove(token);
    }

    /** Returns true if there are any active challenge tokens. */
    public boolean isEmpty() {
        return store.isEmpty();
    }
}
