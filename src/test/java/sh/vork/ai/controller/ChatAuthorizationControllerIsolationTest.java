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
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.function.ExecuteTerminalCommandRequest;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.protocol.interaction.FieldSource;
import sh.vork.ai.protocol.interaction.FormAction;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;
import sh.vork.ai.security.AuthorizationRuleEngine;
import sh.vork.ai.service.AiOrchestrationService;
import sh.vork.database.mock.MapDatabaseRepository;
import sh.vork.scheduling.service.AiSchedulerService;
import sh.vork.security.SecureCredentialStore;

class ChatAuthorizationControllerIsolationTest {

    @Test
    void respond_webOrigin_resumesSynchronouslyOnRequestThread() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);

        String sessionUuid = "session-web";
        AiChatMessage prompt = promptMessage("evt-1", "compileJavaType", "pending-1", "{}", "Need approval");

        sessionRepo.save(new AiSession(
                sessionUuid,
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "alice",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(prompt),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.AWAITING_INPUT));

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
                schedulerService,
                null,
                null,
                new SecureCredentialStore(),
                null);

        ResponseEntity<Map<String, Object>> response = controller.respond(
                sessionUuid,
                new ChatAuthorizationController.InteractionResponse("evt-1", "AUTHORIZE_TOOL", "DENIED", Map.of()));

        assertEquals("WEB_RESUMED", response.getBody().get("status"));
        assertTrue(aiService.generateWithHistoryCalls > 0);
        assertTrue(aiService.lastNewUserMessage.contains("Do not call tools again"));

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
        AiChatMessage prompt = promptMessage("evt-2", "compileJavaType", "pending-2", "{}", "Need approval");

        sessionRepo.save(new AiSession(
                sessionUuid,
                AiProvider.BACKGROUND_SCHEDULER.name(),
                SessionOriginMode.BACKGROUND,
                "system-user",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(prompt),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.AWAITING_INPUT));

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
                schedulerService,
                null,
                null,
                new SecureCredentialStore(),
                null);

        ResponseEntity<Map<String, Object>> response = controller.respond(
                sessionUuid,
                new ChatAuthorizationController.InteractionResponse("evt-2", "AUTHORIZE_TOOL", "DENIED", Map.of()));

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
        AiChatMessage prompt = promptMessage("evt-3", "compileJavaType", "pending-3", "{}", "Need approval");

        sessionRepo.save(new AiSession(
                sessionUuid,
                AiProvider.BACKGROUND_SCHEDULER.name(),
                SessionOriginMode.BACKGROUND,
                "system-user",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(prompt),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.AWAITING_INPUT));

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
                schedulerService,
                null,
                null,
                new SecureCredentialStore(),
                null);

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
        AiChatMessage prompt = promptMessage(
                "evt-4",
                "getURLContents",
                "pending-4",
                "{\"url\":\"https://jadaptive.com\"}",
                "Need to fetch page content before summarization.");

        sessionRepo.save(new AiSession(
                sessionUuid,
                AiProvider.BACKGROUND_SCHEDULER.name(),
                SessionOriginMode.BACKGROUND,
                "system-user",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(prompt),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.AWAITING_INPUT));

        ChatAuthorizationController controller = new ChatAuthorizationController(
                sessionRepo,
                new AuthorizationRuleEngine(List.of(), null),
                new RecordingAiService("unused"),
                new SimpMessagingTemplate(new NoOpMessageChannel()),
                objectMapper,
                List.of(),
                Runnable::run,
                new RecordingSchedulerService(),
                null,
                null,
                new SecureCredentialStore(),
                null);

        ResponseEntity<Map<String, Object>> response = controller.pendingAuthorization(sessionUuid, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("OK", response.getBody().get("status"));
        assertEquals("evt-4", response.getBody().get("eventId"));
        assertEquals("getURLContents", response.getBody().get("toolName"));
        assertEquals("AWAITING_INPUT", response.getBody().get("sessionStatus"));
    }

    @Test
    void respond_executeTerminalCommand_persistsReplayableTranscript() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);

        String sessionUuid = "session-terminal";
        AiChatMessage prompt = promptMessage(
                "evt-terminal",
                "executeTerminalCommand",
                "pending-terminal",
                "{\"command\":\"ls -l\"}",
                "Need approval");

        sessionRepo.save(new AiSession(
                sessionUuid,
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "alice",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(prompt),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.AWAITING_INPUT));

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
                new RecordingSchedulerService(),
                null,
                null,
                new SecureCredentialStore(),
                null);

        controller.respond(sessionUuid,
                new ChatAuthorizationController.InteractionResponse("evt-terminal", "AUTHORIZE_TOOL", "ONCE", Map.of()));

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

    @Test
    void respond_executeTerminalCommand_includesTerminalIdentityInTranscript() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);

        String sessionUuid = "session-terminal-id";
        AiChatMessage prompt = promptMessage(
                "evt-terminal-id",
                "executeTerminalCommand",
                "pending-terminal-id",
                "{\"command\":\"uname -a\"}",
                "Need approval");

        sessionRepo.save(new AiSession(
                sessionUuid,
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "alice",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(prompt),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.AWAITING_INPUT));

        ToolCallback terminalTool = FunctionToolCallback.builder("executeTerminalCommand",
                (ExecuteTerminalCommandRequest req) -> "{\"status\":\"COMPLETED\",\"command\":\"uname -a\",\"terminalId\":\"term-123\",\"outputFileUuid\":\"file-123\",\"output\":\"Darwin test-host\"}")
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
                new RecordingSchedulerService(),
                null,
                null,
                new SecureCredentialStore(),
                null);

        controller.respond(sessionUuid,
                new ChatAuthorizationController.InteractionResponse("evt-terminal-id", "AUTHORIZE_TOOL", "ONCE", Map.of()));

        AiSession saved = sessionRepo.get(sessionUuid);
        assertNotNull(saved);
        AiChatMessage toolMessage = saved.messages().stream().filter(m -> "TOOL".equals(m.role())).findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(toolMessage.content(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> transcript = (Map<String, Object>) payload.get("terminalTranscript");
        assertNotNull(transcript);
        assertEquals("term-123", transcript.get("terminalId"));
        assertEquals("file-123", transcript.get("outputFileUuid"));
    }

    @Test
    void respond_whenModelContinuationFails_persistsToolAndErrorMessages() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);

        String sessionUuid = "session-terminal-failure";
        AiChatMessage prompt = promptMessage(
                "evt-terminal-failure",
                "executeTerminalCommand",
                "pending-terminal-failure",
                "{\"command\":\"whoami\"}",
                "Need approval");

        sessionRepo.save(new AiSession(
                sessionUuid,
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "alice",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(prompt),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.AWAITING_INPUT));

        ToolCallback terminalTool = FunctionToolCallback.builder("executeTerminalCommand",
                (ExecuteTerminalCommandRequest req) -> "{\"status\":\"COMPLETED\",\"command\":\"whoami\",\"terminalId\":\"term-fail\",\"output\":\"alice\"}")
                .description("Execute a terminal command")
                .inputType(ExecuteTerminalCommandRequest.class)
                .build();

        ChatAuthorizationController controller = new ChatAuthorizationController(
                sessionRepo,
                new AuthorizationRuleEngine(List.of(), null),
                new FailingAiService(),
                new SimpMessagingTemplate(new NoOpMessageChannel()),
                objectMapper,
                List.of(terminalTool),
                Runnable::run,
                new RecordingSchedulerService(),
                null,
                null,
                new SecureCredentialStore(),
                null);

        ResponseEntity<Map<String, Object>> response = controller.respond(
                sessionUuid,
                new ChatAuthorizationController.InteractionResponse("evt-terminal-failure", "AUTHORIZE_TOOL", "ONCE", Map.of()));

        assertEquals(500, response.getStatusCode().value());
        assertEquals("ERROR", response.getBody().get("status"));

        AiSession saved = sessionRepo.get(sessionUuid);
        assertNotNull(saved);
        long toolCount = saved.messages().stream().filter(m -> "TOOL".equals(m.role())).count();
        long errorCount = saved.messages().stream().filter(m -> "ERROR".equals(m.role())).count();
        assertEquals(1, toolCount);
        assertEquals(1, errorCount);
    }

    @Test
    void respond_toolExecutionSuspension_returnsAwaitingInputAndPersistsPrompt() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);

        String sessionUuid = "session-credential-prompt";
        AiChatMessage prompt = promptMessage(
                "evt-ssh",
                "executeTerminalCommand",
                "pending-ssh",
                "{\"command\":\"ls -l\",\"host\":\"system@example.com\"}",
                "Need approval");

        sessionRepo.save(new AiSession(
                sessionUuid,
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "alice",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(prompt),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.AWAITING_INPUT));

        InteractionFormSchema credentialSchema = new InteractionFormSchema(
                "AUTHORIZE_TOOL",
                "SSH Credentials Required",
                "Provide credentials",
                List.of(new FormField("ssh-password-system-example.com", "password", "SSH Password", "", false, FieldSource.SECRET, List.of())),
                List.of(new FormAction("ONCE", "Save & Continue", "primary"),
                        new FormAction("DENIED", "Cancel", "danger")));

        ToolCallback terminalTool = FunctionToolCallback.builder("executeTerminalCommand",
                (ExecuteTerminalCommandRequest req) -> {
                    throw new ToolSuspensionException("executeTerminalCommand", "{}", "Provide credentials", credentialSchema);
                })
                .description("Execute a terminal command")
                .inputType(ExecuteTerminalCommandRequest.class)
                .build();

        ChatAuthorizationController controller = new ChatAuthorizationController(
                sessionRepo,
                new AuthorizationRuleEngine(List.of(), null),
                new RecordingAiService("unused"),
                new SimpMessagingTemplate(new NoOpMessageChannel()),
                objectMapper,
                List.of(terminalTool),
                Runnable::run,
                new RecordingSchedulerService(),
                null,
                null,
                new SecureCredentialStore(),
                null);

        ResponseEntity<Map<String, Object>> response = controller.respond(
                sessionUuid,
                new ChatAuthorizationController.InteractionResponse("evt-ssh", "AUTHORIZE_TOOL", "ONCE", Map.of()));

        assertEquals("AWAITING_INPUT", response.getBody().get("status"));

        AiSession saved = sessionRepo.get(sessionUuid);
        assertNotNull(saved);
        assertEquals(AiSessionStatus.AWAITING_INPUT, saved.status());
        AiChatMessage latest = saved.messages().get(saved.messages().size() - 1);
        assertEquals("PROMPT_REQUIRED", latest.role());
        assertEquals("executeTerminalCommand", latest.toolName());
        assertNotNull(latest.toolCalls());
        assertEquals(1, latest.toolCalls().size());
        assertEquals("{\"command\":\"ls -l\",\"host\":\"system@example.com\"}",
                latest.toolCalls().get(0).arguments());
    }

    private static AiChatMessage promptMessage(
            String eventId,
            String toolName,
            String toolCallId,
            String arguments,
            String reason) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        UiEventFrame frame = new UiEventFrame(
                eventId,
                "PROMPT_REQUIRED",
                "AUTHORIZE_TOOL",
                reason,
                new InteractionFormSchema(
                        "AUTHORIZE_TOOL",
                        "Authorization Required",
                        reason,
                        List.of(
                                new FormField("comment", "text", "Comment", "", false, FieldSource.CONVERSATION, List.of()),
                                new FormField("apiKey", "password", "API Key", "", true, FieldSource.SECRET, List.of()),
                                new FormField("profile", "text", "Profile", "", false, FieldSource.CONTEXT, List.of())),
                        List.of(
                                new FormAction("ONCE", "Allow Once", "primary"),
                                new FormAction("SESSION", "Allow for Session", "secondary"),
                                new FormAction("ALWAYS", "Always Allow", "success"),
                                new FormAction("DENIED", "Deny", "danger"))));

        return new AiChatMessage(
                "m-" + eventId,
                "PROMPT_REQUIRED",
                mapper.writeValueAsString(frame),
                System.currentTimeMillis(),
                null,
                List.of(new AiChatMessage.ToolCallRef(toolCallId, "FUNCTION", toolName, arguments)),
                toolCallId,
                toolName);
    }

    private static final class RecordingAiService extends AiOrchestrationService {
        private final String nextOutput;
        private int generateWithHistoryCalls;
        private String lastNewUserMessage;

        private RecordingAiService(String nextOutput) {
            super(Map.of(), null);
            this.nextOutput = nextOutput;
        }

        @Override
        public String generateWithHistory(List<org.springframework.ai.chat.messages.Message> conversationHistory,
                                          String newUserMessage,
                                          AiProvider provider) {
            generateWithHistoryCalls++;
            lastNewUserMessage = newUserMessage;
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

        private static final class FailingAiService extends AiOrchestrationService {
                private FailingAiService() {
                        super(Map.of(), null);
                }

                @Override
                public String generateWithHistory(List<org.springframework.ai.chat.messages.Message> conversationHistory,
                                                                                  String newUserMessage,
                                                                                  AiProvider provider) {
                        throw new IllegalStateException("simulated model continuation failure");
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

    record DummyCompileToolRequest(String source) {
    }
}
