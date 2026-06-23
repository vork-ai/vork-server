package sh.vork.ai.memory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import sh.vork.ai.entity.AiSession;
import sh.vork.orm.DatabaseRepository;

/**
 * Repository-backed session environment storage.
 *
 * <p>This implementation updates the {@link AiSession#environmentVariables()}
 * map directly through the configured {@link DatabaseRepository} so behavior is
 * consistent across Mongo, Redis, and Nitrite backends.
 */
public class RepositorySessionEnvironmentService implements SessionEnvironmentService {

    private final DatabaseRepository<AiSession> sessionRepository;

    public RepositorySessionEnvironmentService(DatabaseRepository<AiSession> sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    public void setEnv(String sessionUuid, String key, String value) {
        if (sessionUuid == null || sessionUuid.isBlank() || key == null || key.isBlank()) {
            return;
        }

        AiSession session = sessionRepository.get(sessionUuid);
        if (session == null) {
            return;
        }

        String normalizedValue = value == null ? "" : value;
        Map<String, String> merged = new ConcurrentHashMap<>(session.environmentVariables());
        merged.put(key, normalizedValue);

        sessionRepository.save(new AiSession(
                session.uuid(),
                session.provider(),
                session.originMode(),
                session.username(),
                session.name(),
                session.createdAt(),
                session.currentRoundCount(),
                session.messages(),
                merged,
                session.status(),
                session.activeAgentTemplateId(),
                session.modelId(),
                session.skillStack(),
                session.sessionSkillUuids(),
                session.sessionToolIds()));
    }

    @Override
    public void deleteEnv(String sessionUuid, String key) {
        if (sessionUuid == null || sessionUuid.isBlank() || key == null || key.isBlank()) {
            return;
        }

        AiSession session = sessionRepository.get(sessionUuid);
        if (session == null || session.environmentVariables() == null || session.environmentVariables().isEmpty()) {
            return;
        }

        Map<String, String> merged = new ConcurrentHashMap<>(session.environmentVariables());
        merged.remove(key);

        sessionRepository.save(new AiSession(
                session.uuid(),
                session.provider(),
                session.originMode(),
                session.username(),
                session.name(),
                session.createdAt(),
                session.currentRoundCount(),
                session.messages(),
                merged,
                session.status(),
                session.activeAgentTemplateId(),
                session.modelId(),
                session.skillStack(),
                session.sessionSkillUuids(),
                session.sessionToolIds()));
    }

    @Override
    public Map<String, String> getEnv(String sessionUuid) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return Map.of();
        }

        AiSession session = sessionRepository.get(sessionUuid);
        if (session == null || session.environmentVariables() == null || session.environmentVariables().isEmpty()) {
            return Map.of();
        }

        return Map.copyOf(new LinkedHashMap<>(session.environmentVariables()));
    }
}
