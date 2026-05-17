package sh.vork.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * Strategy interface for backing file storage.
 *
 * <p>Implementations persist raw file bytes and return an opaque {@code storagePath}
 * that can later be passed back to {@link #retrieve} or {@link #delete}.
 * The path has no meaning to callers — it is stored in {@link StoredFile#storagePath()}
 * and used only internally.
 */
public interface FileStorageProvider {

    /**
     * Persists {@code content} and returns an opaque storage path.
     *
     * @param uuid         unique ID for this file (used to derive the storage path)
     * @param originalName original filename supplied by the client
     * @param mimeType     MIME type of the content
     * @param content      raw bytes; caller is responsible for closing the stream
     * @param sizeBytes    number of bytes in {@code content} (may be -1 if unknown)
     * @return opaque storage path
     */
    String store(String uuid, String originalName, String mimeType,
                 InputStream content, long sizeBytes) throws IOException;

    /**
     * Opens a stream for reading the stored file.
     *
     * @param storagePath opaque path returned by {@link #store}
     * @return a new {@link InputStream} positioned at byte 0; caller must close it
     * @throws IOException if the file cannot be found or read
     */
    InputStream retrieve(String storagePath) throws IOException;

    /**
     * Removes the stored file. No-op if the path does not exist.
     *
     * @param storagePath opaque path returned by {@link #store}
     */
    void delete(String storagePath) throws IOException;
}
