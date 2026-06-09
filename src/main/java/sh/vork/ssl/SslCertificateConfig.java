package sh.vork.ssl;

import com.jadaptive.orm.DatabaseEntity;

/**
 * Persisted metadata for the active SSL certificate.
 * The single live instance uses uuid = "current".
 */
public record SslCertificateConfig(
        String uuid,
        String type,                  // "self-signed" | "lets-encrypt"
        String commonName,
        String organization,
        String organizationalUnit,
        String locality,
        String state,
        String country,
        String letsEncryptEmail,
        String letsEncryptAccountUrl, // persisted ACME account URL
        long   issuedAt,
        long   expiresAt,
        long   updatedAt
) implements DatabaseEntity {}
