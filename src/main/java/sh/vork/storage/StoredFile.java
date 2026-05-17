package sh.vork.storage;

import sh.vork.database.DatabaseEntity;

/**
 * Metadata for a file that has been uploaded and persisted by {@link FileStorageService}.
 *
 * <p>The actual bytes are managed by the active {@link FileStorageProvider}; this
 * record is stored in MongoDB (collection: {@code stored_file}) so that metadata can
 * be retrieved without touching the storage back-end.
 *
 * @param uuid         unique file identifier (also the MongoDB {@code _id})
 * @param originalName filename supplied by the client at upload time
 * @param mimeType     MIME type detected or declared by the client
 * @param sizeBytes    file size in bytes
 * @param storagePath  opaque path managed by the active {@link FileStorageProvider}
 * @param uploadedAt   epoch-milliseconds when the file was stored
 */
public record StoredFile(
        String uuid,
        String originalName,
        String mimeType,
        long   sizeBytes,
        String storagePath,
        long   uploadedAt
) implements DatabaseEntity {}
