package sh.vork.setup;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import sh.vork.ai.AiProvider;
import sh.vork.ai.discovery.DiscoveredModel;
import sh.vork.ai.discovery.ModelDiscoveryOrchestrator;
import sh.vork.ai.provider.AiChatClientFactory;
import sh.vork.ai.provider.AiProviderConfigService;

/**
 * Serves the first-time setup wizard and the AJAX endpoints it calls.
 *
 * <p>Page endpoints ({@code /setup}) are accessible without authentication.
 * API endpoints ({@code /api/setup/**}) are also unauthenticated so the wizard
 * can create the first admin account.  Both are exempted from CSRF checks in
 * {@code SecurityConfig} because no session/cookie exists during setup.
 */
@Controller
public class SetupController {

    private static final Logger log = LoggerFactory.getLogger(SetupController.class);

    private final SetupService              setupService;
    private final DatabaseSetupService      databaseSetupService;
    private final AiProviderConfigService   configService;
    private final AiChatClientFactory       clientFactory;
    private final ModelDiscoveryOrchestrator orchestrator;
    private final SystemSettingsService     systemSettingsService;
    private final AuthenticationManager     authenticationManager;

    public SetupController(SetupService setupService,
                           DatabaseSetupService databaseSetupService,
                           AiProviderConfigService configService,
                           AiChatClientFactory clientFactory,
                           ModelDiscoveryOrchestrator orchestrator,
                           SystemSettingsService systemSettingsService,
                           AuthenticationManager authenticationManager) {
        this.setupService          = setupService;
        this.databaseSetupService  = databaseSetupService;
        this.configService         = configService;
        this.clientFactory         = clientFactory;
        this.orchestrator          = orchestrator;
        this.systemSettingsService = systemSettingsService;
        this.authenticationManager = authenticationManager;
    }

    // ── Page endpoint ─────────────────────────────────────────────────────────

    /** Renders the setup wizard. Redirects to {@code /} if setup is already complete. */
    @GetMapping("/setup")
    public String setupWizard() {
        if (!setupService.isSetupRequired()) {
            return "redirect:/";
        }
        return "setup";
    }

    // ── API endpoints ─────────────────────────────────────────────────────────

    /** Returns whether setup is still required and whether the Gemini key is pre-configured. */
    @GetMapping("/api/setup/status")
    @ResponseBody
    public Map<String, Object> status() {
        return Map.of(
                "setupRequired",          setupService.isSetupRequired(),
                "databaseConfigured",     databaseSetupService.isDatabaseConfigured(),
                "geminiApiKeyConfigured",  configService.getConfig(AiProvider.GEMINI) != null
        );
    }

    /** Returns the current or default database settings (password omitted). */
    @GetMapping("/api/setup/database")
    @ResponseBody
    public Map<String, Object> getDatabaseSettings() {
        log.debug("ENTER getDatabaseSettings");
        boolean configured = databaseSetupService.isDatabaseConfigured();
        DatabaseSettings s = databaseSetupService.getCurrentSettings();
        Map<String, Object> settingsMap = switch (s.backend()) {
            case "nitrite" -> Map.of(
                    "backend",  s.backend(),
                    "database", s.database() != null ? s.database() : "");
            case "redis" -> Map.of(
                    "backend",  s.backend(),
                    "host",     s.host(),
                    "port",     s.port());
            default -> Map.of(
                    "backend",  s.backend(),
                    "host",     s.host(),
                    "port",     s.port(),
                    "database", s.database() != null ? s.database() : "",
                    "username", s.username() != null ? s.username() : "");
        };
        return Map.of("configured", configured, "settings", settingsMap);
    }

    /** Tests connectivity without saving anything. */
    @PostMapping("/api/setup/database/test")
    @ResponseBody
    public Map<String, Object> testDatabase(@RequestBody DatabaseRequest req) {
        log.debug("ENTER testDatabase: backend={}, host={}, port={}",
                req.backend(), req.host(), req.port());
        DatabaseSettings settings = new DatabaseSettings(
                req.backend(), req.host(), req.port(),
                req.database(), req.username(), req.password());
        DatabaseSetupService.TestResult result = databaseSetupService.testConnection(settings);
        if (!result.ok()) {
            return Map.of("error", result.error());
        }
        log.info("Database connection test OK [backend={}, host={}, port={}]",
                req.backend(), req.host(), req.port());
        return Map.of("ok", true);
    }

    /**
     * Tests, saves, and optionally triggers a restart when the backend changes.
     *
     * <p>A restart is required only when the active backend (as loaded by Spring)
     * differs from the requested one — the new {@code @ConditionalOnProperty}
     * beans cannot be swapped without a JVM restart.
     */
    @PostMapping("/api/setup/database")
    @ResponseBody
    public Map<String, Object> configureDatabase(@RequestBody DatabaseRequest req) {
        log.debug("ENTER configureDatabase: backend={}, host={}, port={}",
                req.backend(), req.host(), req.port());
        DatabaseSettings settings = new DatabaseSettings(
                req.backend(), req.host(), req.port(),
                req.database(), req.username(), req.password());

        // Test the connection before persisting anything
        DatabaseSetupService.TestResult test = databaseSetupService.testConnection(settings);
        if (!test.ok()) {
            return Map.of("error", test.error());
        }

        try {
            boolean previouslyConfigured = databaseSetupService.isDatabaseConfigured();
            String previousBackend = databaseSetupService.getCurrentBackend();
            DatabaseSettings previousSettings = databaseSetupService.getCurrentSettings();
            databaseSetupService.saveConfig(settings);

            boolean restartRequired;
            if (!previouslyConfigured) {
                // Fresh install: Nitrite backend is active by default (matchIfMissing=true).
                // Restart only if the user's settings differ from those defaults.
                restartRequired = !"nitrite".equals(req.backend())
                    || !Objects.equals(settings.database(), previousSettings.database());
            } else {
                // Re-configure: restart only when the active backend is changing.
                restartRequired = !previousBackend.equals(req.backend());
            }

            if (restartRequired) {
                databaseSetupService.scheduleRestart();
            }
            log.info("Database configured [backend={}, restartRequired={}]",
                    req.backend(), restartRequired);
            return Map.of("ok", true, "restartRequired", restartRequired);
        } catch (Exception e) {
            log.warn("Failed to save database configuration: {}", e.getMessage());
            return Map.of("error", "Failed to save configuration: " + e.getMessage());
        }
    }

    /**
     * Creates the initial admin account.
     * On success the user is automatically logged into the current HTTP session.
     */
    @PostMapping("/api/setup/account")
    @ResponseBody
    public ResponseEntity<?> createAccount(@RequestBody AccountRequest req,
                                           HttpServletRequest httpRequest) {
        log.debug("ENTER createAccount: [username={}]", req.username());
        if (!setupService.isSetupRequired()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Setup is already complete."));
        }
        if (req.username() == null || req.username().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required."));
        }
        if (req.password() == null || req.password().length() < 8) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password must be at least 8 characters."));
        }
        if (!req.password().equals(req.confirmPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Passwords do not match."));
        }
        try {
            setupService.createAdminUser(req.username(), req.password());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        // Auto-login so subsequent wizard steps use the authenticated session
        autoLogin(req.username(), req.password(), httpRequest);
        log.info("Admin account created during setup: [username={}]", req.username());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Saves the provider credentials, invalidates the discovery cache, and returns
     * the list of discovered models.  Returns an error body if no models are found.
     */
    @PostMapping("/api/setup/ai-provider/validate")
    @ResponseBody
    public ResponseEntity<?> validateProvider(@RequestBody ProviderValidateRequest req) {
        log.debug("ENTER validateProvider: [provider={}]", req.provider());
        AiProvider provider = resolveProvider(req.provider());
        if (provider == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown provider: " + req.provider()));
        }
        // Persist credentials so the discovery provider can pick them up
        configService.saveConfig(provider, req.apiKey(), req.baseUrl(), null, true);
        clientFactory.invalidate(provider);
        orchestrator.invalidate(provider.name().toLowerCase());
        List<DiscoveredModel> models = orchestrator.discoverForProvider(provider.name().toLowerCase());
        if (models.isEmpty()) {
            return ResponseEntity.ok(Map.of("error", "No models found — please check your credentials."));
        }
        log.info("Setup provider validation succeeded [provider={}, models={}]", provider, models.size());
        return ResponseEntity.ok(Map.of("models", models));
    }

    /**
     * Persists the final provider configuration and, when requested, sets it
     * as the global default provider/model for all new sessions.
     */
    @PostMapping("/api/setup/ai-provider")
    @ResponseBody
    public ResponseEntity<?> saveProvider(@RequestBody ProviderSaveRequest req) {
        log.debug("ENTER saveProvider: [provider={}, model={}]", req.provider(), req.defaultModel());
        AiProvider provider = resolveProvider(req.provider());
        if (provider == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown provider: " + req.provider()));
        }
        configService.saveConfig(provider, req.apiKey(), req.baseUrl(), req.defaultModel(), true);
        if (req.setAsGlobal() && req.defaultModel() != null && !req.defaultModel().isBlank()) {
            systemSettingsService.setGlobal(provider.name(), req.defaultModel());
        }
        log.info("Provider configured during setup [provider={}, model={}, global={}]",
                provider, req.defaultModel(), req.setAsGlobal());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AiProvider resolveProvider(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            AiProvider p = AiProvider.valueOf(name.toUpperCase());
            return (p == AiProvider.BACKGROUND_SCHEDULER || p == AiProvider.ANTHROPIC) ? null : p;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void autoLogin(String username, String password, HttpServletRequest request) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            HttpSession session = request.getSession(true);
            session.setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
            log.debug("Auto-login succeeded for setup user: {}", username);
        } catch (AuthenticationException e) {
            log.warn("Auto-login failed after account creation (user will log in manually): {}", e.getMessage());
        }
    }

    // ── Request DTOs ─────────────────────────────────────────────────────────

    record AccountRequest(String username, String password, String confirmPassword) {}

    record DatabaseRequest(String backend, String host, int port,
                           String database, String username, String password) {}

    record ProviderValidateRequest(String provider, String apiKey, String baseUrl) {}

    record ProviderSaveRequest(String provider, String apiKey, String baseUrl,
                               String defaultModel, boolean setAsGlobal) {}
}
