package sh.vork.scheduling.lifecycle;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import sh.vork.database.DatabaseRepository;
import sh.vork.scheduling.domain.DurationType;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;
import sh.vork.scheduling.service.AiSchedulerService;

class SchedulerLifecycleManagerTest {

    @Test
    void onReady_reschedulesOnlyActiveJobs() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);
        AiSchedulerService schedulerService = mock(AiSchedulerService.class);

        ScheduledJob active = new ScheduledJob(
                "active-1", "Active Job", "a", "sid", "alice",
                Instant.parse("2026-05-17T10:15:30Z"), 10, DurationType.MINUTES, ScheduledJobStatus.ACTIVE);
        ScheduledJob paused = new ScheduledJob(
                "paused-1", "Paused Job", "b", "sid", "bob",
                Instant.parse("2026-05-17T10:15:30Z"), 10, DurationType.MINUTES, ScheduledJobStatus.PAUSED);
        ScheduledJob completed = new ScheduledJob(
                "completed-1", "Completed Job", "c", "sid", "carol",
                Instant.parse("2026-05-17T10:15:30Z"), 0, DurationType.MINUTES, ScheduledJobStatus.COMPLETED);

        when(repo.list(0, Integer.MAX_VALUE)).thenReturn(Stream.of(active, paused, completed));

        SchedulerLifecycleManager manager = new SchedulerLifecycleManager(repo, schedulerService);
        manager.onReady();

        verify(schedulerService).scheduleJob(active);
        verifyNoMoreInteractions(schedulerService);
    }
}
