package sh.vork.ai.controller;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import jakarta.servlet.http.HttpSession;
import sh.vork.ai.AiProvider;
import sh.vork.ai.agent.AgentType;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.provider.AiModelService;
import sh.vork.ai.registry.ToolRegistry;
import sh.vork.ai.service.AiOrchestrationService;
import sh.vork.ai.service.ChatService;
import sh.vork.ai.terminal.TerminalStreamRouter;
import sh.vork.ai.memory.SessionEnvironmentService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.skill.Skill;
import sh.vork.web.RequestOriginContext;

/**
 * Handles both HTTP session initialisation and WebSocket chat messages.
 *
 * <h3>HTTP</h3>
 * {@code GET /api/chat/session} — called on page load.  Returns the session UUID
 * and full message history so the browser can render prior turns.
 *
 * <h3>WebSocket / STOMP</h3>
 * Client sends to {@code /app/chat.send}; the server broadcasts the AI response
 * to {@code /topic/chat/{sessionUuid}}.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService            chatService;
    private final SimpMessagingTemplate  messaging;
    private final AiOrchestrationService aiOrchestrationService;
    private final TerminalStreamRouter   terminalStreamRouter;
    private final AiModelService         modelService;
    private final ToolRegistry           toolRegistry;
    private final DatabaseRepository<Skill> skillRepo;
    private final SessionEnvironmentService sessionEnvironmentService;



    public ChatController(ChatService chatService, SimpMessagingTemplate messaging,
                          AiOrchestrationService aiOrchestrationService,
                          TerminalStreamRouter terminalStreamRouter,
                          AiModelService modelService,
                          ToolRegistry toolRegistry,
                          DatabaseRepository<Skill> skillRepository,
                          SessionEnvironmentService sessionEnvironmentService) {
        this.chatService = chatService;
        this.messaging   = messaging;
        this.aiOrchestrationService = aiOrchestrationService;
        this.terminalStreamRouter = terminalStreamRouter;
        this.modelService = modelService;
        this.toolRegistry = toolRegistry;
        this.skillRepo = skillRepository;
        this.sessionEnvironmentService = sessionEnvironmentService;
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    @GetMapping("/session")
    public SessionResponse getSession(
            HttpServletRequest request,
            HttpSession httpSession,
            @RequestParam(defaultValue = "GEMINI") AiProvider provider,
            @RequestParam(required = false) String sessionUuid,
            @RequestParam(required = false) String modelId) {
        AiSession session = (sessionUuid == null || sessionUuid.isBlank())
                ? chatService.getOrCreateSession(httpSession.getId(), provider, modelId)
                : chatService.getSessionForCurrentUser(sessionUuid);
        persistRequestBaseUrl(session.uuid(), request);
        return new SessionResponse(session.uuid(), session.name(), session.provider(),
            session.activeAgentTemplateId(), session.messages(), session.modelId(), session.status(),
            session.originMode() != null ? session.originMode().name() : null);
    }

    @GetMapping("/session/new")
    public SessionResponse createSession(
            HttpServletRequest request,
            @RequestParam(defaultValue = "GEMINI") AiProvider provider,
            @RequestParam(required = false) String modelId) {
        AiSession session = chatService.createNewSession(provider, modelId);
        persistRequestBaseUrl(session.uuid(), request);
        return new SessionResponse(session.uuid(), session.name(), session.provider(),
            session.activeAgentTemplateId(), session.messages(), session.modelId(), session.status(),
            session.originMode() != null ? session.originMode().name() : null);
    }

    @GetMapping("/sessions")
    public List<SessionSummaryResponse> listSessions() {
        return chatService.listSessionsForCurrentUser()
                .stream()
                .sorted(Comparator.comparingLong(AiSession::createdAt).reversed())
                .map(session -> new SessionSummaryResponse(
                        session.uuid(),
                        session.name(),
                        session.provider(),
                        session.createdAt(),
                        session.messages() == null ? 0 : session.messages().size(),
                        session.modelId()))
                .toList();
    }

            @PostMapping("/session/{sessionUuid}/name")
            public SessionSummaryResponse renameSession(@PathVariable String sessionUuid,
                                @RequestBody RenameSessionRequest request) {
            AiSession session = chatService.renameSessionForCurrentUser(sessionUuid,
                request == null ? null : request.name());
            return new SessionSummaryResponse(
                session.uuid(),
                session.name(),
                session.provider(),
                session.createdAt(),
                session.messages() == null ? 0 : session.messages().size(),
                session.modelId());
            }

    @PutMapping("/session/{sessionUuid}/model")
    public ResponseEntity<?> updateSessionModel(@PathVariable String sessionUuid,
                                                @RequestBody ModelSelectionRequest request) {
        log.debug("ENTER updateSessionModel: [session={}, provider={}, model={}]",
                sessionUuid, request.provider(), request.modelId());
        try {
            AiSession session = chatService.updateSessionModel(sessionUuid,
                    request.provider(), request.modelId());
            log.info("Session model updated [session={}, provider={}, model={}]",
                    sessionUuid, session.provider(), session.modelId());
            return ResponseEntity.ok(Map.of(
                    "status", "OK",
                    "provider", session.provider(),
                    "modelId", session.modelId() != null ? session.modelId() : ""));
        } catch (IllegalStateException ex) {
            log.warn("updateSessionModel denied [session={}, reason={}]", sessionUuid, ex.getMessage());
            return ResponseEntity.status(403)
                    .body(Map.of("status", "ERROR", "message", "Access denied"));
        }
    }

    @GetMapping("/models")
    public List<AiModelService.ProviderModelGroup> getModels() {
        log.debug("ENTER getModels");
        return modelService.getConfiguredProviders();
    }

    @PostMapping("/session/{sessionUuid}/agent")
    public ResponseEntity<?> switchAgent(@PathVariable String sessionUuid,
                                          @RequestBody Map<String, String> body) {
        String agentTemplateId = body == null ? null : body.get("agentTemplateId");
        if (agentTemplateId == null || agentTemplateId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "ERROR", "message", "agentTemplateId required"));
        }
        try {
            ///AiSession session = chatService.getSessionForCurrentUser(sessionUuid);
            String newId = chatService.switchActiveAgentById(sessionUuid, agentTemplateId);
            if (newId == null) {
                return ResponseEntity.status(404)
                        .body(Map.of("status", "ERROR", "message", "Agent template not found"));
            }
            log.info("Agent switched via API [session={}, agentTemplateId={}]", sessionUuid, newId);
            return ResponseEntity.ok(Map.of("status", "OK", "agentTemplateId", newId));
        } catch (IllegalStateException ex) {
            log.warn("switchAgent denied [session={}, reason={}]", sessionUuid, ex.getMessage());
            return ResponseEntity.status(403)
                    .body(Map.of("status", "ERROR", "message", "Access denied"));
        }
    }

    @PostMapping("/session/{sessionUuid}/terminal/{terminalId}/terminate")
    public ResponseEntity<?> terminateCommand(@PathVariable String sessionUuid,
                                              @PathVariable String terminalId) {
        log.debug("ENTER terminateCommand: [session={}, terminal={}]", sessionUuid, terminalId);
        boolean sent = terminalStreamRouter.terminateActiveCommand(sessionUuid, terminalId);
        if (sent) {
            log.info("Terminal abort requested [session={}, terminal={}]", sessionUuid, terminalId);
            return ResponseEntity.ok(Map.of("status", "OK"));
        }
        log.warn("terminateCommand: no active command found [session={}, terminal={}]",
                sessionUuid, terminalId);
        return ResponseEntity.status(404)
                .body(Map.of("status", "NOT_FOUND", "message", "No active command for that terminal"));
    }

    @GetMapping("/agents")
    public List<AgentTemplateSummary> listAgents(
            @RequestParam(required = false) String type) {
        var stream = chatService.listAgentTemplates().stream();
        if (type != null && !type.isBlank()) {
            try {
                AgentType agentType = AgentType.valueOf(type.toUpperCase());
                stream = stream.filter(t -> t.agentType() == agentType);
            } catch (IllegalArgumentException ignored) {
                log.warn("listAgents: unknown type filter ignored [type={}]", type);
            }
        }
        return stream
                .map(t -> new AgentTemplateSummary(t.uuid(), t.name(), t.agentType().name()))
                .toList();
    }

    @GetMapping("/welcome")
    public Map<String, String> getWelcomeMessage(
            @RequestParam(defaultValue = "GEMINI") String provider) {
        AiProvider aiProvider = resolveProvider(provider);
        // generateWelcomeMessage uses the active agent system prompt + a welcome
        // instruction suffix, with all tools stripped to prevent tool-auth challenges.
        // extractTextResponse unwraps the structured JSON response.
        String raw = aiOrchestrationService.generateWelcomeMessage(aiProvider);
        String content = chatService.extractTextResponse(raw);
        return Map.of("content", content != null ? content : "");
    }

    // ── Session extras: skills & tools ────────────────────────────────────────

    @PostMapping("/session/{sessionUuid}/session-skills/{skillUuid}")
    public ResponseEntity<?> addSessionSkill(@PathVariable String sessionUuid,
                                              @PathVariable String skillUuid) {
        log.debug("ENTER addSessionSkill: [session={}, skill={}]", sessionUuid, skillUuid);
        try {
            AiSession updated = chatService.addSessionSkill(sessionUuid, skillUuid);
            return ResponseEntity.ok(Map.of("status", "OK",
                    "sessionSkillUuids", updated.sessionSkillUuids()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(403).body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

    @DeleteMapping("/session/{sessionUuid}/session-skills/{skillUuid}")
    public ResponseEntity<?> removeSessionSkill(@PathVariable String sessionUuid,
                                                 @PathVariable String skillUuid) {
        log.debug("ENTER removeSessionSkill: [session={}, skill={}]", sessionUuid, skillUuid);
        try {
            AiSession updated = chatService.removeSessionSkill(sessionUuid, skillUuid);
            return ResponseEntity.ok(Map.of("status", "OK",
                    "sessionSkillUuids", updated.sessionSkillUuids()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(403).body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

    @PostMapping("/session/{sessionUuid}/session-tools/{toolId}")
    public ResponseEntity<?> addSessionTool(@PathVariable String sessionUuid,
                                             @PathVariable String toolId) {
        log.debug("ENTER addSessionTool: [session={}, tool={}]", sessionUuid, toolId);
        try {
            AiSession updated = chatService.addSessionTool(sessionUuid, toolId);
            return ResponseEntity.ok(Map.of("status", "OK",
                    "sessionToolIds", updated.sessionToolIds()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(403).body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

    @DeleteMapping("/session/{sessionUuid}/session-tools/{toolId}")
    public ResponseEntity<?> removeSessionTool(@PathVariable String sessionUuid,
                                                @PathVariable String toolId) {
        log.debug("ENTER removeSessionTool: [session={}, tool={}]", sessionUuid, toolId);
        try {
            AiSession updated = chatService.removeSessionTool(sessionUuid, toolId);
            return ResponseEntity.ok(Map.of("status", "OK",
                    "sessionToolIds", updated.sessionToolIds()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(403).body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

    /** Returns all non-hidden tools from the registry, optionally filtered by category. */
    @GetMapping("/tools")
    public List<ToolSummary> listTools(
            @RequestParam(required = false) String category) {
        log.debug("ENTER listTools: [category={}]", category);
        return toolRegistry.getAvailableTools().stream()
                .filter(d -> category == null || category.isBlank() || d.category().equalsIgnoreCase(category))
                .map(d -> new ToolSummary(d.id(), d.friendlyName(), d.category(), d.description()))
                .sorted(Comparator.comparing(ToolSummary::category).thenComparing(ToolSummary::name))
                .toList();
    }

    /** Returns the agent config and session extras for the sidebar panel. */
    @GetMapping("/session/{sessionUuid}/agent-config")
    public ResponseEntity<?> getAgentConfig(@PathVariable String sessionUuid) {
        log.debug("ENTER getAgentConfig: [session={}]", sessionUuid);
        try {
            AiSession session = chatService.getSessionForCurrentUser(sessionUuid);
            // Agent skills
            sh.vork.ai.agent.AgentTemplate tpl = session.activeAgentTemplateId() != null
                    ? chatService.listAgentTemplates().stream()
                            .filter(t -> t.uuid().equals(session.activeAgentTemplateId()))
                            .findFirst().orElse(null)
                    : null;
            List<SkillSummary> agentSkills = tpl != null && tpl.skillUuids() != null
                    ? tpl.skillUuids().stream()
                            .map(skillRepo::get)
                            .filter(java.util.Objects::nonNull)
                            .map(s -> new SkillSummary(s.uuid(), s.name(), s.description(), s.toolName()))
                            .toList()
                    : List.of();
            // Session skills
            List<SkillSummary> sessionSkills = session.sessionSkillUuids().stream()
                    .map(skillRepo::get)
                    .filter(java.util.Objects::nonNull)
                    .map(s -> new SkillSummary(s.uuid(), s.name(), s.description(), s.toolName()))
                    .toList();
            // Agent tools (from allowedTools list)
            List<ToolSummary> agentTools = tpl != null && tpl.allowedTools() != null
                    ? tpl.allowedTools().stream()
                            .map(id -> toolRegistry.getAvailableTools().stream()
                                    .filter(d -> d.id().equals(id))
                                    .findFirst().orElse(null))
                            .filter(java.util.Objects::nonNull)
                            .map(d -> new ToolSummary(d.id(), d.friendlyName(), d.category(), d.description()))
                            .toList()
                    : List.of();
            // Session tools
            List<ToolSummary> sessionTools = session.sessionToolIds().stream()
                    .map(id -> toolRegistry.getAvailableTools().stream()
                            .filter(d -> d.id().equals(id))
                            .findFirst().orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .map(d -> new ToolSummary(d.id(), d.friendlyName(), d.category(), d.description()))
                    .toList();
            return ResponseEntity.ok(new AgentConfigResponse(
                    tpl != null ? tpl.uuid() : null,
                    tpl != null ? tpl.name() : null,
                    agentSkills, sessionSkills, agentTools, sessionTools));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(403).body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

    // ── WebSocket / STOMP ─────────────────────────────────────────────────────

    @MessageMapping("/chat.send")
    public void handleChatMessage(ChatRequest request, java.security.Principal principal) {
        String sid = request == null ? null : request.sessionUuid();
        try (MDC.MDCCloseable _ = MDC.putCloseable("sessionUuid", sid == null ? "<null>" : sid)) {
            log.debug("WebSocket message received [length={}, attachments={}]",
                request.content() == null ? 0 : request.content().length(),
                request.attachmentUuids() == null ? 0 : request.attachmentUuids().size());
            try {
            String username = (principal != null && principal.getName() != null) ? principal.getName() : "anonymous";
            AiProvider provider = resolveProvider(request.provider());
            AiChatMessage response = chatService.sendMessageAsUser(
                username, request.sessionUuid(), request.content(), request.attachmentUuids(), provider);
            if (response != null) {
                UiEventFrame frame = new UiEventFrame(
                    UUID.randomUUID().toString(),
                    "TEXT_RESPONSE",
                    "CHAT_OUTPUT",
                    response.content(),
                    null);
                messaging.convertAndSend("/topic/chat/" + request.sessionUuid(), frame);
            }
            } catch (Exception ex) {
            log.error("Chat error: {}", ex.getMessage(), ex);
            UiEventFrame frame = new UiEventFrame(
                UUID.randomUUID().toString(),
                "ERROR",
                "CHAT_ERROR",
                "Sorry, something went wrong: " + ex.getMessage(),
                null);
            messaging.convertAndSend("/topic/chat/" + request.sessionUuid(), frame);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AiProvider resolveProvider(String name) {
        if (name == null || name.isBlank()) return AiProvider.GEMINI;
        try {
            return AiProvider.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown provider '{}', defaulting to GEMINI", name);
            return AiProvider.GEMINI;
        }
    }

    private void persistRequestBaseUrl(String sessionUuid, HttpServletRequest request) {
        if (sessionUuid == null || sessionUuid.isBlank() || request == null) {
            return;
        }

        String baseUrl = RequestOriginContext.resolveBaseUrl(request);
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }

        sessionEnvironmentService.setEnv(sessionUuid, "__request_base_url__", baseUrl);

        Map<String, String> env = sessionEnvironmentService.getEnv(sessionUuid);
        String existingRedirectUri = env.get("redirectUri");
        if (existingRedirectUri == null || existingRedirectUri.isBlank() || isUnresolvedRedirectUri(existingRedirectUri)) {
            sessionEnvironmentService.setEnv(sessionUuid, "redirectUri", baseUrl + "/api/oauth/callback");
        }
    }

    private static boolean isUnresolvedRedirectUri(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.contains("<your_ip_address>")
                || (normalized.contains("<") && normalized.contains(">"));
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    record SessionResponse(String sessionUuid, String sessionName, String provider,
                            String activeAgentTemplateId, List<AiChatMessage> messages, String modelId,
                            AiSessionStatus status, String originMode) {}

    record SessionSummaryResponse(String sessionUuid, String sessionName, String provider,
                                  long createdAt, int messageCount, String modelId) {}

    record RenameSessionRequest(String name) {}

    record ChatRequest(String sessionUuid, String content, String provider, List<String> attachmentUuids) {}

    record ModelSelectionRequest(String provider, String modelId) {}

    record AgentTemplateSummary(String uuid, String name, String agentType) {}

    record SkillSummary(String uuid, String name, String description, String toolName) {}

    record ToolSummary(String id, String name, String category, String description) {}

    record AgentConfigResponse(
            String agentUuid,
            String agentName,
            List<SkillSummary> agentSkills,
            List<SkillSummary> sessionSkills,
            List<ToolSummary> agentTools,
            List<ToolSummary> sessionTools) {}
}
