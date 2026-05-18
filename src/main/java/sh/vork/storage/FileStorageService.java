package sh.vork.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import sh.vork.database.DatabaseRepository;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

/**
 * High-level service for uploading, retrieving, and deleting files.
 *
 * <p>Metadata is persisted to MongoDB via a {@link DatabaseRepository}{@code <StoredFile>}.
 * Bytes are delegated to the active {@link FileStorageProvider}.
 *
 * <h3>AI-supported MIME types</h3>
 * {@link #isAiSupported(String)} returns {@code true} for MIME types that Gemini (and
 * most vision-capable models) can process: images, audio, video, PDF, and plain text.
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    /** Exact MIME types accepted by the AI beyond image/audio/video prefix wildcards. */
    private static final Set<String> SUPPORTED_EXACT = Set.of(
            "application/pdf"
    );

    private final FileStorageProvider      provider;
    private final DatabaseRepository<StoredFile> repository;

    public FileStorageService(FileStorageProvider fileStorageProvider,
                              DatabaseRepository<StoredFile> storedFileRepository) {
        this.provider   = fileStorageProvider;
        this.repository = storedFileRepository;
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Stores {@code file} and returns the persisted {@link StoredFile} metadata.
     *
     * @throws IOException if the bytes cannot be written to storage
     */
    public StoredFile upload(MultipartFile file) throws IOException {
        String uuid      = UUID.randomUUID().toString();
        String name      = sanitiseName(file.getOriginalFilename());
        String mimeType  = resolveMimeType(file);
        long   size      = file.getSize();

        log.info("Uploading file [name={}, mime={}, size={}]", name, mimeType, size);

        try (InputStream in = file.getInputStream()) {
            String storagePath = provider.store(uuid, name, mimeType, in, size);
            StoredFile stored = new StoredFile(uuid, name, mimeType, size, storagePath,
                    System.currentTimeMillis());
            repository.save(stored);
            log.info("File stored [uuid={}, name={}]", uuid, name);
            return stored;
        }
    }

    /**
     * Uploads bytes from an InputStream directly, without a MultipartFile wrapper.
     * Used for programmatic uploads like terminal output files.
     */
    public StoredFile uploadRaw(InputStream in, String originalName, String mimeType, long sizeBytes) throws IOException {
        String uuid = UUID.randomUUID().toString();
        String name = sanitiseName(originalName);

        log.info("Uploading raw file [name={}, mime={}, size={}]", name, mimeType, sizeBytes);

        String storagePath = provider.store(uuid, name, mimeType, in, sizeBytes);
        StoredFile stored = new StoredFile(uuid, name, mimeType, sizeBytes, storagePath,
                System.currentTimeMillis());
        repository.save(stored);
        log.info("Raw file stored [uuid={}, name={}]", uuid, name);
        return stored;
    }

    // ── Retrieve ──────────────────────────────────────────────────────────────

    /**
     * Returns the metadata record for {@code uuid}, or {@code null} if not found.
     */
    public StoredFile getMetadata(String uuid) {
        return repository.get(uuid);
    }

    /**
     * Opens a stream to the stored file bytes. The caller must close the stream.
     *
     * @throws IOException if the file cannot be found or read
     */
    public InputStream getContent(String uuid) throws IOException {
        StoredFile meta = repository.get(uuid);
        if (meta == null) {
            throw new IOException("Unknown file UUID: " + uuid);
        }
        return provider.retrieve(meta.storagePath());
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Removes both the stored bytes and the metadata record. No-op if not found.
     */
    public void delete(String uuid) throws IOException {
        StoredFile meta = repository.get(uuid);
        if (meta == null) return;
        provider.delete(meta.storagePath());
        repository.delete(uuid);
        log.info("Deleted file [uuid={}]", uuid);
    }

    // ── AI support check ──────────────────────────────────────────────────────

    /**
     * Returns {@code true} when the MIME type can be processed by the configured
     * AI provider.
     */
    public static boolean isAiSupported(String mimeType) {
        if (mimeType == null) return false;
        String lower = mimeType.toLowerCase();
        return lower.startsWith("image/")
                || lower.startsWith("audio/")
                || lower.startsWith("video/")
                || lower.startsWith("text/")
                || SUPPORTED_EXACT.contains(lower);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String sanitiseName(String name) {
        if (name == null || name.isBlank()) return "upload";
        // Keep only the filename part — no path separators
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private static String resolveMimeType(MultipartFile file) {
        String ct = file.getContentType();
        if (ct != null && !ct.isBlank() && !"application/octet-stream".equals(ct)) {
            return ct;
        }
        // Fall back to application/octet-stream
        return "application/octet-stream";
    }
}
