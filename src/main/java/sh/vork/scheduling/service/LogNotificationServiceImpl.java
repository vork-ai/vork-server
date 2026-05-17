package sh.vork.scheduling.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LogNotificationServiceImpl implements SystemNotificationService {

    private static final Logger log = LoggerFactory.getLogger(LogNotificationServiceImpl.class);

    @Override
        public void notifyOfflineOperator(String toolName, String arguments, String sessionUuid, String eventId) {
        String approveUrl = String.format(
            "http://localhost:8080/ui/authorize.html?sessionUuid=%s&eventId=%s",
            sessionUuid,
            eventId == null ? "" : eventId);

        String message = String.format(
            "[SECURITY ALERT] Background task requested restricted tool [%s] [session=%s, event=%s]. Approve via: %s",
                toolName,
            sessionUuid,
            eventId,
                approveUrl);

        log.warn("{}", message);
        log.warn("Restricted tool args snapshot: {}", arguments == null ? "{}" : arguments);
        System.out.println(message);
    }
}
