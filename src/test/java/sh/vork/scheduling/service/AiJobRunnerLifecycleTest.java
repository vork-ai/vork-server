package sh.vork.scheduling.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.database.mock.MapDatabaseRepository;
import sh.vork.scheduling.domain.DurationType;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;

class AiJobRunnerLifecycleTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void oneShotJob_marksCompletedWhenTrackingSessionCompletes() {
        MapDatabaseRepository<ScheduledJob> jobRepo = new MapDatabaseRepository<>(ScheduledJob.class);
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);

        ScheduledJob job = new ScheduledJob(
                "job-life-1",
                "Summarize Site",
                "Fetch and summarize",
                "source-session",
                "alice",
                Instant.parse("2026-05-17T18:10:00Z"),
                0,
                DurationType.MINUTES,
                ScheduledJobStatus.ACTIVE);

        jobRepo.save(job);

        CompletingEngine engine = new CompletingEngine(sessionRepo, AiSessionStatus.COMPLETED);
        AiJobRunner runner = new AiJobRunner(job, engine, jobRepo, sessionRepo);

        runner.run();

        ScheduledJob saved = jobRepo.get("job-life-1");
        assertEquals(ScheduledJobStatus.COMPLETED, saved.status());
    }

    @Test
    void oneShotJob_pausesWhenTrackingSessionAwaitsAuthorization() {
        MapDatabaseRepository<ScheduledJob> jobRepo = new MapDatabaseRepository<>(ScheduledJob.class);
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);

        ScheduledJob job = new ScheduledJob(
                "job-life-2",
                "Summarize Site",
                "Fetch and summarize",
                "source-session",
                "alice",
                Instant.parse("2026-05-17T18:10:00Z"),
                0,
                DurationType.MINUTES,
                ScheduledJobStatus.ACTIVE);

        jobRepo.save(job);

        CompletingEngine engine = new CompletingEngine(sessionRepo, AiSessionStatus.AWAITING_AUTHORIZATION);
        AiJobRunner runner = new AiJobRunner(job, engine, jobRepo, sessionRepo);

        runner.run();

        ScheduledJob saved = jobRepo.get("job-life-2");
        assertEquals(ScheduledJobStatus.PAUSED, saved.status());
    }

    private static final class CompletingEngine extends BackgroundOrchestrationEngine {

        private final MapDatabaseRepository<AiSession> sessionRepo;
        private final AiSessionStatus targetStatus;

        private CompletingEngine(MapDatabaseRepository<AiSession> sessionRepo, AiSessionStatus targetStatus) {
            super(null, null, new BackgroundExecutionContext());
            this.sessionRepo = sessionRepo;
            this.targetStatus = targetStatus;
        }

        @Override
        public void executeBackgroundTurn(String sessionUuid, String initialPrompt) {
            AiSession current = sessionRepo.get(sessionUuid);
            if (current == null) {
                return;
            }

            sessionRepo.save(new AiSession(
                    current.uuid(),
                    current.provider(),
                    SessionOriginMode.BACKGROUND,
                    current.username(),
                    current.createdAt(),
                    current.currentRoundCount(),
                    withSyntheticAssistantMessage(current.messages()),
                    targetStatus));
        }

        private static List<AiChatMessage> withSyntheticAssistantMessage(List<AiChatMessage> existing) {
            java.util.ArrayList<AiChatMessage> updated = new java.util.ArrayList<>(existing);
            updated.add(new AiChatMessage(
                    java.util.UUID.randomUUID().toString(),
                    "ASSISTANT",
                    "Synthetic completion",
                    System.currentTimeMillis(),
                    null));
            return List.copyOf(updated);
        }
    }
}
