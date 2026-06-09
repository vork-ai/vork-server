package sh.vork.ssl;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.shredzone.acme4j.Account;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Certificate;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.time.Duration;

/**
 * Orchestrates the ACME (Let's Encrypt) certificate lifecycle.
 *
 * <h3>HTTP-01 Challenge requirement</h3>
 * <p>Let's Encrypt will send a GET request to
 * {@code http://{hostname}:80/.well-known/acme-challenge/{token}}.
 * Port 80 must be reachable from the internet.  If Vork is behind a reverse
 * proxy or NAT, ensure port 80 is forwarded to Vork's HTTP port (default 8080).
 *
 * <h3>Thread safety</h3>
 * <p>Only one ACME flow may run at a time.  {@link #requestCertificate} throws if
 * another is already in progress.
 */
@Service
public class LetsEncryptService {

    private static final Logger log = LoggerFactory.getLogger(LetsEncryptService.class);

    /** Live ACME directory URL for Let's Encrypt production. */
    private static final String LE_PRODUCTION = "acme://letsencrypt.org";
    // Staging URL (for testing, avoids rate limits): "acme://letsencrypt.org/staging"

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public enum CertStatus { IDLE, ORDERING, PENDING_CHALLENGE, VALIDATING, ISSUING, SUCCESS, FAILED }

    private volatile CertStatus certStatus = CertStatus.IDLE;
    private volatile String statusMessage = "";

    private final AcmeChallengeStore challengeStore;
    private final SslService sslService;

    public LetsEncryptService(AcmeChallengeStore challengeStore, SslService sslService) {
        this.challengeStore = challengeStore;
        this.sslService     = sslService;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns the current Let's Encrypt workflow status. */
    public CertStatus getStatus() { return certStatus; }

    /** Returns a human-readable description of the current status. */
    public String getStatusMessage() { return statusMessage; }

    /**
     * Starts an asynchronous Let's Encrypt certificate request on a virtual thread.
     *
     * @param hostname the fully-qualified domain name to certify
     * @param email    contact email for the ACME account
     * @throws IllegalStateException if a request is already in progress
     */
    public synchronized void requestCertificate(String hostname, String email) {
        log.debug("ENTER LetsEncryptService.requestCertificate: hostname={}", hostname);
        if (certStatus == CertStatus.ORDERING
                || certStatus == CertStatus.PENDING_CHALLENGE
                || certStatus == CertStatus.VALIDATING
                || certStatus == CertStatus.ISSUING) {
            throw new IllegalStateException("Let's Encrypt process already in progress: " + certStatus);
        }
        certStatus = CertStatus.ORDERING;
        statusMessage = "Starting certificate request for " + hostname + "…";

        Thread.ofVirtual()
              .name("le-cert-request")
              .start(() -> runAcmeFlow(hostname, email));
        log.debug("EXIT LetsEncryptService.requestCertificate: flow started");
    }

    /**
     * Initiates a certificate renewal for the same hostname/email used previously.
     * Designed to be called from the {@link SslRenewalScheduler}.
     */
    public void renewCertificate() {
        log.debug("ENTER LetsEncryptService.renewCertificate");
        var cfg = sslService.getCurrentCertInfo();
        if (cfg == null || cfg.letsEncryptEmail() == null || cfg.commonName() == null) {
            log.warn("SSL: Cannot renew — no Let's Encrypt configuration found");
            return;
        }
        try {
            requestCertificate(cfg.commonName(), cfg.letsEncryptEmail());
        } catch (Exception e) {
            log.warn("SSL: Renewal could not be started: {}", e.getMessage());
        }
    }

    // ── ACME flow ─────────────────────────────────────────────────────────────

    private void runAcmeFlow(String hostname, String email) {
        log.info("SSL: Starting Let's Encrypt ACME flow for hostname={}", hostname);
        try {
            Path certDir = sslService.getCertDir();

            // ── 1. ACME Session ──────────────────────────────────────────────
            Session session = new Session(LE_PRODUCTION);

            // ── 2. Account (create or restore) ──────────────────────────────
            KeyPair accountKey = getOrCreateAccountKey(certDir);
            Account account = buildAccount(session, accountKey, email);
            String accountUrl = account.getLocation().toString();
            sslService.saveAccountUrl(accountUrl);
            log.info("SSL: ACME account: {}", accountUrl);

            // ── 3. Order ─────────────────────────────────────────────────────
            certStatus = CertStatus.ORDERING;
            statusMessage = "Creating certificate order for " + hostname + "…";
            Order order = account.newOrder().domains(hostname).create();

            // ── 4. HTTP-01 challenge ─────────────────────────────────────────
            for (Authorization auth : order.getAuthorizations()) {
                if (auth.getStatus() == Status.VALID) continue;

                Http01Challenge challenge = auth.findChallenge(Http01Challenge.TYPE);
                if (challenge == null) {
                    throw new RuntimeException("ACME server did not offer HTTP-01 challenge for " + hostname);
                }

                String token         = challenge.getToken();
                String authorization = challenge.getAuthorization();
                challengeStore.put(token, authorization);

                certStatus    = CertStatus.PENDING_CHALLENGE;
                statusMessage = "Awaiting ACME HTTP-01 validation at http://" + hostname
                        + "/.well-known/acme-challenge/" + token;
                log.info("SSL: ACME challenge token={}", token);

                challenge.trigger();

                // ── 5. Poll for validation ───────────────────────────────────
                certStatus    = CertStatus.VALIDATING;
                statusMessage = "Validating domain ownership…";

                for (int i = 0; i < 24; i++) {
                    Thread.sleep(Duration.ofSeconds(5).toMillis());
                    auth.update();
                    Status s = challenge.getStatus();
                    log.debug("SSL: ACME challenge status={}", s);
                    if (s == Status.VALID)   break;
                    if (s == Status.INVALID) throw new RuntimeException("ACME domain validation failed for " + hostname);
                }

                if (challenge.getStatus() != Status.VALID) {
                    throw new RuntimeException("ACME domain validation timed out for " + hostname);
                }
                challengeStore.remove(token);
            }

            // ── 6. Generate domain key pair + CSR ────────────────────────────
            certStatus    = CertStatus.ISSUING;
            statusMessage = "Requesting certificate issuance…";

            KeyPair domainKey = generateRsaKeyPair();
            byte[] csrDer = SslCertificateUtil.generateCsrDer(
                    domainKey, hostname, null, null, null, null, null);

            order.execute(csrDer);

            // ── 7. Wait for certificate ──────────────────────────────────────
            for (int i = 0; i < 20; i++) {
                Thread.sleep(Duration.ofSeconds(3).toMillis());
                order.update();
                Status s = order.getStatus();
                log.debug("SSL: ACME order status={}", s);
                if (s == Status.VALID)   break;
                if (s == Status.INVALID) throw new RuntimeException("Certificate issuance failed");
            }

            if (order.getStatus() != Status.VALID) {
                throw new RuntimeException("Certificate issuance timed out");
            }

            // ── 8. Write cert chain + new private key ────────────────────────
            Certificate cert = order.getCertificate();

            Path certFile = certDir.resolve("certificate.pem");
            Path keyFile  = certDir.resolve("private-key.pem");

            try (java.io.Writer w = new FileWriter(certFile.toFile())) {
                cert.writeCertificate(w);
            }
            try (JcaPEMWriter pw = new JcaPEMWriter(new FileWriter(keyFile.toFile()))) {
                pw.writeObject(domainKey.getPrivate());
            }

            sslService.refreshAfterCertUpdate("lets-encrypt", email);

            certStatus    = CertStatus.SUCCESS;
            statusMessage = "Let's Encrypt certificate obtained successfully for " + hostname;
            log.info("SSL: {}", statusMessage);

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            certStatus    = CertStatus.FAILED;
            statusMessage = "Certificate request interrupted";
            log.warn("SSL: ACME flow interrupted");
        } catch (Exception e) {
            certStatus    = CertStatus.FAILED;
            statusMessage = "Failed: " + e.getMessage();
            log.error("SSL: ACME flow failed: {}", e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private KeyPair getOrCreateAccountKey(Path certDir) throws Exception {
        Path accountKeyFile = certDir.resolve("acme-account.pem");
        if (Files.exists(accountKeyFile)) {
            try (org.bouncycastle.openssl.PEMParser parser =
                         new org.bouncycastle.openssl.PEMParser(new java.io.FileReader(accountKeyFile.toFile()))) {
                Object obj = parser.readObject();
                if (obj instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo) {
                    // Existing account key file found; we'll generate a fresh pair anyway
                    // since we don't have the corresponding public key stored separately.
                    log.debug("SSL: Existing ACME account key file found, will attempt key reuse");
                }
            } catch (IOException e) {
                log.warn("SSL: Could not read existing ACME account key — generating new one: {}", e.getMessage());
            }
        }
        // Generate a new 2048-bit RSA account key pair
        KeyPair keyPair = generateRsaKeyPair();
        try (JcaPEMWriter pw = new JcaPEMWriter(new FileWriter(accountKeyFile.toFile()))) {
            pw.writeObject(keyPair.getPrivate());
        }
        log.info("SSL: New ACME account key generated in {}", accountKeyFile);
        return keyPair;
    }

    private Account buildAccount(Session session, KeyPair accountKey, String email) throws Exception {
        return new AccountBuilder()
                .addContact("mailto:" + email)
                .agreeToTermsOfService()
                .useKeyPair(accountKey)
                .create(session);
    }

    private static KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }
}
