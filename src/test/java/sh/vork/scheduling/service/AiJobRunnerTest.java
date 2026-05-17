package sh.vork.scheduling.service;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.database.DatabaseRepository;
import sh.vork.scheduling.domain.DurationType;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;

class AiJobRunnerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void run_oneShot_executesAiAndMarksCompleted() {
        BackgroundOrchestrationEngine orchestrationEngine = mock(BackgroundOrchestrationEngine.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);

        ScheduledJob job = new ScheduledJob(
                "job-1",
            "Run Once Job",
                "Run once",
                "sid-1",
                "alice",
                Instant.parse("2026-05-17T10:15:30Z"),
                0,
                DurationType.MINUTES,
                ScheduledJobStatus.ACTIVE);

        when(sessionRepo.get(org.mockito.ArgumentMatchers.anyString())).thenReturn(new AiSession(
            "sid",
            "BACKGROUND_SCHEDULER",
            "alice",
            Instant.now().toEpochMilli(),
            java.util.List.of(),
            AiSessionStatus.COMPLETED));

        AiJobRunner runner = new AiJobRunner(job, orchestrationEngine, repo, sessionRepo);
        runner.run();

        verify(orchestrationEngine).executeBackgroundTurn(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("Run once"));
        verify(repo).save(new ScheduledJob(
                "job-1",
            "Run Once Job",
                "Run once",
                "sid-1",
                "alice",
                Instant.parse("2026-05-17T10:15:30Z"),
                0,
                DurationType.MINUTES,
                ScheduledJobStatus.COMPLETED));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void run_repeating_executesAiWithoutCompletionUpdate() {
        BackgroundOrchestrationEngine orchestrationEngine = mock(BackgroundOrchestrationEngine.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);

        ScheduledJob job = new ScheduledJob(
                "job-2",
            "Repeat Job",
                "Repeat",
                "sid-2",
                "bob",
                Instant.parse("2026-05-17T10:15:30Z"),
                5,
                DurationType.MINUTES,
                ScheduledJobStatus.ACTIVE);

        AiJobRunner runner = new AiJobRunner(job, orchestrationEngine, repo, sessionRepo);
        runner.run();

        verify(orchestrationEngine).executeBackgroundTurn(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("Repeat"));
        verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void run_aiFailure_doesNotThrowAndClearsSecurityContext() {
        BackgroundOrchestrationEngine orchestrationEngine = mock(BackgroundOrchestrationEngine.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);

        ScheduledJob job = new ScheduledJob(
                "job-3",
            "Failing Job",
                "Fails",
                "sid-3",
                "carol",
                Instant.parse("2026-05-17T10:15:30Z"),
                0,
                DurationType.MINUTES,
                ScheduledJobStatus.ACTIVE);

        doThrow(new RuntimeException("boom"))
            .when(orchestrationEngine)
            .executeBackgroundTurn(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("Fails"));

        AiJobRunner runner = new AiJobRunner(job, orchestrationEngine, repo, sessionRepo);
        runner.run();

        verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
