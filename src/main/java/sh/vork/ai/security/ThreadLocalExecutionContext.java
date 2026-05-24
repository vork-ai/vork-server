package sh.vork.ai.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ThreadLocalExecutionContext {

    private static final ThreadLocal<Map<String, String>> CONTEXT =
            ThreadLocal.withInitial(ConcurrentHashMap::new);

    private ThreadLocalExecutionContext() {
    }

    public static void put(String key, String value) {
        if (key == null || key.isBlank()) {
            return;
        }
        CONTEXT.get().put(key, value == null ? "" : value);
    }

    public static String get(String key) {
        return CONTEXT.get().get(key);
    }

    public static Map<String, String> snapshot() {
        return Map.copyOf(CONTEXT.get());
    }

    public static void clear() {
        CONTEXT.remove();
    }
}