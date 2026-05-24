package sh.vork.scheduling.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.database.mock.MapDatabaseRepository;
import sh.vork.scheduling.domain.DurationType;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;

class AiSchedulerServiceResumeCompletionTest {

    @Test
    void resumeBackgroundSession_marksOneShotJobCompletedWhenTrackingSessionCompletes() {
        MapDatabaseRepository<ScheduledJob> jobRepo = new MapDatabaseRepository<>(ScheduledJob.class);
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);

        String jobId = "job-resume-1";
        String trackingSessionUuid = jobId + "-run-abc";

        jobRepo.save(new ScheduledJob(
                jobId,
                "One-shot",
                "prompt",
                "source",
                "alice",
                Instant.parse("2026-05-17T18:00:00Z"),
                0,
                DurationType.MINUTES,
                ScheduledJobStatus.PAUSED));

        sessionRepo.save(new AiSession(
                trackingSessionUuid,
                "BACKGROUND_SCHEDULER",
                SessionOriginMode.BACKGROUND,
                "alice",
            "Untitled",
                System.currentTimeMillis(),
                1,
                List.of(new AiChatMessage("m1", "USER", "prompt", System.currentTimeMillis(), null)),
                AiSessionStatus.RUNNING));

        CompletingEngine engine = new CompletingEngine(sessionRepo);
        AiSchedulerService service = new AiSchedulerService(null, jobRepo, engine, sessionRepo);

        service.resumeBackgroundSession(trackingSessionUuid);

        ScheduledJob updated = jobRepo.get(jobId);
        assertEquals(ScheduledJobStatus.COMPLETED, updated.status());
    }

    private static final class CompletingEngine extends BackgroundOrchestrationEngine {
        private final MapDatabaseRepository<AiSession> sessionRepo;

        private CompletingEngine(MapDatabaseRepository<AiSession> sessionRepo) {
            super(null, null, new BackgroundExecutionContext());
            this.sessionRepo = sessionRepo;
        }

        @Override
        public void executeBackgroundTurn(String sessionUuid, String initialPrompt) {
            AiSession s = sessionRepo.get(sessionUuid);
            if (s == null) {
                return;
            }
            sessionRepo.save(new AiSession(
                    s.uuid(),
                    s.provider(),
                    s.originMode(),
                    s.username(),
                    s.name(),
                    s.createdAt(),
                    s.currentRoundCount(),
                    s.messages(),
                    AiSessionStatus.COMPLETED));
        }
    }
}
