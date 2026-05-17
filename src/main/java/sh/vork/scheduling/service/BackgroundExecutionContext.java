package sh.vork.scheduling.service;

import org.springframework.stereotype.Component;

@Component
public class BackgroundExecutionContext {

    private final ThreadLocal<Boolean> executionComplete = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public void markExecutionComplete() {
        executionComplete.set(Boolean.TRUE);
    }

    public boolean isExecutionComplete() {
        return Boolean.TRUE.equals(executionComplete.get());
    }

    public void clear() {
        executionComplete.remove();
    }
}
