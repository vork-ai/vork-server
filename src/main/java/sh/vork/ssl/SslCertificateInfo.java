package sh.vork.ssl;

import java.util.List;

/**
 * DTO carrying the details of the currently active certificate, returned by the REST API.
 */
public record SslCertificateInfo(
        String type,
        String commonName,
        String organization,
        String organizationalUnit,
        String locality,
        String state,
        String country,
        String issuerName,
        String serialNumber,
        String signatureAlgorithm,
        long   validFrom,
        long   validUntil,
        boolean selfSigned,
        List<String> subjectAltNames,
        int    keySize,
        String letsEncryptEmail,
        String letsEncryptStatus,
        String letsEncryptMessage
) {}
