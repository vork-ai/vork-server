package sh.vork.ssl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Checks the active SSL certificate daily and triggers a Let's Encrypt renewal
 * when fewer than 30 days of validity remain.
 *
 * <p>Only acts for certificates with type {@code "lets-encrypt"}.
 * Self-signed certificates must be regenerated manually from the settings page.
 */
@Component
public class SslRenewalScheduler {

    private static final Logger log = LoggerFactory.getLogger(SslRenewalScheduler.class);
    private static final long RENEW_THRESHOLD_DAYS = 30;

    private final SslService sslService;
    private final LetsEncryptService letsEncryptService;

    public SslRenewalScheduler(SslService sslService, LetsEncryptService letsEncryptService) {
        this.sslService         = sslService;
        this.letsEncryptService = letsEncryptService;
    }

    /**
     * Runs at 03:00 every day.  Renews the Let's Encrypt certificate if it
     * expires within {@value #RENEW_THRESHOLD_DAYS} days.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void checkAndRenewCertificate() {
        log.debug("ENTER SslRenewalScheduler.checkAndRenewCertificate");

        String type = sslService.getCertType();
        if (!"lets-encrypt".equals(type)) {
            log.debug("SSL: Certificate type is '{}' — auto-renewal skipped", type);
            return;
        }

        long daysLeft = sslService.daysUntilExpiry();
        log.info("SSL: Renewal check — {} day(s) remaining", daysLeft);

        if (daysLeft < 0) {
            log.warn("SSL: Could not determine certificate expiry — skipping renewal");
            return;
        }

        if (daysLeft <= RENEW_THRESHOLD_DAYS) {
            log.info("SSL: Certificate expires in {} day(s) — triggering Let's Encrypt renewal", daysLeft);
            try {
                letsEncryptService.renewCertificate();
            } catch (Exception e) {
                log.error("SSL: Renewal failed: {}", e.getMessage(), e);
            }
        } else {
            log.debug("EXIT SslRenewalScheduler.checkAndRenewCertificate: {} days left, no renewal needed", daysLeft);
        }
    }
}
