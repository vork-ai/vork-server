package sh.vork.ai.terminal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import sh.vork.storage.StoredFile;
import sh.vork.storage.FileStorageService;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Manages terminal output storage on disk.
 * Writes command output to temp files, then uploads them to the file store
 * for attachment to chat messages.
 */
@Service
public class TerminalOutputStore {

    private static final Logger log = LoggerFactory.getLogger(TerminalOutputStore.class);

    private final FileStorageService fileStorageService;
    private final Path tempDir;

    public TerminalOutputStore(FileStorageService fileStorageService,
                                @Value("${java.io.tmpdir}") String tmpDirPath) {
        this.fileStorageService = fileStorageService;
        this.tempDir = Paths.get(tmpDirPath, "vork-terminal-output");
        ensureTempDir();
    }

    private void ensureTempDir() {
        try {
            Files.createDirectories(tempDir);
        } catch (IOException ex) {
            log.warn("Could not create terminal output temp directory: {}", ex.getMessage());
        }
    }

    /**
     * Creates a new terminal output writer for a command.
     * Returns a {@link TerminalOutputWriter} that buffers output and can be finalized.
     */
    public TerminalOutputWriter createWriter(String sessionUuid, String command) {
        String fileName = "terminal-" + UUID.randomUUID() + ".txt";
        Path file = tempDir.resolve(fileName);
        return new TerminalOutputWriter(file, command, sessionUuid);
    }

    /**
     * Finalizes a terminal output writer: flushes to disk and uploads to file store.
     * Returns a {@link StoredFile} record or null if upload fails.
     */
    public StoredFile finalize(TerminalOutputWriter writer) {
        try {
            writer.close();
            return uploadToStore(writer.getFile(), writer.getCommand());
        } catch (Exception ex) {
            log.error("Failed to finalize terminal output [session={}, command={}]: {}",
                    writer.getSessionUuid(), writer.getCommand(), ex.getMessage(), ex);
            return null;
        }
    }

    /**
     * Uploads a terminal output file to the file store.
     */
    private StoredFile uploadToStore(Path file, String command) throws IOException {
        if (!Files.exists(file)) {
            throw new FileNotFoundException("Terminal output file not found: " + file);
        }

        long sizeBytes = Files.size(file);
        byte[] bytes = Files.readAllBytes(file);

        try {
            String mimeType = "text/plain";
            String originalName = "terminal-output-" + UUID.randomUUID() + ".txt";

            // Wrap bytes in a mock MultipartFile for upload
            ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
            StoredFile stored = fileStorageService.uploadRaw(stream, originalName, mimeType, sizeBytes);

            log.info("Uploaded terminal output [command={}, sizeBytes={}, uuid={}]",
                    command, sizeBytes, stored.uuid());

            // Clean up temp file
            try {
                Files.delete(file);
            } catch (IOException ex) {
                log.warn("Could not delete terminal output temp file: {}", file, ex);
            }

            return stored;
        } catch (Exception ex) {
            throw new IOException("Failed to upload terminal output to file store", ex);
        }
    }

    /**
     * A writer that buffers terminal output to disk.
     */
    public static class TerminalOutputWriter implements Closeable {
        private final Path file;
        private final String command;
        private final String sessionUuid;
        private final BufferedWriter writer;
        private boolean closed = false;

        TerminalOutputWriter(Path file, String command, String sessionUuid) {
            this.file = file;
            this.command = command;
            this.sessionUuid = sessionUuid;
            try {
                this.writer = new BufferedWriter(
                        new OutputStreamWriter(
                                new FileOutputStream(file.toFile()),
                                StandardCharsets.UTF_8),
                        8192);
            } catch (IOException ex) {
                throw new RuntimeException("Failed to create terminal output writer", ex);
            }
        }

        public void write(String chunk) throws IOException {
            if (closed) {
                throw new IOException("Writer is closed");
            }
            writer.write(chunk);
        }

        public void flush() throws IOException {
            if (!closed) {
                writer.flush();
            }
        }

        public Path getFile() {
            return file;
        }

        public String getCommand() {
            return command;
        }

        public String getSessionUuid() {
            return sessionUuid;
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                writer.close();
                closed = true;
            }
        }
    }
}
