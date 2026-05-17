package sh.vork.storage;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * REST endpoints for file upload and retrieval.
 *
 * <pre>
 * POST /api/files          – upload a file; returns metadata + aiSupported flag
 * GET  /api/files/{uuid}   – stream file bytes with correct Content-Type
 * </pre>
 */
@RestController
@RequestMapping("/api/files")
public class FileUploadController {

    private static final Logger log = LoggerFactory.getLogger(FileUploadController.class);

    private final FileStorageService storageService;

    public FileUploadController(FileStorageService storageService) {
        this.storageService = storageService;
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Accepts a single file part named {@code file}.
     *
     * <p>Response JSON:
     * <pre>{
     *   "uuid":        "…",
     *   "name":        "photo.jpg",
     *   "mimeType":    "image/jpeg",
     *   "sizeBytes":   204800,
     *   "url":         "/api/files/{uuid}",
     *   "aiSupported": true
     * }</pre>
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "Uploaded file is empty"));
        }
        try {
            StoredFile stored = storageService.upload(file);
            return ResponseEntity.ok(Map.of(
                    "uuid",        stored.uuid(),
                    "name",        stored.originalName(),
                    "mimeType",    stored.mimeType(),
                    "sizeBytes",   stored.sizeBytes(),
                    "url",         "/api/files/" + stored.uuid(),
                    "aiSupported", FileStorageService.isAiSupported(stored.mimeType())
            ));
        } catch (IOException ex) {
            log.error("File upload failed: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", "Upload failed: " + ex.getMessage()));
        }
    }

    // ── Serve ─────────────────────────────────────────────────────────────────

    /**
     * Streams the stored file with its original MIME type.
     *
     * <ul>
     *   <li>Images → {@code Content-Disposition: inline} so the browser displays them.</li>
     *   <li>All other types → {@code Content-Disposition: attachment} to trigger download.</li>
     * </ul>
     */
    @GetMapping("/{uuid}")
    public void serve(@PathVariable String uuid, HttpServletResponse response) throws IOException {
        StoredFile meta = storageService.getMetadata(uuid);
        if (meta == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "File not found: " + uuid);
            return;
        }

        response.setContentType(meta.mimeType());
        response.setContentLengthLong(meta.sizeBytes());

        boolean inline = meta.mimeType().startsWith("image/");
        String disposition = inline
                ? "inline; filename=\"" + escapeHeaderValue(meta.originalName()) + "\""
                : "attachment; filename=\"" + escapeHeaderValue(meta.originalName()) + "\"";
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition);

        try (InputStream in = storageService.getContent(uuid)) {
            StreamUtils.copy(in, response.getOutputStream());
        } catch (IOException ex) {
            log.error("Failed to stream file [uuid={}]: {}", uuid, ex.getMessage(), ex);
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Removes characters that are illegal in HTTP header values. */
    private static String escapeHeaderValue(String value) {
        return value == null ? "" : value.replace("\"", "'").replace("\n", "").replace("\r", "");
    }
}
