package sh.vork.ssl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Runs early in the Spring Boot lifecycle (before the embedded server is configured).
 * <p>
 * Responsibilities:
 * <ol>
 *   <li>Resolve the SSL certificate directory ({@code conf.d/ssl} by default).</li>
 *   <li>Generate a self-signed certificate if none exists.</li>
 *   <li>Publish {@code spring.ssl.bundle.pem.vork-ssl.*} and {@code server.ssl.bundle}
 *       properties so that Tomcat starts on HTTPS port 8443.</li>
 * </ol>
 *
 * <p>The certificate directory is resolved in this priority order:
 * <ol>
 *   <li>System property {@code vork.ssl.cert-dir}</li>
 *   <li>Environment variable {@code VORK_SSL_CERT_DIR} (Spring maps this automatically)</li>
 *   <li>Default: {@code conf.d/ssl} (relative to the working directory)</li>
 * </ol>
 *
 * <p>Registered via {@code META-INF/spring.factories}.
 */
public class SslStartupInitializer implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SslStartupInitializer.class);

    /** Property key written into the environment so other beans can read the resolved path. */
    public static final String CERT_DIR_PROP = "vork.ssl.cert-dir";

    @Override
    public int getOrder() {
        // Run after most other post-processors but before server setup.
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        log.debug("ENTER SslStartupInitializer.postProcessEnvironment");

        String certDirStr = resolveCertDir(env);
        Path certDir = Paths.get(certDirStr).toAbsolutePath();
        Path certFile = certDir.resolve("certificate.pem");
        Path keyFile  = certDir.resolve("private-key.pem");

        try {
            Files.createDirectories(certDir);
            log.debug("SSL cert dir: {}", certDir);

            if (!Files.exists(certFile) || !Files.exists(keyFile)) {
                log.info("SSL: No certificate found in {}, generating self-signed certificate", certDir);
                SslCertificateUtil.generateSelfSigned(
                        certFile, keyFile,
                        "localhost", "Vork", null, null, null, null,
                        365);
                log.info("SSL: Self-signed certificate generated in {}", certDir);
            } else {
                log.debug("SSL: Existing certificate found in {}", certDir);
            }

            // Publish SSL properties so Spring Boot configures Tomcat with TLS.
            // We use the direct server.ssl.certificate / server.ssl.certificate-private-key
            // approach rather than a named bundle to avoid timing issues where
            // SslAutoConfiguration tries to resolve a bundle before properties are bound.
            Properties ssl = new Properties();
            ssl.setProperty("server.ssl.certificate",             certFile.toString());
            ssl.setProperty("server.ssl.certificate-private-key", keyFile.toString());
            ssl.setProperty("server.port",    "8443");
            ssl.setProperty(CERT_DIR_PROP,    certDir.toString());

            // addLast → these are defaults; SERVER_PORT / VORK_SSL_CERT_DIR env vars override them.
            env.getPropertySources().addLast(new PropertiesPropertySource("vork-ssl-defaults", ssl));

            log.debug("EXIT SslStartupInitializer: SSL configured, port=8443, certDir={}", certDir);

        } catch (Exception e) {
            log.error("SSL: Certificate initialisation failed — server will start without SSL: {}", e.getMessage(), e);
        }
    }

    private String resolveCertDir(ConfigurableEnvironment env) {
        // 1. JVM system property (highest priority)
        String sys = System.getProperty(CERT_DIR_PROP);
        if (sys != null && !sys.isBlank()) return sys.trim();

        // 2. Spring environment (covers VORK_SSL_CERT_DIR env var via Spring's relaxed binding,
        //    and any explicit application.yml entry).
        String envProp = env.getProperty(CERT_DIR_PROP);
        if (envProp != null && !envProp.isBlank()) return envProp.trim();

        // 3. Default
        return "conf.d/ssl";
    }
}
