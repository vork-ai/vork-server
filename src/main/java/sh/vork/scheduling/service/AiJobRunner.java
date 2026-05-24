package sh.vork.scheduling.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.database.DatabaseRepository;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;

/**
 * Runnable wrapper for executing one scheduled AI job invocation.
 */
public class AiJobRunner implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AiJobRunner.class);

    private final ScheduledJob job;
    private final BackgroundOrchestrationEngine backgroundOrchestrationEngine;
    private final DatabaseRepository<ScheduledJob> jobRepository;
    private final DatabaseRepository<AiSession> sessionRepository;

    public AiJobRunner(ScheduledJob job,
                       BackgroundOrchestrationEngine backgroundOrchestrationEngine,
                       DatabaseRepository<ScheduledJob> jobRepository,
                       DatabaseRepository<AiSession> sessionRepository) {
        this.job = job;
        this.backgroundOrchestrationEngine = backgroundOrchestrationEngine;
        this.jobRepository = jobRepository;
        this.sessionRepository = sessionRepository;
    }

    @Override
    public void run() {
        String trackingSessionUuid = job.id() + "-run-" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        try {
            AiChatMessage seedMessage = new AiChatMessage(
                UUID.randomUUID().toString(),
                "USER",
                job.aiPrompt(),
                now,
                null);

            AiSession trackingSession = new AiSession(
                trackingSessionUuid,
                AiProvider.BACKGROUND_SCHEDULER.name(),
                SessionOriginMode.BACKGROUND,
                job.username(),
                "Untitled",
                now,
                0,
                List.of(seedMessage),
                AiSessionStatus.RUNNING);
            sessionRepository.save(trackingSession);

            SecurityContextHolder.getContext()
                    .setAuthentication(new SystemBackgroundAuthentication(job.username()));

            log.info("Executing scheduled AI job [id={}, name={}, user={}, sourceSession={}, trackingSession={}]",
                job.id(), job.name(), job.username(), job.sessionUuid(), trackingSessionUuid);

            executeBackgroundTurn(trackingSessionUuid);

            AiSession finalSession = sessionRepository.get(trackingSessionUuid);
                log.info("Scheduled AI job run finished [id={}, trackingSession={}, repeatDuration={}, finalSessionStatus={}]",
                    job.id(),
                    trackingSessionUuid,
                    job.repeatDuration(),
                    finalSession == null ? "<missing>" : finalSession.status());

            if (job.repeatDuration() == 0
                    && finalSession != null
                    && finalSession.status() == AiSessionStatus.COMPLETED) {
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
                log.info("Scheduled AI job marked completed [id={}]", job.id());
                    } else if (job.repeatDuration() == 0
                        && finalSession != null
                        && finalSession.status() == AiSessionStatus.AWAITING_INPUT) {
                    jobRepository.save(new ScheduledJob(
                        job.id(),
                        job.name(),
                        job.aiPrompt(),
                        job.sessionUuid(),
                        job.username(),
                        job.startTime(),
                        job.repeatDuration(),
                        job.durationType(),
                        ScheduledJobStatus.PAUSED));
                    log.info("Scheduled AI job paused awaiting authorization [id={}, trackingSession={}]",
                        job.id(), trackingSessionUuid);
            } else if (job.repeatDuration() > 0) {
                log.info("Scheduled AI job remains ACTIVE because it is recurring [id={}, repeatDuration={}, unit={}]",
                        job.id(), job.repeatDuration(), job.durationType());
            } else if (finalSession == null) {
                log.warn("Scheduled AI job remains ACTIVE because tracking session is missing [id={}, trackingSession={}]",
                        job.id(), trackingSessionUuid);
            } else {
                log.info("Scheduled AI job remains ACTIVE because final tracking session is not COMPLETED [id={}, trackingSession={}, finalStatus={}]",
                        job.id(), trackingSessionUuid, finalSession.status());
            }
        } catch (Exception ex) {
            log.error("Scheduled AI job failed [id={}]: {}", job.id(), ex.getMessage(), ex);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    public void executeBackgroundTurn(String sessionUuid) {
        backgroundOrchestrationEngine.executeBackgroundTurn(sessionUuid, job.aiPrompt());
    }
}
