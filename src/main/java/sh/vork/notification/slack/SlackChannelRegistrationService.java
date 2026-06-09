package sh.vork.notification.slack;

import com.jadaptive.orm.DatabaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import sh.vork.notification.GlobalAddress;
import sh.vork.notification.NotificationMediaType;
import sh.vork.notification.NotificationProviderConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the in-memory state for admin-initiated Slack channel registration.
 *
 * <p>The registration flow:
 * <ol>
 *   <li>An admin calls
 *       {@code POST /api/notification/slack/{configId}/channel-registration/start} —
 *       this generates a one-time code.</li>
 *   <li>A team member adds the Vork bot to the target Slack channel and posts
 *       {@code register CODE} inside it.</li>
 *   <li>{@link SlackChannelRegistrationConsumer} calls
 *       {@link #complete(String, String, String, String)} which saves a
 *       {@link GlobalAddress} and marks the registration complete.</li>
 *   <li>The admin UI polls {@link #checkStatus(String)} until {@code "complete"}.</li>
 * </ol>
 *
 * <p>Pending registrations expire after 15 minutes.
 */
@Service
public class SlackChannelRegistrationService {

    private static final Logger   log    = LoggerFactory.getLogger(SlackChannelRegistrationService.class);
    private static final Duration EXPIRY = Duration.ofMinutes(15);

    private final DatabaseRepository<NotificationProviderConfig> configRepo;
    private final DatabaseRepository<GlobalAddress>              globalAddressRepo;
    
    /** registrationId → pending */
    private final ConcurrentHashMap<String, PendingChannelRegistration> byId   = new ConcurrentHashMap<>();
    /** code → pending */
    private final ConcurrentHashMap<String, PendingChannelRegistration> byCode = new ConcurrentHashMap<>();

    public SlackChannelRegistrationService(
            DatabaseRepository<NotificationProviderConfig> configRepo,
            DatabaseRepository<GlobalAddress> globalAddressRepo,
            SlackApiClient slackApiClient) {
        this.configRepo       = configRepo;
        this.globalAddressRepo = globalAddressRepo;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts a new channel registration for the given Slack config.
     *
     * @param configId the Slack {@link NotificationProviderConfig} UUID
     * @return registration info with the one-time code and instructions
     * @throws IllegalArgumentException if the config is not found or not Slack
     */
    public ChannelRegistrationInfo startChannelRegistration(String configId) {
        log.debug("ENTER startChannelRegistration: [configId={}]", configId);

        NotificationProviderConfig config = configRepo.get(configId);
        if (config == null || !"slack".equals(config.providerKey())) {
            throw new IllegalArgumentException("Slack provider config not found: " + configId);
        }

        String code           = UUID.randomUUID().toString().replace("-", "")
                                    .substring(0, 16).toUpperCase();
        String registrationId = UUID.randomUUID().toString();

        PendingChannelRegistration pending = new PendingChannelRegistration(
                registrationId, configId, code, Instant.now());
        byId.put(registrationId, pending);
        byCode.put(code, pending);

        log.info("Slack channel registration started [configId={}, regId={}]", configId, registrationId);
        return new ChannelRegistrationInfo(registrationId, code);
    }

    /**
     * Called by {@link SlackChannelRegistrationConsumer} when {@code register CODE}
     * is received in a non-DM Slack channel.
     *
     * @param configId    the provider config ID for the receiving bot
     * @param code        the code posted in the channel
     * @param channelId   the Slack channel ID to register as a global address
     * @param channelName display name of the channel (used as label)
     * @return {@code true} if registration succeeded; {@code false} if the code is
     *         unknown or expired
     */
    public boolean complete(String configId, String code,
                             String channelId, String channelName) {
        log.debug("ENTER complete: [code={}, channelId={}, configId={}]", code, channelId, configId);

        PendingChannelRegistration pending = byCode.get(code);
        if (pending == null) {
            log.debug("No pending channel registration for code");
            return false;
        }
        if (!pending.configId.equals(configId)) {
            log.debug("Code belongs to a different configId — ignoring");
            return false;
        }
        if (Instant.now().isAfter(pending.createdAt.plus(EXPIRY))) {
            byCode.remove(code);
            byId.remove(pending.registrationId);
            log.debug("Channel registration code expired [code={}]", code);
            return false;
        }
        if (pending.complete) return true; // idempotent

        String label = (channelName != null && !channelName.isBlank()) ? channelName : channelId;
        GlobalAddress address = new GlobalAddress(
                UUID.randomUUID().toString(),
                configId,
                label,
                NotificationMediaType.SLACK,
                channelId,
                System.currentTimeMillis());
        globalAddressRepo.save(address);

        pending.markComplete(address.uuid());
        byCode.remove(code);

        log.info("Slack channel registration completed [configId={}, channel={}, label={}]",
                configId, channelId, label);
        return true;
    }

    /**
     * Polls the status of a pending channel registration.
     */
    public ChannelRegistrationStatus checkStatus(String registrationId) {
        PendingChannelRegistration pending = byId.get(registrationId);
        if (pending == null) return new ChannelRegistrationStatus("expired", null);

        if (Instant.now().isAfter(pending.createdAt.plus(EXPIRY))) {
            byId.remove(registrationId);
            byCode.remove(pending.code);
            return new ChannelRegistrationStatus("expired", null);
        }
        if (pending.complete) {
            byId.remove(registrationId);
            return new ChannelRegistrationStatus("complete", pending.addressId);
        }
        return new ChannelRegistrationStatus("pending", null);
    }

    // ── Value types ───────────────────────────────────────────────────────────

    public record ChannelRegistrationInfo(String registrationId, String code) {}

    public record ChannelRegistrationStatus(String status, String addressId) {}

    private static class PendingChannelRegistration {
        final String  registrationId;
        final String  configId;
        final String  code;
        final Instant createdAt;

        volatile boolean complete  = false;
        volatile String  addressId = null;

        PendingChannelRegistration(String registrationId, String configId,
                                    String code, Instant createdAt) {
            this.registrationId = registrationId;
            this.configId       = configId;
            this.code           = code;
            this.createdAt      = createdAt;
        }

        void markComplete(String addressId) {
            this.addressId = addressId;
            this.complete  = true;
        }
    }
}
