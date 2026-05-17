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

    /**
     * Returns a lazily-loaded stream of entities that match all of the supplied
     * {@code queries} (combined with AND), sorted by {@code sortField} in the
     * given {@code sortOrder} and paged with {@code skip/limit}.
     *
     * <p>Passing no queries returns all documents (equivalent to {@link #list}).
     *
     * <p>The stream <strong>must</strong> be closed after consumption so that
     * the underlying backend cursor is released. Use try-with-resources.
     *
     * @param page      zero-based page index
     * @param pageSize  maximum number of entities to return
     * @param sortField document field name to sort by (dot notation supported)
     * @param sortOrder {@link SortOrder#ASC} or {@link SortOrder#DESC}
     * @param queries   zero or more predicates; all must match (AND semantics)
     * @return a closeable {@link Stream} — close it when done
     */
    Stream<T> search(int page, int pageSize, String sortField, SortOrder sortOrder,
                     SearchQuery... queries);

    /**
     * Returns the total count of entities that match all of the supplied
     * {@code queries} (combined with AND).
     *
     * @param queries zero or more predicates; all must match (AND semantics)
     * @return number of matching documents
     */
    long searchCount(SearchQuery... queries);

    /**
     * Returns a lazily-loaded stream of entities that match the supplied raw
     * MongoDB filter JSON, sorted and paged.
     *
     * <p>This is intended for use by the AI via the {@code searchTypeInstances}
     * tool, which can supply a MongoDB query document directly.
     *
     * <p><strong>The caller must close this stream.</strong>
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Only {@link sh.vork.database.mongo.MongoDBRepository} supports this method;
     * the in-memory mock does not.
     *
     * @param page         zero-based page index
     * @param pageSize     maximum number of entities to return
     * @param sortField    document field name to sort by
     * @param sortOrder    sort direction
     * @param filterJson   a MongoDB filter document as a JSON string,
     *                     e.g. {@code {"status":"active","age":{"$gt":18}}}
     * @return a closeable {@link Stream} — close it when done
     */
    default Stream<T> searchRaw(int page, int pageSize, String sortField, SortOrder sortOrder,
                                String filterJson) {
        throw new UnsupportedOperationException(
                "Raw MongoDB filter queries are not supported by this repository implementation");
    }

    /**
     * Returns the count of entities that match the supplied raw MongoDB filter JSON.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     *
     * @param filterJson a MongoDB filter document as a JSON string
     * @return number of matching documents
     */
    default long searchCountRaw(String filterJson) {
        throw new UnsupportedOperationException(
                "Raw MongoDB filter queries are not supported by this repository implementation");
    }
}
