package sh.vork.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.orm.mock.MapDatabaseRepository;

class ChatServiceNoCandidateFallbackTest {

    @Test
    void sendMessage_whenModelReturnsNoCandidate_retriesWithSimplifiedPath() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        FallbackAiService aiService = new FallbackAiService();

        String sessionId = "session-fallback";
        sessionRepo.save(new AiSession(
                sessionId,
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "anonymous",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(),
            AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING, null, null, null, null, null));

        ChatService chatService = new ChatService(
                sessionRepo,
                null,
                aiService,
                null,
                null,
                new ObjectMapper().findAndRegisterModules(),
                List.of(),
                null,
                Runnable::run);

        AiChatMessage out = chatService.sendMessage(sessionId, "schedule a task", null, AiProvider.GEMINI);

        assertNotNull(out);
        assertEquals("fallback-generated", out.content());
        assertTrue(aiService.historyCalls >= 1);
        assertTrue(aiService.simpleCalls >= 1);

        AiSession saved = sessionRepo.get(sessionId);
        assertNotNull(saved);
        assertEquals(2, saved.messages().size());
        assertEquals("USER", saved.messages().get(0).role());
        assertEquals("ASSISTANT", saved.messages().get(1).role());
    }

        @Test
        void sendMessage_whenBothPrimaryAndFallbackFail_returnsSafeMessage() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        DoubleFailureAiService aiService = new DoubleFailureAiService();

        String sessionId = "session-double-failure";
        sessionRepo.save(new AiSession(
            sessionId,
            AiProvider.GEMINI.name(),
            SessionOriginMode.WEB,
            "anonymous",
            "Untitled",
            System.currentTimeMillis(),
            0,
            List.of(),
            AiSession.defaultEnvironmentVariables(),
            AiSessionStatus.RUNNING, null, null, null, null, null));

        ChatService chatService = new ChatService(
            sessionRepo,
            null,
            aiService,
            null,
            null,
            new ObjectMapper().findAndRegisterModules(),
            List.of(),
            null,
            Runnable::run);

        AiChatMessage out = chatService.sendMessage(sessionId, "schedule a task", null, AiProvider.GEMINI);

        assertNotNull(out);
        assertEquals(
            "I couldn't produce a model response right now due to a transient provider issue. Please try again.",
            out.content());
        assertTrue(aiService.historyCalls >= 1);
        assertTrue(aiService.simpleCalls >= 1);

        AiSession saved = sessionRepo.get(sessionId);
        assertNotNull(saved);
        assertEquals(2, saved.messages().size());
        assertEquals("ASSISTANT", saved.messages().get(1).role());
        }

    @Test
    void sendMessage_whenStatusBecomesCompletedDuringTurn_preservesCompletedStatus() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);

        String sessionId = "session-complete-during-turn";
        sessionRepo.save(new AiSession(
                sessionId,
                AiProvider.BACKGROUND_SCHEDULER.name(),
                SessionOriginMode.BACKGROUND,
                "anonymous",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(),
            AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING, null, null, null, null, null));

        CompletingDuringGenerationAiService aiService = new CompletingDuringGenerationAiService(sessionRepo, sessionId);

        ChatService chatService = new ChatService(
                sessionRepo,
                null,
                aiService,
                null,
                null,
                new ObjectMapper().findAndRegisterModules(),
                List.of(),
                null,
                Runnable::run);

        AiChatMessage out = chatService.sendMessage(sessionId, "finish the task", null, AiProvider.BACKGROUND_SCHEDULER);

        assertNotNull(out);
        AiSession saved = sessionRepo.get(sessionId);
        assertNotNull(saved);
        assertEquals(AiSessionStatus.COMPLETED, saved.status());
        assertEquals(2, saved.messages().size());
    }

    @Test
    void sendMessage_backgroundFinishedTurnMarksSessionCompleted() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        BackgroundFinishedTurnAiService aiService = new BackgroundFinishedTurnAiService();

        String sessionId = "session-background-finished-turn";
        sessionRepo.save(new AiSession(
                sessionId,
                AiProvider.BACKGROUND_SCHEDULER.name(),
                SessionOriginMode.BACKGROUND,
                "anonymous",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(),
            AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING, null, null, null, null, null));

        ChatService chatService = new ChatService(
                sessionRepo,
                null,
                aiService,
                null,
                null,
                new ObjectMapper().findAndRegisterModules(),
                List.of(),
                null,
                Runnable::run);

        AiChatMessage out = chatService.sendMessage(sessionId, "finish this background task", null, AiProvider.BACKGROUND_SCHEDULER);

        assertNotNull(out);
        AiSession saved = sessionRepo.get(sessionId);
        assertNotNull(saved);
        assertEquals(AiSessionStatus.COMPLETED, saved.status());
        assertEquals(2, saved.messages().size());
        assertEquals("ASSISTANT", saved.messages().get(1).role());
    }

    @Test
    void sendMessage_backgroundCompletedSession_usesCompletionReportAsFinalAssistantMessage() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        BackgroundFinishedTurnAiService aiService = new BackgroundFinishedTurnAiService();

        String sessionId = "session-background-completion-report";
        Map<String, String> env = new java.util.HashMap<>(AiSession.defaultEnvironmentVariables());
        env.put("JOB_COMPLETION_REPORT", "Completed scan and sent consolidated report to the user.");
        sessionRepo.save(new AiSession(
                sessionId,
                AiProvider.BACKGROUND_SCHEDULER.name(),
                SessionOriginMode.BACKGROUND,
                "anonymous",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(),
                Map.copyOf(env),
                AiSessionStatus.COMPLETED, null, null, null, null, null));

        ChatService chatService = new ChatService(
                sessionRepo,
                null,
                aiService,
                null,
                null,
                new ObjectMapper().findAndRegisterModules(),
                List.of(),
                null,
                Runnable::run);

        AiChatMessage out = chatService.sendMessage(sessionId, "finish this background task", null, AiProvider.BACKGROUND_SCHEDULER);

        assertNotNull(out);
        assertEquals("Completed scan and sent consolidated report to the user.", out.content());
        AiSession saved = sessionRepo.get(sessionId);
        assertNotNull(saved);
        assertEquals("Completed scan and sent consolidated report to the user.", saved.messages().get(saved.messages().size() - 1).content());
    }

    @Test
    void sendMessage_whenPersistUserMessageFalse_doesNotStoreInvocationUserMessage() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        RecordingHistoryAiService aiService = new RecordingHistoryAiService();

        String sessionId = "session-non-persisted-user-invocation";
        sessionRepo.save(new AiSession(
                sessionId,
                AiProvider.BACKGROUND_SCHEDULER.name(),
                SessionOriginMode.BACKGROUND,
                "anonymous",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING, null, null, null, null, null));

        ChatService chatService = new ChatService(
                sessionRepo,
                null,
                aiService,
                null,
                null,
                new ObjectMapper().findAndRegisterModules(),
                List.of(),
                null,
                Runnable::run);

        AiChatMessage out = chatService.sendMessage(
                sessionId,
                "Proceed.",
                List.of(),
                AiProvider.BACKGROUND_SCHEDULER,
                false);

        assertNotNull(out);
        AiSession saved = sessionRepo.get(sessionId);
        assertNotNull(saved);
        assertEquals(1, saved.messages().size());
        assertEquals("ASSISTANT", saved.messages().get(0).role());
        assertEquals("history-recorded", saved.messages().get(0).content());
    }

        @Test
        void sendMessage_excludesToolMessagesFromParentConversationHistory() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        RecordingHistoryAiService aiService = new RecordingHistoryAiService();

        String sessionId = "session-with-tool-history";
        sessionRepo.save(new AiSession(
            sessionId,
            AiProvider.GEMINI.name(),
            SessionOriginMode.WEB,
            "anonymous",
            "Untitled",
            System.currentTimeMillis(),
            0,
            List.of(
                new AiChatMessage("u1", "USER", "show the files", System.currentTimeMillis(), null),
                new AiChatMessage("t1", "TOOL",
                    "{\"responses\":[{\"id\":\"call-1\",\"name\":\"executeTerminalCommand\",\"responseData\":\"{\\\"status\\\":\\\"COMPLETED\\\",\\\"command\\\":\\\"ls -l\\\",\\\"rawOutput\\\":\\\"total 1\\nfile.txt\\n\\\",\\\"displayOutput\\\":\\\"total 1\\nfile.txt\\n\\\"}\"}],\"terminalTranscript\":{\"command\":\"ls -l\",\"output\":\"total 1\\nfile.txt\\n\"}}",
                    System.currentTimeMillis(), null, null, "call-1", "executeTerminalCommand")),
            AiSession.defaultEnvironmentVariables(),
            AiSessionStatus.RUNNING, null, null, null, null, null));

        ChatService chatService = new ChatService(
            sessionRepo,
            null,
            aiService,
            null,
            null,
            new ObjectMapper().findAndRegisterModules(),
            List.of(),
            null,
            Runnable::run);

        AiChatMessage out = chatService.sendMessage(sessionId, "summarize that output", null, AiProvider.GEMINI);

        assertNotNull(out);
        assertEquals(1, aiService.lastConversationHistory.size());
        assertTrue(aiService.lastConversationHistory.get(0) instanceof org.springframework.ai.chat.messages.UserMessage);
        }

    private static final class FallbackAiService extends AiOrchestrationService {
        int historyCalls;
        int simpleCalls;

        private FallbackAiService() {
            super(Map.of(), null, null, null, null, null, Map.of(), null, null, null, null, null, null, null);
        }

        @Override
        public String generateWithHistory(List<org.springframework.ai.chat.messages.Message> conversationHistory,
                                          String newUserMessage,
                                          AiProvider provider) {
            historyCalls++;
            throw new NoSuchElementException("No value present");
        }

        @Override
        public String generate(String userPrompt, AiProvider provider) {
            simpleCalls++;
            return "fallback-generated";
        }
    }

    private static final class DoubleFailureAiService extends AiOrchestrationService {
        int historyCalls;
        int simpleCalls;

        private DoubleFailureAiService() {
            super(Map.of(), null, null, null, null, null, Map.of(), null, null, null, null, null, null, null);
        }

        @Override
        public String generateWithHistory(List<org.springframework.ai.chat.messages.Message> conversationHistory,
                                          String newUserMessage,
                                          AiProvider provider) {
            historyCalls++;
            throw new NoSuchElementException("No value present");
        }

        @Override
        public String generate(String userPrompt, AiProvider provider) {
            simpleCalls++;
            throw new NoSuchElementException("No value present");
        }
    }

    private static final class CompletingDuringGenerationAiService extends AiOrchestrationService {
        private final MapDatabaseRepository<AiSession> sessionRepo;
        private final String sessionUuid;

        private CompletingDuringGenerationAiService(MapDatabaseRepository<AiSession> sessionRepo, String sessionUuid) {
            super(Map.of(), null, null, null, null, null, Map.of(), null, null, null, null, null, null, null);
            this.sessionRepo = sessionRepo;
            this.sessionUuid = sessionUuid;
        }

        @Override
        public String generateWithHistory(List<org.springframework.ai.chat.messages.Message> conversationHistory,
                                          String newUserMessage,
                                          AiProvider provider) {
            AiSession s = sessionRepo.get(sessionUuid);
            sessionRepo.save(new AiSession(
                    s.uuid(),
                    s.provider(),
                    s.originMode(),
                    s.username(),
                    s.name(),
                    s.createdAt(),
                    s.currentRoundCount(),
                    s.messages(),
                    AiSession.defaultEnvironmentVariables(),
                    AiSessionStatus.COMPLETED, null, null, null, null, null));
            return "I have completed the task autonomously and invoked completeBackgroundTask.";
        }
    }

    private static final class RecordingHistoryAiService extends AiOrchestrationService {
        private List<org.springframework.ai.chat.messages.Message> lastConversationHistory = List.of();

        private RecordingHistoryAiService() {
            super(Map.of(), null, null, null, null, null, Map.of(), null, null, null, null, null, null, null);
        }

        @Override
        public String generateWithHistory(List<org.springframework.ai.chat.messages.Message> conversationHistory,
                                          String newUserMessage,
                                          AiProvider provider) {
            this.lastConversationHistory = List.copyOf(conversationHistory);
            return "history-recorded";
        }
    }

    private static final class BackgroundFinishedTurnAiService extends AiOrchestrationService {
        private BackgroundFinishedTurnAiService() {
            super(Map.of(), null, null, null, null, null, Map.of(), null, null, null, null, null, null, null);
        }

        @Override
        public String generateWithHistory(List<org.springframework.ai.chat.messages.Message> conversationHistory,
                                          String newUserMessage,
                                          AiProvider provider) {
            return "{\"status\":\"FINISHED_TURN\",\"textResponse\":\"Background job finished.\"}";
        }
    }
}
