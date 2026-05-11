package sh.vork.database;

import java.util.stream.Stream;

/**
 * Generic repository contract for Vork database entities.
 *
 * <p>{@code save} performs an upsert — it creates a new document when no
 * document with the given {@code uuid} exists, and replaces the existing one
 * when it does.
 *
 * <p>The {@link #list} method returns a <em>lazily-loaded</em> {@link Stream}
 * backed by the underlying cursor. Callers <strong>must</strong> close the
 * stream after use, ideally via try-with-resources:
 *
 * <pre>{@code
 * try (Stream<Product> page = repo.list(0, 50)) {
 *     page.forEach(this::handle);
 * }
 * }</pre>
 *
 * @param <T> the entity type, must implement {@link DatabaseEntity}
 */
public interface DatabaseRepository<T extends DatabaseEntity> {

    /**
     * Retrieves an entity by its UUID.
     *
     * @return the entity, or {@code null} if not found
     */
    T get(String uuid);

    /**
     * Creates or replaces the entity identified by {@code entity.uuid()}.
     */
    void save(T entity);

    /**
     * Deletes the entity with the given UUID. No-op if not found.
     */
    void delete(String uuid);

    /**
     * Returns a lazily-loaded, ordered stream of entities for the requested page.
     *
     * <p>The stream <strong>must</strong> be closed after consumption so that
     * the underlying backend cursor is released. Use try-with-resources.
     *
     * @param page     zero-based page index
     * @param pageSize maximum number of entities to return
     * @return a closeable {@link Stream} — close it when done
     */
    Stream<T> list(int page, int pageSize);

    /**
     * Returns the total number of documents in the store.
     */
    long count();
}
