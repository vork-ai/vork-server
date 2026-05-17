package sh.vork.scheduling.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import sh.vork.ai.entity.AiSession;
import sh.vork.database.DatabaseRepository;
import sh.vork.scheduling.domain.DurationType;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;

class AiSchedulerServiceTest {

    @Test
    void scheduleJob_oneShot_persistsAndSchedulesOnce() {
        ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);
        BackgroundOrchestrationEngine orchestrationEngine = mock(BackgroundOrchestrationEngine.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        @SuppressWarnings({"rawtypes"})
        ScheduledFuture future = mock(ScheduledFuture.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenReturn(future);

        AiSchedulerService service = new AiSchedulerService(scheduler, repo, orchestrationEngine, sessionRepo);

        Instant start = Instant.parse("2026-05-17T10:15:30Z");
        ScheduledJob job = new ScheduledJob(
                "job-1",
            "Job One",
                "Do work",
                "sid-1",
                "alice",
                start,
                0,
                DurationType.MINUTES,
                ScheduledJobStatus.ACTIVE);

        ScheduledJob out = service.scheduleJob(job);

        assertEquals("job-1", out.id());
        verify(scheduler).schedule(any(Runnable.class), eq(start));

        ArgumentCaptor<ScheduledJob> saved = ArgumentCaptor.forClass(ScheduledJob.class);
        verify(repo).save(saved.capture());
        assertEquals("job-1", saved.getValue().id());
        assertEquals(ScheduledJobStatus.ACTIVE, saved.getValue().status());
    }

    @Test
    void scheduleJob_repeating_usesFixedRateWithMappedDuration() {
        ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);
        BackgroundOrchestrationEngine orchestrationEngine = mock(BackgroundOrchestrationEngine.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        @SuppressWarnings({"rawtypes"})
        ScheduledFuture future = mock(ScheduledFuture.class);
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenReturn(future);

        AiSchedulerService service = new AiSchedulerService(scheduler, repo, orchestrationEngine, sessionRepo);

        Instant start = Instant.parse("2026-05-17T10:15:30Z");
        ScheduledJob job = new ScheduledJob(
                "job-2",
            "Job Two",
                "Repeat",
                "sid-2",
                "bob",
                start,
                2,
                DurationType.HOURS,
                ScheduledJobStatus.ACTIVE);

        service.scheduleJob(job);

        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(start), eq(Duration.ofHours(2)));
    }

    @Test
    void scheduleJob_blankId_generatesIdAndDefaults() {
        ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);
        BackgroundOrchestrationEngine orchestrationEngine = mock(BackgroundOrchestrationEngine.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        ScheduledFuture future = mock(ScheduledFuture.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenReturn(future);

        AiSchedulerService service = new AiSchedulerService(scheduler, repo, orchestrationEngine, sessionRepo);

        ScheduledJob job = new ScheduledJob(
                " ",
            "Generated Job",
                "One shot",
                "sid-3",
                "charlie",
                null,
                0,
                null,
                null);

        ScheduledJob out = service.scheduleJob(job);

        assertNotNull(out.id());
        assertEquals(DurationType.SECONDS, out.durationType());
        assertEquals(ScheduledJobStatus.ACTIVE, out.status());

        ArgumentCaptor<ScheduledJob> saved = ArgumentCaptor.forClass(ScheduledJob.class);
        verify(repo).save(saved.capture());
        assertNotNull(saved.getValue().startTime());
    }

    @Test
    void cancelJob_cancelsFutureAndMarksPaused() {
        ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);
        BackgroundOrchestrationEngine orchestrationEngine = mock(BackgroundOrchestrationEngine.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        ScheduledFuture future = mock(ScheduledFuture.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenReturn(future);

        AiSchedulerService service = new AiSchedulerService(scheduler, repo, orchestrationEngine, sessionRepo);

        ScheduledJob existing = new ScheduledJob(
                "job-3",
            "Pause Job",
                "Prompt",
                "sid-4",
                "dana",
                Instant.parse("2026-05-17T10:15:30Z"),
                0,
                DurationType.SECONDS,
                ScheduledJobStatus.ACTIVE);

        service.scheduleJob(existing);
        when(repo.get("job-3")).thenReturn(existing);

        service.cancelJob("job-3");

        verify(future).cancel(true);

        ArgumentCaptor<ScheduledJob> saved = ArgumentCaptor.forClass(ScheduledJob.class);
        verify(repo, times(2)).save(saved.capture());
        ScheduledJob paused = saved.getAllValues().get(1);
        assertEquals(ScheduledJobStatus.PAUSED, paused.status());
    }
}
