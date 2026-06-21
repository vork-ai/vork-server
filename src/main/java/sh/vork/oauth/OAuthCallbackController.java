package sh.vork.oauth;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.orm.DatabaseRepository;

@RestController
@RequestMapping("/api/oauth")
public class OAuthCallbackController {

    private final OAuthClientService oauthClientService;
    private final DatabaseRepository<AiSession> sessionRepository;

    public OAuthCallbackController(OAuthClientService oauthClientService,
                                   DatabaseRepository<AiSession> sessionRepository) {
        this.oauthClientService = oauthClientService;
        this.sessionRepository = sessionRepository;
    }

    @GetMapping(value = "/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(@RequestParam("state") String state,
                                           @RequestParam(value = "code", required = false) String code,
                                           @RequestParam(value = "error", required = false) String error) {
        Map<String, Object> result = oauthClientService.completeCallback(state, code, error);
        if ("ok".equals(result.get("status"))) {
            String sessionUuid = String.valueOf(result.getOrDefault("sessionUuid", ""));
            SessionOriginMode originMode = resolveOriginMode(sessionUuid);
            String autoResumeScript = "";
            String followUpMessage;
            if (!sessionUuid.isBlank()) {
                autoResumeScript = """
                    <script>
                    (async function () {
                        try {
                            await fetch('/api/chat/authorize/%s?approved=true&policy=ONCE', { method: 'GET', credentials: 'same-origin' });
                        } catch (e) {
                            // Best-effort resume; user can still continue manually if needed.
                        }
                        %s
                    }());
                    </script>
                    """.formatted(sessionUuid,
                        originMode == SessionOriginMode.WEB
                                ? "window.location.href = '/index.html';"
                                : "");
            }

            if (originMode == SessionOriginMode.WEB) {
                followUpMessage = "Returning you to chat…";
            } else if (originMode == SessionOriginMode.TELEGRAM) {
                followUpMessage = "OAuth connected. You can return to Telegram and continue there.";
            } else if (originMode == SessionOriginMode.SLACK) {
                followUpMessage = "OAuth connected. You can return to Slack and continue there.";
            } else {
                followUpMessage = "OAuth connected. You can return to your original channel and continue.";
            }
            return ResponseEntity.ok("""
                    <html><body>
                    <h3>OAuth connection completed</h3>
                    <p>%s</p>
                    %s
                    </body></html>
                    """.formatted(followUpMessage, autoResumeScript));
        }
        String message = String.valueOf(result.getOrDefault("message", "OAuth callback failed"));
        return ResponseEntity.badRequest().body("""
                <html><body>
                <h3>OAuth connection failed</h3>
                <p>%s</p>
                </body></html>
                """.formatted(message));
    }

    private SessionOriginMode resolveOriginMode(String sessionUuid) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return SessionOriginMode.WEB;
        }
        AiSession session = sessionRepository.get(sessionUuid);
        if (session == null || session.originMode() == null) {
            return SessionOriginMode.WEB;
        }
        return session.originMode();
    }
}
