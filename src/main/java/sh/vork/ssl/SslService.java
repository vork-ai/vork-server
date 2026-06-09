package sh.vork.ssl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import com.jadaptive.orm.DatabaseRepository;

import jakarta.annotation.PostConstruct;

/**
 * Spring service that manages SSL certificate operations:
 * <ul>
 *   <li>On startup: syncs certificate metadata from the PEM file into MongoDB.</li>
 *   <li>Generate self-signed certificates on demand.</li>
 *   <li>Generate PKCS#10 CSR bytes for download.</li>
 *   <li>Update metadata after Let's Encrypt certificate issuance.</li>
 * </ul>
 */
@Service
public class SslService {

    private static final Logger log = LoggerFactory.getLogger(SslService.class);
    private static final String CONFIG_UUID = "current";

    private final DatabaseRepository<SslCertificateConfig> repo;
    private final Path certDir;
    private final ApplicationContext applicationContext;

    public SslService(
            DatabaseRepository<SslCertificateConfig> sslCertificateConfigRepository,
            @Value("${vork.ssl.cert-dir:conf.d/ssl}") String certDirStr,
            ApplicationContext applicationContext) {
        this.repo               = sslCertificateConfigRepository;
        this.certDir            = Paths.get(certDirStr).toAbsolutePath();
        this.applicationContext = applicationContext;
    }

    // ── Startup sync ──────────────────────────────────────────────────────────

    @PostConstruct
    public void syncCertMetadata() {
        log.debug("ENTER SslService.syncCertMetadata");
        Path certFile = certDir.resolve("certificate.pem");
        if (!Files.exists(certFile)) {
            log.warn("SSL: certificate.pem not found in {} – skipping metadata sync", certDir);
            return;
        }
        try {
            SslCertificateInfo info = SslCertificateUtil.readCertInfo(certFile);
            SslCertificateConfig existing = repo.get(CONFIG_UUID);
            String type = (existing != null && existing.type() != null) ? existing.type() : "self-signed";
            String leEmail = existing != null ? existing.letsEncryptEmail() : null;
            String leAccountUrl = existing != null ? existing.letsEncryptAccountUrl() : null;

            SslCertificateConfig config = new SslCertificateConfig(
                    CONFIG_UUID,
                    type,
                    info.commonName(),
                    info.organization(),
                    info.organizationalUnit(),
                    info.locality(),
                    info.state(),
                    info.country(),
                    leEmail,
                    leAccountUrl,
                    info.validFrom(),
                    info.validUntil(),
                    System.currentTimeMillis()
            );
            repo.save(config);
            log.info("SSL: Certificate metadata synced — type={}, cn={}, expires={}",
                    type, info.commonName(), new java.util.Date(info.validUntil()));
        } catch (Exception e) {
            log.warn("SSL: Could not sync certificate metadata: {}", e.getMessage(), e);
        }
        log.debug("EXIT SslService.syncCertMetadata");
    }

    // ── Certificate info ──────────────────────────────────────────────────────

    /**
     * Returns current certificate details, or {@code null} if no certificate exists.
     */
    public SslCertificateInfo getCurrentCertInfo() {
        log.debug("ENTER SslService.getCurrentCertInfo");
        Path certFile = certDir.resolve("certificate.pem");
        if (!Files.exists(certFile)) {
            log.debug("EXIT SslService.getCurrentCertInfo: no cert file");
            return null;
        }
        try {
            SslCertificateInfo raw = SslCertificateUtil.readCertInfo(certFile);
            SslCertificateConfig cfg = repo.get(CONFIG_UUID);
            String type       = cfg != null ? cfg.type()                : "self-signed";
            String leEmail    = cfg != null ? cfg.letsEncryptEmail()    : null;
            SslCertificateInfo info = new SslCertificateInfo(
                    type,
                    raw.commonName(), raw.organization(), raw.organizationalUnit(),
                    raw.locality(), raw.state(), raw.country(),
                    raw.issuerName(), raw.serialNumber(), raw.signatureAlgorithm(),
                    raw.validFrom(), raw.validUntil(), raw.selfSigned(),
                    raw.subjectAltNames(), raw.keySize(),
                    leEmail, null, null
            );
            log.debug("EXIT SslService.getCurrentCertInfo: type={}", type);
            return info;
        } catch (Exception e) {
            log.warn("SSL: Could not read certificate info: {}", e.getMessage(), e);
            return null;
        }
    }

    // ── Self-signed generation ────────────────────────────────────────────────

    /**
     * Generates a new self-signed certificate, replacing existing PEM files.
     * Spring Boot's {@code reload-on-rotation=true} will detect the file change and
     * reload the SSL context automatically.
     */
    public void generateSelfSigned(String cn, String o, String ou,
                                   String l, String st, String c) throws Exception {
        log.debug("ENTER SslService.generateSelfSigned: cn={}", cn);
        Path certFile = certDir.resolve("certificate.pem");
        Path keyFile  = certDir.resolve("private-key.pem");

        SslCertificateUtil.generateSelfSigned(certFile, keyFile, cn, o, ou, l, st, c, 365);
        log.info("SSL: New self-signed certificate generated — cn={}", cn);

        // Persist metadata
        SslCertificateInfo info = SslCertificateUtil.readCertInfo(certFile);
        SslCertificateConfig existing = repo.get(CONFIG_UUID);
        String leAccountUrl = existing != null ? existing.letsEncryptAccountUrl() : null;
        repo.save(new SslCertificateConfig(
                CONFIG_UUID, "self-signed",
                info.commonName(), info.organization(), info.organizationalUnit(),
                info.locality(), info.state(), info.country(),
                null, leAccountUrl,
                info.validFrom(), info.validUntil(), System.currentTimeMillis()
        ));

        // Reload Tomcat's SSL connector so the new cert takes effect without restart
        reloadSslConnectors();
        log.debug("EXIT SslService.generateSelfSigned");
    }

    // ── CSR ───────────────────────────────────────────────────────────────────

    /**
     * Generates a PKCS#10 CSR using the current private key and the supplied subject fields.
     *
     * @return DER-encoded CSR bytes
     */
    public byte[] generateCsr(String cn, String o, String ou,
                               String l, String st, String c) throws Exception {
        log.debug("ENTER SslService.generateCsr: cn={}", cn);
        Path certFile = certDir.resolve("certificate.pem");
        Path keyFile  = certDir.resolve("private-key.pem");
        byte[] csrDer = SslCertificateUtil.generateCsrDer(certFile, keyFile, cn, o, ou, l, st, c);
        log.debug("EXIT SslService.generateCsr: {}B DER", csrDer.length);
        return csrDer;
    }

    // ── Post-issuance update ──────────────────────────────────────────────────

    /**
     * Updates the persisted metadata after a new certificate has been written to disk
     * (e.g., after a successful Let's Encrypt issuance).
     *
     * @param type  "lets-encrypt" or "self-signed"
     * @param email email address used for Let's Encrypt (may be null)
     */
    public void refreshAfterCertUpdate(String type, String email) {
        log.debug("ENTER SslService.refreshAfterCertUpdate: type={}", type);
        Path certFile = certDir.resolve("certificate.pem");
        try {
            SslCertificateInfo info = SslCertificateUtil.readCertInfo(certFile);
            SslCertificateConfig existing = repo.get(CONFIG_UUID);
            String leAccountUrl = existing != null ? existing.letsEncryptAccountUrl() : null;
            repo.save(new SslCertificateConfig(
                    CONFIG_UUID, type,
                    info.commonName(), info.organization(), info.organizationalUnit(),
                    info.locality(), info.state(), info.country(),
                    email, leAccountUrl,
                    info.validFrom(), info.validUntil(), System.currentTimeMillis()
            ));
            log.info("SSL: Certificate metadata updated — type={}, cn={}", type, info.commonName());
            reloadSslConnectors();
        } catch (Exception e) {
            log.warn("SSL: Could not refresh cert metadata: {}", e.getMessage(), e);
        }
        log.debug("EXIT SslService.refreshAfterCertUpdate");
    }

    // ── Tomcat SSL reload ───────────────────────────────────────────────────

    /**
     * Reloads the SSL configuration on all secure Tomcat connectors so that a newly
     * written PEM file takes effect without restarting the JVM.
     */
    void reloadSslConnectors() {
        log.debug("ENTER SslService.reloadSslConnectors");
        try {
            if (applicationContext instanceof ServletWebServerApplicationContext ctx) {
                if (ctx.getWebServer() instanceof TomcatWebServer tomcatWs) {
                    for (Connector connector : tomcatWs.getTomcat().getService().findConnectors()) {
                        if (connector.getSecure()
                                && connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> proto) {
                            proto.reloadSslHostConfigs();
                            log.info("SSL: Connector :{} SSL context reloaded", connector.getPort());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("SSL: Could not reload SSL context dynamically — restart required: {}", e.getMessage());
        }
        log.debug("EXIT SslService.reloadSslConnectors");
    }

    /**
     * Persists an updated ACME account URL.
     */
    public void saveAccountUrl(String accountUrl) {
        SslCertificateConfig existing = repo.get(CONFIG_UUID);
        if (existing == null) return;
        repo.save(new SslCertificateConfig(
                existing.uuid(), existing.type(),
                existing.commonName(), existing.organization(), existing.organizationalUnit(),
                existing.locality(), existing.state(), existing.country(),
                existing.letsEncryptEmail(), accountUrl,
                existing.issuedAt(), existing.expiresAt(), System.currentTimeMillis()
        ));
    }

    /** Returns the persisted ACME account URL, or {@code null} if not yet set. */
    public String getAccountUrl() {
        SslCertificateConfig cfg = repo.get(CONFIG_UUID);
        return cfg != null ? cfg.letsEncryptAccountUrl() : null;
    }

    /** Returns the resolved certificate directory path. */
    public Path getCertDir() { return certDir; }

    /** Returns {@code true} if a certificate PEM file exists on disk. */
    public boolean certExists() {
        return Files.exists(certDir.resolve("certificate.pem"));
    }

    /** Returns the number of days until the current certificate expires, or -1 if unknown. */
    public long daysUntilExpiry() {
        SslCertificateConfig cfg = repo.get(CONFIG_UUID);
        if (cfg == null || cfg.expiresAt() == 0) return -1;
        long ms = cfg.expiresAt() - System.currentTimeMillis();
        return ms / 86_400_000L;
    }

    /** Returns the stored certificate type ("self-signed" or "lets-encrypt"), or null if unknown. */
    public String getCertType() {
        SslCertificateConfig cfg = repo.get(CONFIG_UUID);
        return cfg != null ? cfg.type() : null;
    }
}
