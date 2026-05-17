package sh.vork.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * {@link FileStorageProvider} that stores files on the local filesystem.
 *
 * <p>Files are stored under the directory configured by {@code storage.base-dir}
 * (default: {@code conf.d/files} relative to the working directory).  Each file is
 * saved as {@code {uuid}} — no extension — so the original filename is preserved
 * only in the {@link StoredFile} metadata record.
 *
 * <p>The base directory is created automatically on first use if it does not exist.
 */
@Component
public class LocalFileStorageProvider implements FileStorageProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorageProvider.class);

    private final Path baseDir;

    public LocalFileStorageProvider(
            @Value("${storage.base-dir:conf.d/files}") String baseDirPath) throws IOException {
        this.baseDir = Paths.get(baseDirPath).toAbsolutePath();
        Files.createDirectories(this.baseDir);
        log.info("Local file storage initialised at {}", this.baseDir);
    }

    @Override
    public String store(String uuid, String originalName, String mimeType,
                        InputStream content, long sizeBytes) throws IOException {
        Path target = baseDir.resolve(uuid);
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        log.debug("Stored file [uuid={}, name={}, size={}]", uuid, originalName, sizeBytes);
        return uuid; // storagePath is just the uuid for local storage
    }

    @Override
    public InputStream retrieve(String storagePath) throws IOException {
        Path file = baseDir.resolve(storagePath);
        if (!Files.exists(file)) {
            throw new IOException("File not found in local storage: " + storagePath);
        }
        return Files.newInputStream(file);
    }

    @Override
    public void delete(String storagePath) throws IOException {
        Path file = baseDir.resolve(storagePath);
        boolean deleted = Files.deleteIfExists(file);
        if (deleted) {
            log.debug("Deleted stored file [path={}]", storagePath);
        }
    }
}
