package sh.vork.ssl;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import java.io.FileReader;
import java.io.FileWriter;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static utilities for X.509 certificate and CSR operations, built on BouncyCastle.
 */
public final class SslCertificateUtil {

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private SslCertificateUtil() {}

    // ── Self-signed certificate generation ────────────────────────────────────

    /**
     * Generates a new RSA-2048 self-signed certificate and writes PEM files.
     *
     * @param certFile  destination for the PEM certificate
     * @param keyFile   destination for the PEM private key (PKCS#8)
     * @param cn        Common Name (hostname / server name)
     * @param o         Organization (may be null)
     * @param ou        Organizational Unit (may be null)
     * @param l         Locality (may be null)
     * @param st        State (may be null)
     * @param c         Country code (may be null)
     * @param validDays certificate validity in days
     */
    public static void generateSelfSigned(Path certFile, Path keyFile,
                                          String cn, String o, String ou,
                                          String l, String st, String c,
                                          int validDays) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();

        X500Name subject = buildSubject(cn, o, ou, l, st, c);
        Date notBefore = new Date();
        Date notAfter  = new Date(notBefore.getTime() + (long) validDays * 86_400_000L);
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());

        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, keyPair.getPublic());

        // Subject Alternative Name: include the CN as a DNS SAN
        if (cn != null && !cn.isBlank()) {
            certBuilder.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(new GeneralName(GeneralName.dNSName, cn)));
        }
        certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        certBuilder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        certBuilder.addExtension(Extension.extendedKeyUsage, false,
                new ExtendedKeyUsage(new KeyPurposeId[]{KeyPurposeId.id_kp_serverAuth}));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(keyPair.getPrivate());
        X509CertificateHolder holder = certBuilder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);

        try (JcaPEMWriter pw = new JcaPEMWriter(new FileWriter(certFile.toFile()))) {
            pw.writeObject(cert);
        }
        try (JcaPEMWriter pw = new JcaPEMWriter(new FileWriter(keyFile.toFile()))) {
            pw.writeObject(keyPair.getPrivate());
        }
    }

    // ── CSR generation ────────────────────────────────────────────────────────

    /**
     * Generates a PKCS#10 CSR (DER-encoded) using the private key from {@code keyFile}
     * and the public key extracted from the existing {@code certFile}.
     *
     * @return DER-encoded PKCS#10 CSR bytes
     */
    public static byte[] generateCsrDer(Path certFile, Path keyFile,
                                        String cn, String o, String ou,
                                        String l, String st, String c) throws Exception {
        KeyPair keyPair = readKeyPair(certFile, keyFile);
        return buildCsr(keyPair, cn, o, ou, l, st, c);
    }

    /**
     * Generates a PKCS#10 CSR using an explicit key pair.
     * Used by the Let's Encrypt flow where a fresh key pair is created.
     */
    public static byte[] generateCsrDer(KeyPair keyPair,
                                        String cn, String o, String ou,
                                        String l, String st, String c) throws Exception {
        return buildCsr(keyPair, cn, o, ou, l, st, c);
    }

    // ── Certificate reading ───────────────────────────────────────────────────

    /**
     * Reads the first certificate from a PEM file and returns its metadata.
     * {@code type}, {@code letsEncryptEmail}, {@code letsEncryptStatus}, and
     * {@code letsEncryptMessage} are left null — the caller fills them from the DB.
     */
    public static SslCertificateInfo readCertInfo(Path certFile) throws Exception {
        try (PEMParser parser = new PEMParser(new FileReader(certFile.toFile()))) {
            Object obj = parser.readObject();
            if (!(obj instanceof X509CertificateHolder)) {
                throw new Exception("No X.509 certificate found in " + certFile);
            }
            X509Certificate cert = new JcaX509CertificateConverter()
                    .setProvider("BC").getCertificate((X509CertificateHolder) obj);
            return extractInfo(cert);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static X500Name buildSubject(String cn, String o, String ou,
                                         String l, String st, String c) {
        X500NameBuilder b = new X500NameBuilder(BCStyle.INSTANCE);
        if (cn  != null && !cn.isBlank())  b.addRDN(BCStyle.CN,  cn);
        if (o   != null && !o.isBlank())   b.addRDN(BCStyle.O,   o);
        if (ou  != null && !ou.isBlank())  b.addRDN(BCStyle.OU,  ou);
        if (l   != null && !l.isBlank())   b.addRDN(BCStyle.L,   l);
        if (st  != null && !st.isBlank())  b.addRDN(BCStyle.ST,  st);
        if (c   != null && !c.isBlank())   b.addRDN(BCStyle.C,   c);
        return b.build();
    }

    private static byte[] buildCsr(KeyPair keyPair, String cn, String o, String ou,
                                    String l, String st, String c) throws Exception {
        X500Name subject = buildSubject(cn, o, ou, l, st, c);
        JcaPKCS10CertificationRequestBuilder csrBuilder =
                new JcaPKCS10CertificationRequestBuilder(subject, keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(keyPair.getPrivate());
        PKCS10CertificationRequest csr = csrBuilder.build(signer);
        return csr.getEncoded();
    }

    /** Reads back the key pair: public key from the cert file, private key from the key file. */
    static KeyPair readKeyPair(Path certFile, Path keyFile) throws Exception {
        PublicKey publicKey;
        try (PEMParser certParser = new PEMParser(new FileReader(certFile.toFile()))) {
            Object obj = certParser.readObject();
            if (!(obj instanceof X509CertificateHolder)) {
                throw new Exception("No X.509 certificate in " + certFile);
            }
            X509Certificate cert = new JcaX509CertificateConverter()
                    .setProvider("BC").getCertificate((X509CertificateHolder) obj);
            publicKey = cert.getPublicKey();
        }

        PrivateKey privateKey;
        try (PEMParser keyParser = new PEMParser(new FileReader(keyFile.toFile()))) {
            Object obj = keyParser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
            if (obj instanceof PrivateKeyInfo pki) {
                privateKey = converter.getPrivateKey(pki);
            } else if (obj instanceof PEMKeyPair pemKP) {
                privateKey = converter.getKeyPair(pemKP).getPrivate();
            } else {
                throw new Exception("Unrecognised private key format in " + keyFile);
            }
        }
        return new KeyPair(publicKey, privateKey);
    }

    private static SslCertificateInfo extractInfo(X509Certificate cert) {
        Map<String, String> sub = parseDn(cert.getSubjectX500Principal().getName("RFC2253"));
        boolean selfSigned = cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal());

        int keySize = 0;
        if (cert.getPublicKey() instanceof RSAPublicKey rsa) {
            keySize = rsa.getModulus().bitLength();
        }

        List<String> sans = new ArrayList<>();
        try {
            Collection<List<?>> altNames = cert.getSubjectAlternativeNames();
            if (altNames != null) {
                for (List<?> altName : altNames) {
                    if (altName.get(0) instanceof Integer type && (type == 2 || type == 7)) {
                        sans.add(altName.get(1).toString());
                    }
                }
            }
        } catch (Exception ignored) {}

        return new SslCertificateInfo(
                null,
                sub.get("CN"),
                sub.get("O"),
                sub.get("OU"),
                sub.get("L"),
                sub.get("ST"),
                sub.get("C"),
                cert.getIssuerX500Principal().getName("RFC2253"),
                cert.getSerialNumber().toString(16).toUpperCase(),
                cert.getSigAlgName(),
                cert.getNotBefore().getTime(),
                cert.getNotAfter().getTime(),
                selfSigned,
                sans,
                keySize,
                null, null, null
        );
    }

    private static Map<String, String> parseDn(String dn) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            X500Name name = new X500Name(dn);
            for (RDN rdn : name.getRDNs()) {
                ASN1ObjectIdentifier type = rdn.getFirst().getType();
                String label;
                try { label = BCStyle.INSTANCE.oidToDisplayName(type); }
                catch (Exception e) { label = type.getId(); }
                String value = IETFUtils.valueToString(rdn.getFirst().getValue());
                result.put(label, value);
            }
        } catch (Exception ignored) {}
        return result;
    }
}
