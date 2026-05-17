package sh.vork.ai.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;
import sh.vork.scheduling.service.AiSchedulerService;

class AiConfigScheduleTaskToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void scheduleBackgroundTask_acceptsRelativeStartTime() throws Exception {
                RecordingSchedulerService scheduler = new RecordingSchedulerService();
                ObjectProvider<AiSchedulerService> provider = new SingleObjectProvider<>(scheduler);

        AiConfig config = new AiConfig(
                null,
                null,
                objectMapper);

        ToolCallback tool = config.scheduleBackgroundTask(provider);

        Instant before = Instant.now();
        String out = tool.call("""
                {
                  "jobName": "Summarize Jadaptive",
                  "backgroundPrompt": "Fetch https://jadaptive.com and summarize the content",
                  "startIsoTime": "in 1 minute",
                  "repeatInterval": 0,
                  "durationType": "MINUTES"
                }
                """);
        Instant after = Instant.now();

        Map<String, Object> response = objectMapper.readValue(out, new TypeReference<>() {
        });
        assertEquals("scheduled", response.get("status"));
        assertEquals("job-1", response.get("jobId"));

        ScheduledJob scheduled = scheduler.lastScheduled;

        Instant minExpected = before.plus(50, ChronoUnit.SECONDS);
        Instant maxExpected = after.plus(70, ChronoUnit.SECONDS);
        assertTrue(!scheduled.startTime().isBefore(minExpected), "startTime should be about one minute in the future");
        assertTrue(!scheduled.startTime().isAfter(maxExpected), "startTime should be about one minute in the future");
        assertEquals(0L, scheduled.repeatDuration());
    }

    @Test
    void scheduleBackgroundTask_defaultsToImmediateWhenStartMissing() throws Exception {
                RecordingSchedulerService scheduler = new RecordingSchedulerService();
                ObjectProvider<AiSchedulerService> provider = new SingleObjectProvider<>(scheduler);

        AiConfig config = new AiConfig(
                null,
                null,
                objectMapper);

        ToolCallback tool = config.scheduleBackgroundTask(provider);

        Instant before = Instant.now();
        String out = tool.call("""
                {
                  "jobName": "Immediate summary",
                  "backgroundPrompt": "Fetch https://jadaptive.com and summarize the content",
                  "repeatInterval": 0,
                  "durationType": "MINUTES"
                }
                """);
        Instant after = Instant.now();

        Map<String, Object> response = objectMapper.readValue(out, new TypeReference<>() {
        });
        assertEquals("scheduled", response.get("status"));

                ScheduledJob scheduled = scheduler.lastScheduled;

        assertTrue(!scheduled.startTime().isBefore(before.minusSeconds(1)), "startTime should default to now");
        assertTrue(!scheduled.startTime().isAfter(after.plusSeconds(1)), "startTime should default to now");
    }

        private static final class RecordingSchedulerService extends AiSchedulerService {
                private ScheduledJob lastScheduled;
                private int sequence = 0;

                private RecordingSchedulerService() {
                        super(null, null, null, null);
                }

                @Override
                public ScheduledJob scheduleJob(ScheduledJob job) {
                        this.lastScheduled = job;
                        sequence++;
                        return new ScheduledJob(
                                        "job-" + sequence,
                                        job.name(),
                                        job.aiPrompt(),
                                        job.sessionUuid(),
                                        job.username(),
                                        job.startTime(),
                                        job.repeatDuration(),
                                        job.durationType(),
                                        job.status() == null ? ScheduledJobStatus.ACTIVE : job.status());
                }
        }

        private static final class SingleObjectProvider<T> implements ObjectProvider<T> {
                private final T value;

                private SingleObjectProvider(T value) {
                        this.value = value;
                }

                @Override
                public T getObject(Object... args) {
                        return value;
                }

                @Override
                public T getIfAvailable() {
                        return value;
                }

                @Override
                public T getIfUnique() {
                        return value;
                }

                @Override
                public T getObject() {
                        return value;
                }

                @Override
                public Iterator<T> iterator() {
                        return java.util.List.of(value).iterator();
                }

                @Override
                public Stream<T> stream() {
                        return java.util.stream.Stream.of(value);
                }

                @Override
                public Stream<T> orderedStream() {
                        return java.util.stream.Stream.of(value);
                }
        }
}
