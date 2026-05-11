package sh.vork.ai.controller;

import sh.vork.ai.AiProvider;
import sh.vork.ai.service.AiOrchestrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for AI generation.
 *
 * <p>Example requests:
 * <pre>
 *   GET /ai/generate?prompt=What+is+the+weather+in+Paris%3F
 *   GET /ai/generate?prompt=Hello&provider=GEMINI
 * </pre>
 */
@RestController
@RequestMapping("/ai")
public class AiController {

    private final AiOrchestrationService orchestrationService;

    public AiController(AiOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    /**
     * Generates a response from the specified AI provider.
     *
     * @param prompt   the user's prompt (required)
     * @param provider AI provider to use; defaults to {@link AiProvider#GEMINI}
     * @return plain-text model response
     */
    @GetMapping("/generate")
    public ResponseEntity<String> generate(
            @RequestParam String prompt,
            @RequestParam(defaultValue = "GEMINI") AiProvider provider) {
        return ResponseEntity.ok(orchestrationService.generate(prompt, provider));
    }
}
