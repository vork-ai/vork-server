package sh.vork.oauth;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/oauth")
public class OAuthCallbackController {

    private final OAuthClientService oauthClientService;

    public OAuthCallbackController(OAuthClientService oauthClientService) {
        this.oauthClientService = oauthClientService;
    }

    @GetMapping(value = "/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(@RequestParam("state") String state,
                                           @RequestParam(value = "code", required = false) String code,
                                           @RequestParam(value = "error", required = false) String error) {
        Map<String, Object> result = oauthClientService.completeCallback(state, code, error);
        if ("ok".equals(result.get("status"))) {
            return ResponseEntity.ok("""
                    <html><body>
                    <h3>OAuth connection completed</h3>
                    <p>You can return to the chat now and continue.</p>
                    </body></html>
                    """);
        }
        String message = String.valueOf(result.getOrDefault("message", "OAuth callback failed"));
        return ResponseEntity.badRequest().body("""
                <html><body>
                <h3>OAuth connection failed</h3>
                <p>%s</p>
                </body></html>
                """.formatted(message));
    }
}
