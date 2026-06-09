package sh.vork.ssl.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sh.vork.ssl.LetsEncryptService;
import sh.vork.ssl.SslCertificateInfo;
import sh.vork.ssl.SslService;

import java.util.Map;

/**
 * REST API for SSL certificate management.
 *
 * <pre>
 * GET  /api/ssl/certificate                  — current certificate details
 * POST /api/ssl/certificate/self-signed       — regenerate self-signed cert
 * GET  /api/ssl/certificate/csr               — download CSR (DER, with query params for subject)
 * POST /api/ssl/certificate/lets-encrypt      — request Let's Encrypt certificate
 * GET  /api/ssl/certificate/lets-encrypt/status — workflow status
 * </pre>
 */
@RestController
@RequestMapping("/api/ssl/certificate")
public class SslController {

    private static final Logger log = LoggerFactory.getLogger(SslController.class);

    private final SslService sslService;
    private final LetsEncryptService letsEncryptService;

    public SslController(SslService sslService, LetsEncryptService letsEncryptService) {
        this.sslService         = sslService;
        this.letsEncryptService = letsEncryptService;
    }

    // ── GET certificate info ──────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<?> getCertificate() {
        log.debug("ENTER SslController.getCertificate");
        SslCertificateInfo info = sslService.getCurrentCertInfo();
        if (info == null) {
            return ResponseEntity.ok(Map.of("status", "not-configured",
                    "message", "No SSL certificate is currently active."));
        }
        log.debug("EXIT SslController.getCertificate: type={}", info.type());
        return ResponseEntity.ok(info);
    }

    // ── POST regenerate self-signed ───────────────────────────────────────────

    @PostMapping("/self-signed")
    public ResponseEntity<Map<String, String>> regenerateSelfSigned(
            @RequestBody SelfSignedRequest req) {
        log.debug("ENTER SslController.regenerateSelfSigned: cn={}", req.cn());
        try {
            sslService.generateSelfSigned(
                    req.cn(), req.o(), req.ou(), req.l(), req.st(), req.c());
            log.info("SSL: Self-signed certificate regenerated via API — cn={}", req.cn());
            return ResponseEntity.ok(Map.of("status", "ok",
                    "message", "Self-signed certificate generated. "
                            + "The SSL context will reload automatically within seconds."));
        } catch (Exception e) {
            log.warn("SSL: Self-signed generation failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // ── GET CSR download ──────────────────────────────────────────────────────

    @GetMapping("/csr")
    public ResponseEntity<byte[]> downloadCsr(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String cn,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String o,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String ou,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String l,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String st,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String c) {
        log.debug("ENTER SslController.downloadCsr: cn={}", cn);
        try {
            // Fall back to current cert subject fields when params are omitted
            SslCertificateInfo current = sslService.getCurrentCertInfo();
            String resolvedCn  = nonBlankOr(cn,  current != null ? current.commonName()           : null);
            String resolvedO   = nonBlankOr(o,   current != null ? current.organization()         : null);
            String resolvedOu  = nonBlankOr(ou,  current != null ? current.organizationalUnit()   : null);
            String resolvedL   = nonBlankOr(l,   current != null ? current.locality()             : null);
            String resolvedSt  = nonBlankOr(st,  current != null ? current.state()                : null);
            String resolvedC   = nonBlankOr(c,   current != null ? current.country()              : null);

            byte[] csrDer = sslService.generateCsr(resolvedCn, resolvedO, resolvedOu,
                    resolvedL, resolvedSt, resolvedC);

            String filename = "vork-" + (resolvedCn != null ? resolvedCn.replace('*', '_') : "server") + ".csr";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("application/pkcs10"))
                    .body(csrDer);
        } catch (Exception e) {
            log.warn("SSL: CSR generation failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── POST request Let's Encrypt certificate ────────────────────────────────

    @PostMapping("/lets-encrypt")
    public ResponseEntity<Map<String, String>> requestLetsEncrypt(
            @RequestBody LetsEncryptRequest req) {
        log.debug("ENTER SslController.requestLetsEncrypt: hostname={}", req.hostname());
        try {
            letsEncryptService.requestCertificate(req.hostname(), req.email());
            return ResponseEntity.ok(Map.of("status", "started",
                    "message", "Let's Encrypt certificate request started. "
                            + "Poll /api/ssl/certificate/lets-encrypt/status for progress."));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409)
                    .body(Map.of("status", "in-progress", "message", e.getMessage()));
        } catch (Exception e) {
            log.warn("SSL: Let's Encrypt request failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // ── GET Let's Encrypt status ──────────────────────────────────────────────

    @GetMapping("/lets-encrypt/status")
    public ResponseEntity<Map<String, String>> letsEncryptStatus() {
        return ResponseEntity.ok(Map.of(
                "status",  letsEncryptService.getStatus().name().toLowerCase(),
                "message", letsEncryptService.getStatusMessage()
        ));
    }

    // ── Request / response records ────────────────────────────────────────────

    public record SelfSignedRequest(String cn, String o, String ou, String l, String st, String c) {}

    public record LetsEncryptRequest(String hostname, String email) {}

    // ── Helper ────────────────────────────────────────────────────────────────

    private static String nonBlankOr(String preferred, String fallback) {
        return (preferred != null && !preferred.isBlank()) ? preferred : fallback;
    }
}
