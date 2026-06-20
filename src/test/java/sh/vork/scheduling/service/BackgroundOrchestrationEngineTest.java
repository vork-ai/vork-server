package sh.vork.scheduling.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
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
import sh.vork.ai.service.AiOrchestrationService;
import sh.vork.ai.service.ChatService;
import sh.vork.orm.mock.MapDatabaseRepository;
import sh.vork.scheduling.service.BackgroundOrchestrationEngineTest.TestChatService.Mode;

class BackgroundOrchestrationEngineTest {

    @Test
    void executeBackgroundTurn_maxRounds_setsFailedMaxRoundsAndStops() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        BackgroundExecutionContext context = new BackgroundExecutionContext();
        TestChatService chatService = new TestChatService(sessionRepo, context, Mode.NO_OP);
        BackgroundOrchestrationEngine engine = new BackgroundOrchestrationEngine(chatService, sessionRepo, context, null, null);

        String sessionUuid = "bg-max";
        sessionRepo.save(new AiSession(
                sessionUuid,
                AiProvider.BACKGROUND_SCHEDULER.name(),
                SessionOriginMode.BACKGROUND,
                "alice",
                "Untitled",
                System.currentTimeMillis(),
                10,
                List.of(),
            AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING, null, null, null, null, null));

        engine.executeBackgroundTurn(sessionUuid, "initial");

        AiSession saved = sessionRepo.get(sessionUuid);
        assertNotNull(saved);
        assertEquals(AiSessionStatus.FAILED_MAX_ROUNDS, saved.status());
        assertEquals(0, chatService.sendCallCount);
        assertTrue(saved.messages().stream().anyMatch(m ->
                "ASSISTANT".equals(m.role()) && m.content().contains("max rounds (10)")));
    }

    @Test
    void executeBackgroundTurn_completionFlag_stopsLoop() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        BackgroundExecutionContext context = new BackgroundExecutionContext();
        TestChatService chatService = new TestChatService(sessionRepo, context, Mode.MARK_COMPLETE);
        BackgroundOrchestrationEngine engine = new BackgroundOrchestrationEngine(chatService, sessionRepo, context, null, null);

        String sessionUuid = "bg-complete";
        sessionRepo.save(new AiSession(
                sessionUuid,
                AiProvider.BACKGROUND_SCHEDULER.name(),
                SessionOriginMode.BACKGROUND,
                "alice",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(),
            AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING, null, null, null, null, null));

        engine.executeBackgroundTurn(sessionUuid, "initial");

        AiSession saved = sessionRepo.get(sessionUuid);
        assertNotNull(saved);
        assertEquals(AiSessionStatus.COMPLETED, saved.status());
        assertEquals(1, chatService.sendCallCount);
        assertEquals(1, saved.currentRoundCount());
    }

    @Test
    void executeBackgroundTurn_suspensionException_exitsWithoutCompletedOrFailed() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        BackgroundExecutionContext context = new BackgroundExecutionContext();
        TestChatService chatService = new TestChatService(sessionRepo, context, Mode.THROW_SUSPENSION);
        BackgroundOrchestrationEngine engine = new BackgroundOrchestrationEngine(chatService, sessionRepo, context, null, null);

        String sessionUuid = "bg-suspend";
        sessionRepo.save(new AiSession(
                sessionUuid,
                AiProvider.BACKGROUND_SCHEDULER.name(),
                SessionOriginMode.BACKGROUND,
                "alice",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(),
            AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING, null, null, null, null, null));

        engine.executeBackgroundTurn(sessionUuid, "initial");

        AiSession saved = sessionRepo.get(sessionUuid);
        assertNotNull(saved);
        assertEquals(AiSessionStatus.RUNNING, saved.status());
        assertEquals(1, saved.currentRoundCount());
        assertEquals(1, chatService.sendCallCount);
    }


    static final class TestChatService extends ChatService {
        enum Mode {
            NO_OP,
            MARK_COMPLETE,
            THROW_SUSPENSION
        }

        private final MapDatabaseRepository<AiSession> sessionRepo;
        private final BackgroundExecutionContext context;
        private final Mode mode;
        int sendCallCount;
        boolean lastPersistUserMessage = true;

        TestChatService(MapDatabaseRepository<AiSession> sessionRepo,
                        BackgroundExecutionContext context,
                        Mode mode) {
            super(
                    sessionRepo,
                    null,
                    new AiOrchestrationService(Map.of(), null, null, null, null, null, Map.of(), null, null, null, null, null, null, null),
                    null,
                    new SimpMessagingTemplate(new NoOpMessageChannel()),
                    new ObjectMapper().findAndRegisterModules(),
                    List.<ToolCallback>of(),
                    null,
                    Runnable::run);
            this.sessionRepo = sessionRepo;
            this.context = context;
            this.mode = mode;
        }

        @Override
        public AiChatMessage sendMessage(String sessionUuid, String content,
                                         List<String> attachmentUuids, AiProvider provider) {
            return sendMessage(sessionUuid, content, attachmentUuids, provider, true);
        }

        @Override
        public AiChatMessage sendMessage(String sessionUuid, String content,
                                         List<String> attachmentUuids, AiProvider provider,
                                         boolean persistUserMessage) {
            sendCallCount++;
            lastPersistUserMessage = persistUserMessage;
            if (mode == Mode.THROW_SUSPENSION) {
                throw new ToolSuspensionException("compileJavaType", "{}");
            }
            if (mode == Mode.MARK_COMPLETE) {
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
                context.markExecutionComplete();
            }
            return null;
        }
    }

    @Test
    void executeBackgroundTurn_usesNonPersistedResumeInvocationMessages() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        BackgroundExecutionContext context = new BackgroundExecutionContext();
        TestChatService chatService = new TestChatService(sessionRepo, context, Mode.MARK_COMPLETE);
        BackgroundOrchestrationEngine engine = new BackgroundOrchestrationEngine(chatService, sessionRepo, context, null, null);

        String sessionUuid = "bg-non-persisted-resume";
        sessionRepo.save(new AiSession(
                sessionUuid,
                AiProvider.BACKGROUND_SCHEDULER.name(),
                SessionOriginMode.BACKGROUND,
                "alice",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING, null, null, null, null, null));

        engine.executeBackgroundTurn(sessionUuid, "initial");

        assertEquals(1, chatService.sendCallCount);
        assertEquals(false, chatService.lastPersistUserMessage);
    }

    static final class NoOpMessageChannel implements MessageChannel {
        @Override
        public boolean send(Message<?> message) {
            return true;
        }

        @Override
        public boolean send(Message<?> message, long timeout) {
            return true;
        }
    }
}
