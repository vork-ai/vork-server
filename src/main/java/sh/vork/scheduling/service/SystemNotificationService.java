package sh.vork.scheduling.service;

public interface SystemNotificationService {
    void notifyOfflineOperator(String toolName, String arguments, String sessionUuid, String eventId);
}
