package sh.vork.ai.memory;

import java.util.Map;

public interface SessionEnvironmentService {

    void setEnv(String sessionUuid, String key, String value);

    void deleteEnv(String sessionUuid, String key);

    Map<String, String> getEnv(String sessionUuid);
}
