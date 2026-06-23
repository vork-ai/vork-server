package sh.vork.ai.memory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fallback used when no AI session repository bean is available.
 */
public class InMemorySessionEnvironmentService implements SessionEnvironmentService {

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> store = new ConcurrentHashMap<>();

    @Override
    public void setEnv(String sessionUuid, String key, String value) {
        if (sessionUuid == null || sessionUuid.isBlank() || key == null || key.isBlank()) {
            return;
        }

        ConcurrentHashMap<String, String> env =
                store.computeIfAbsent(sessionUuid, ignored -> new ConcurrentHashMap<>());
        env.put(key, value == null ? "" : value);
    }

    @Override
    public void deleteEnv(String sessionUuid, String key) {
        if (sessionUuid == null || sessionUuid.isBlank() || key == null || key.isBlank()) {
            return;
        }

        ConcurrentHashMap<String, String> env = store.get(sessionUuid);
        if (env == null) {
            return;
        }

        env.remove(key);
        if (env.isEmpty()) {
            store.remove(sessionUuid);
        }
    }

    @Override
    public Map<String, String> getEnv(String sessionUuid) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return Map.of();
        }

        Map<String, String> env = store.get(sessionUuid);
        if (env == null || env.isEmpty()) {
            return Map.of();
        }

        return Map.copyOf(new LinkedHashMap<>(env));
    }
}
