package sh.vork.ai.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.function.ExecuteTerminalCommandRequest;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.security.AuthorizationRuleEngine;
import sh.vork.ai.service.AiOrchestrationService;
import sh.vork.database.mock.MapDatabaseRepository;
import sh.vork.scheduling.service.AiSchedulerService;

class ChatAuthorizationControllerIsolationTest {

    @Test
    void respond_webOrigin_resumesSynchronouslyOnRequestThread() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);

        String sessionUuid = "session-web";
        String promptJson = objectMapper.writeValueAsString(new UiEventFrame(
                "evt-1",
                "PROMPT_REQUIRED",
                "AUTHORIZE_TOOL",
                Map.of(
                        "toolName", "compileJavaType",
                        "toolCallId", "pending-1",
                        "arguments", "{}",
                        "actions", List.of("ONCE", "SESSION", "ALWAYS", "DENIED"))));

        sessionRepo.save(new AiSession(
                sessionUuid,
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "alice",
                System.currentTimeMillis(),
            0,
                List.of(new AiChatMessage("m1", "PROMPT_REQUIRED", promptJson, System.currentTimeMillis(), null)),
            AiSessionStatus.AWAITING_AUTHORIZATION));

        AuthorizationRuleEngine rules = new AuthorizationRuleEngine(List.of(), null);
        RecordingAiService aiService = new RecordingAiService("web-final");
        RecordingSchedulerService schedulerService = new RecordingSchedulerService();
        SimpMessagingTemplate messaging = new SimpMessagingTemplate(new NoOpMessageChannel());
        Executor directExecutor = Runnable::run;

        ChatAuthorizationController controller = new ChatAuthorizationController(
                sessionRepo,
                rules,
                aiService,
                messaging,
                objectMapper,
            List.of(allowingCompileTool()),
                directExecutor,
                schedulerService);

        ResponseEntity<Map<String, Object>> response = controller.respond(
                sessionUuid,
                new ChatAuthorizationController.AuthorizationResponseRequest("evt-1", "DENIED", Map.of()));

        assertEquals("WEB_RESUMED", response.getBody().get("status"));
        assertTrue(aiService.generateWithHistoryCalls > 0);

        AiSession saved = sessionRepo.get(sessionUuid);
        assertNotNull(saved);
        assertEquals(SessionOriginMode.WEB, saved.originMode());
        assertEquals(AiSessionStatus.RUNNING, saved.status());
        assertEquals("TEXT_RESPONSE", saved.messages().get(saved.messages().size() - 1).role());
    }

    @Test
    void respond_backgroundOrigin_dispatchesAsyncToBackgroundExecutor() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);

        String sessionUuid = "session-bg";
        String promptJson = objectMapper.writeValueAsString(new UiEventFrame(
                "evt-2",
                "PROMPT_REQUIRED",
                "AUTHORIZE_TOOL",
                Map.of(
                        "toolName", "compileJavaType",
                        "toolCallId", "pending-2",
                        "arguments", "{}",
                        "actions", List.of("ONCE", "SESSION", "ALWAYS", "DENIED"))));

        sessionRepo.save(new AiSession(
                sessionUuid,
                AiProvider.BACKGROUND_SCHEDULER.name(),
                SessionOriginMode.BACKGROUND,
                "system-user",
                System.currentTimeMillis(),
            0,
                List.of(new AiChatMessage("m2", "PROMPT_REQUIRED", promptJson, System.currentTimeMillis(), null)),
            AiSessionStatus.AWAITING_AUTHORIZATION));

        AuthorizationRuleEngine rules = new AuthorizationRuleEngine(List.of(), null);
        RecordingAiService aiService = new RecordingAiService("bg-final");
        RecordingSchedulerService schedulerService = new RecordingSchedulerService();
        SimpMessagingTemplate messaging = new SimpMessagingTemplate(new NoOpMessageChannel());
        Executor directExecutor = Runnable::run;

        ChatAuthorizationController controller = new ChatAuthorizationController(
                sessionRepo,
                rules,
                aiService,
                messaging,
                objectMapper,
                List.of(),
                directExecutor,
                schedulerService);

        ResponseEntity<Map<String, Object>> response = controller.respond(
                sessionUuid,
                new ChatAuthorizationController.AuthorizationResponseRequest("evt-2", "DENIED", Map.of()));

        assertEquals("BACKGROUND_RESUMED", response.getBody().get("status"));
        assertEquals(sessionUuid, schedulerService.lastResumedSessionUuid);

        AiSession saved = sessionRepo.get(sessionUuid);
        assertNotNull(saved);
        assertEquals(SessionOriginMode.BACKGROUND, saved.originMode());
        assertEquals(AiSessionStatus.RUNNING, saved.status());
    }

        @Test
        void authorizeViaLink_backgroundOrigin_mapsPolicyAndResumes() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);

        String sessionUuid = "session-bg-link";
        String promptJson = objectMapper.writeValueAsString(new UiEventFrame(
            "evt-3",
            "PROMPT_REQUIRED",
            "AUTHORIZE_TOOL",
            Map.of(
                "toolName", "compileJavaType",
                "toolCallId", "pending-3",
                "arguments", "{}",
                "actions", List.of("ONCE", "SESSION", "ALWAYS", "DENIED"))));

        sessionRepo.save(new AiSession(
            sessionUuid,
            AiProvider.BACKGROUND_SCHEDULER.name(),
            SessionOriginMode.BACKGROUND,
            "system-user",
            System.currentTimeMillis(),
            0,
            List.of(new AiChatMessage("m3", "PROMPT_REQUIRED", promptJson, System.currentTimeMillis(), null)),
            AiSessionStatus.AWAITING_AUTHORIZATION));

        AuthorizationRuleEngine rules = new AuthorizationRuleEngine(List.of(), null);
        RecordingAiService aiService = new RecordingAiService("bg-final");
        RecordingSchedulerService schedulerService = new RecordingSchedulerService();
        SimpMessagingTemplate messaging = new SimpMessagingTemplate(new NoOpMessageChannel());
        Executor directExecutor = Runnable::run;

        ChatAuthorizationController controller = new ChatAuthorizationController(
            sessionRepo,
            rules,
            aiService,
            messaging,
            objectMapper,
            List.of(allowingCompileTool()),
            directExecutor,
            schedulerService);

        ResponseEntity<Map<String, Object>> response = controller.authorizeViaLink(
            sessionUuid,
            true,
            "ONCE",
            null);

        assertEquals("BACKGROUND_RESUMED", response.getBody().get("status"));
        assertEquals(sessionUuid, schedulerService.lastResumedSessionUuid);

        AiSession saved = sessionRepo.get(sessionUuid);
        assertNotNull(saved);
        assertEquals(SessionOriginMode.BACKGROUND, saved.originMode());
        assertEquals(AiSessionStatus.RUNNING, saved.status());
        }

        @Test
        void pendingAuthorization_returnsPromptDetailsForUiCard() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);

        String sessionUuid = "session-pending-view";
        String promptJson = objectMapper.writeValueAsString(new UiEventFrame(
            "evt-4",
            "PROMPT_REQUIRED",
            "AUTHORIZE_TOOL",
            Map.of(
                "toolName", "getURLContents",
                "toolCallId", "pending-4",
                "reasoning", "Need to fetch page content before summarization.",
                "arguments", "{\"url\":\"https://jadaptive.com\"}",
                "displayArguments", "{\"url\":\"https://jadaptive.com\"}",
                "actions", List.of("ONCE", "SESSION", "ALWAYS", "DENIED"))));

        sessionRepo.save(new AiSession(
            sessionUuid,
            AiProvider.BACKGROUND_SCHEDULER.name(),
            SessionOriginMode.BACKGROUND,
            "system-user",
            System.currentTimeMillis(),
            0,
            List.of(new AiChatMessage("m4", "PROMPT_REQUIRED", promptJson, System.currentTimeMillis(), null)),
            AiSessionStatus.AWAITING_AUTHORIZATION));

        ChatAuthorizationController controller = new ChatAuthorizationController(
            sessionRepo,
            new AuthorizationRuleEngine(List.of(), null),
            new RecordingAiService("unused"),
            new SimpMessagingTemplate(new NoOpMessageChannel()),
            objectMapper,
            List.of(),
            Runnable::run,
            new RecordingSchedulerService());

        ResponseEntity<Map<String, Object>> response = controller.pendingAuthorization(sessionUuid, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("OK", response.getBody().get("status"));
        assertEquals("evt-4", response.getBody().get("eventId"));
        assertEquals("getURLContents", response.getBody().get("toolName"));
        assertEquals("AWAITING_AUTHORIZATION", response.getBody().get("sessionStatus"));
        }

        @Test
        void respond_executeTerminalCommand_persistsReplayableTranscript() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);

        String sessionUuid = "session-terminal";
        String promptJson = objectMapper.writeValueAsString(new UiEventFrame(
            "evt-terminal",
            "PROMPT_REQUIRED",
            "AUTHORIZE_TOOL",
            Map.of(
                "toolName", "executeTerminalCommand",
                "toolCallId", "pending-terminal",
                "arguments", "{\"command\":\"ls -l\"}",
                "actions", List.of("ONCE", "SESSION", "ALWAYS", "DENIED"))));

        sessionRepo.save(new AiSession(
            sessionUuid,
            AiProvider.GEMINI.name(),
            SessionOriginMode.WEB,
            "alice",
            System.currentTimeMillis(),
            0,
            List.of(new AiChatMessage("m-terminal", "PROMPT_REQUIRED", promptJson, System.currentTimeMillis(), null)),
            AiSessionStatus.AWAITING_AUTHORIZATION));

        ToolCallback terminalTool = FunctionToolCallback.builder("executeTerminalCommand",
                (ExecuteTerminalCommandRequest req) -> "ls -lls -l\ntotal 1\nfile.txt\n")
            .description("Execute a terminal command")
            .inputType(ExecuteTerminalCommandRequest.class)
            .build();

        ChatAuthorizationController controller = new ChatAuthorizationController(
            sessionRepo,
            new AuthorizationRuleEngine(List.of(), null),
            new RecordingAiService("terminal-final"),
            new SimpMessagingTemplate(new NoOpMessageChannel()),
            objectMapper,
            List.of(terminalTool),
            Runnable::run,
            new RecordingSchedulerService());

        controller.respond(sessionUuid,
            new ChatAuthorizationController.AuthorizationResponseRequest("evt-terminal", "ONCE", Map.of()));

        AiSession saved = sessionRepo.get(sessionUuid);
        assertNotNull(saved);
        AiChatMessage toolMessage = saved.messages().stream().filter(m -> "TOOL".equals(m.role())).findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(toolMessage.content(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> transcript = (Map<String, Object>) payload.get("terminalTranscript");
        assertNotNull(transcript);
        assertEquals("ls -l", transcript.get("command"));
        assertEquals("total 1\nfile.txt\n", transcript.get("output"));
        assertEquals("ls -lls -l\ntotal 1\nfile.txt\n", transcript.get("rawOutput"));
        assertNull(payload.get("nonexistent"));
        }

    private static final class RecordingAiService extends AiOrchestrationService {
        private final String nextOutput;
        private int generateWithHistoryCalls;

        private RecordingAiService(String nextOutput) {
            super(Map.of());
            this.nextOutput = nextOutput;
        }

        @Override
        public String generateWithHistory(List<org.springframework.ai.chat.messages.Message> conversationHistory,
                                          String newUserMessage,
                                          AiProvider provider) {
            generateWithHistoryCalls++;
            return nextOutput;
        }
    }

    private static final class RecordingSchedulerService extends AiSchedulerService {
        private String lastResumedSessionUuid;

        private RecordingSchedulerService() {
            super(null, null, null, null);
        }

        @Override
        public void resumeBackgroundSession(String sessionUuid) {
            this.lastResumedSessionUuid = sessionUuid;
        }
    }

    private static final class NoOpMessageChannel implements MessageChannel {
        @Override
        public boolean send(Message<?> message) {
            return true;
        }

        @Override
        public boolean send(Message<?> message, long timeout) {
            return true;
        }
    }

    private static ToolCallback allowingCompileTool() {
        return FunctionToolCallback
                .builder("compileJavaType", (DummyCompileToolRequest req) -> "{\"status\":\"ok\"}")
                .description("Test-only compile tool callback")
                .inputType(DummyCompileToolRequest.class)
                .build();
    }

    private record DummyCompileToolRequest(String source) {
    }
}
