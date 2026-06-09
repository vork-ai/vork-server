package sh.vork.ssl.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sh.vork.ssl.AcmeChallengeStore;

/**
 * Serves ACME HTTP-01 challenge responses at
 * {@code /.well-known/acme-challenge/{token}}.
 *
 * <p>Let's Encrypt validators fetch this URL on plain HTTP (port 80).
 * The response must be the key-authorisation string stored in {@link AcmeChallengeStore}.
 */
@RestController
@RequestMapping("/.well-known/acme-challenge")
public class AcmeChallengeController {

    private final AcmeChallengeStore challengeStore;

    public AcmeChallengeController(AcmeChallengeStore challengeStore) {
        this.challengeStore = challengeStore;
    }

    @GetMapping("/{token}")
    public ResponseEntity<String> challenge(@PathVariable String token) {
        String authorization = challengeStore.get(token);
        if (authorization == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header("Content-Type", "text/plain")
                .body(authorization);
    }
}
