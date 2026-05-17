package sh.vork.scheduling.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.database.DatabaseRepository;
import sh.vork.scheduling.domain.DurationType;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;

/**
 * Core programmatic scheduler for persistent AI jobs.
 */
@Service
public class AiSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(AiSchedulerService.class);

    private final ThreadPoolTaskScheduler taskScheduler;
    private final DatabaseRepository<ScheduledJob> jobRepository;
    private final BackgroundOrchestrationEngine backgroundOrchestrationEngine;
    private final DatabaseRepository<AiSession> sessionRepository;

    private final Map<String, ScheduledFuture<?>> activeFutures = new ConcurrentHashMap<>();

    public AiSchedulerService(ThreadPoolTaskScheduler taskScheduler,
                              DatabaseRepository<ScheduledJob> jobRepository,
                              BackgroundOrchestrationEngine backgroundOrchestrationEngine,
                              DatabaseRepository<AiSession> sessionRepository) {
        this.taskScheduler = taskScheduler;
        this.jobRepository = jobRepository;
        this.backgroundOrchestrationEngine = backgroundOrchestrationEngine;
        this.sessionRepository = sessionRepository;
    }

    public ScheduledJob scheduleJob(ScheduledJob job) {
        String id = ensureId(job.id());

        ScheduledJob normalized = new ScheduledJob(
                id,
            job.name(),
                job.aiPrompt(),
                job.sessionUuid(),
                job.username(),
                job.startTime() == null ? Instant.now() : job.startTime(),
                job.repeatDuration(),
                job.durationType() == null ? DurationType.SECONDS : job.durationType(),
                job.status() == null ? ScheduledJobStatus.ACTIVE : job.status());

        ScheduledFuture<?> existing = activeFutures.remove(id);
        if (existing != null) {
            existing.cancel(true);
        }

        jobRepository.save(normalized);

        AiJobRunner runner = new AiJobRunner(normalized, backgroundOrchestrationEngine, jobRepository, sessionRepository);
        Instant start = normalized.startTime();

        ScheduledFuture<?> future;
        if (normalized.repeatDuration() <= 0) {
            future = taskScheduler.schedule(runner, start);
        } else {
            Duration interval = toDuration(normalized.repeatDuration(), normalized.durationType());
            future = taskScheduler.scheduleAtFixedRate(runner, start, interval);
        }

        activeFutures.put(id, future);
        log.info("Scheduled job active [id={}, start={}, repeat={}, unit={}]",
                id, start, normalized.repeatDuration(), normalized.durationType());
        return normalized;
    }

    public void cancelJob(String jobId) {
        ScheduledFuture<?> future = activeFutures.remove(jobId);
        if (future != null) {
            future.cancel(true);
        }

        ScheduledJob existing = jobRepository.get(jobId);
        if (existing == null) {
            return;
        }

        jobRepository.save(new ScheduledJob(
                existing.id(),
            existing.name(),
                existing.aiPrompt(),
                existing.sessionUuid(),
                existing.username(),
                existing.startTime(),
                existing.repeatDuration(),
                existing.durationType(),
                ScheduledJobStatus.PAUSED));

        log.info("Scheduled job paused [id={}]", jobId);
    }

    public void resumeBackgroundSession(String sessionUuid) {
        log.info("Resuming background session [trackingSession={}]", sessionUuid);
        backgroundOrchestrationEngine.executeBackgroundTurn(sessionUuid, null);
        reconcileOneShotJobCompletion(sessionUuid);
    }

    private void reconcileOneShotJobCompletion(String trackingSessionUuid) {
        String marker = "-run-";
        int idx = trackingSessionUuid == null ? -1 : trackingSessionUuid.indexOf(marker);
        if (idx <= 0) {
            return;
        }

        String jobId = trackingSessionUuid.substring(0, idx);
        ScheduledJob job = jobRepository.get(jobId);
        if (job == null) {
            return;
        }
        if (job.repeatDuration() > 0) {
            return;
        }

        AiSession finalSession = sessionRepository.get(trackingSessionUuid);
        if (finalSession != null && finalSession.status() == AiSessionStatus.COMPLETED) {
            jobRepository.save(new ScheduledJob(
                    job.id(),
                    job.name(),
                    job.aiPrompt(),
                    job.sessionUuid(),
                    job.username(),
                    job.startTime(),
                    job.repeatDuration(),
                    job.durationType(),
                    ScheduledJobStatus.COMPLETED));
            log.info("Scheduled AI job marked completed after authorization resume [id={}, trackingSession={}]",
                    job.id(), trackingSessionUuid);
        } else {
            log.info("Authorization resume finished without terminal completion [id={}, trackingSession={}, finalSessionStatus={}]",
                    job.id(), trackingSessionUuid, finalSession == null ? "<missing>" : finalSession.status());
        }
    }

    private static Duration toDuration(long amount, DurationType type) {
        return switch (type) {
            case SECONDS -> Duration.ofSeconds(amount);
            case MINUTES -> Duration.ofMinutes(amount);
            case HOURS -> Duration.ofHours(amount);
            case DAYS -> Duration.ofDays(amount);
            case WEEKS -> Duration.ofDays(amount * 7);
            case MONTHS -> Duration.ofDays(amount * 30);
        };
    }

    private static String ensureId(String id) {
        if (id == null || id.isBlank()) {
            return java.util.UUID.randomUUID().toString();
        }
        return id;
    }
}
