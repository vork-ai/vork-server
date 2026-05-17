package sh.vork.typegen;

import sh.vork.database.DatabaseEntity;
import sh.vork.database.DatabaseRepository;
import sh.vork.database.DatabaseRepositoryFactory;
import sh.vork.database.SearchQuery;
import sh.vork.database.SortOrder;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Routes {@link DatabaseRepository} operations to a per-type repository instance,
 * creating and caching repositories on demand via {@link DatabaseRepositoryFactory}.
 *
 * <p>All operations accept {@link Class} objects rather than static type parameters,
 * making this service usable with types that are only known at runtime (e.g. types
 * compiled and loaded via {@link TypeGeneratorService}).
 *
 * <p>Every class passed here must implement {@link DatabaseEntity}.
 */
@Service
public class TypeDatabaseService {

    private final DatabaseRepositoryFactory factory;
    private final ConcurrentHashMap<Class<?>, DatabaseRepository<?>> repositories = new ConcurrentHashMap<>();

    public TypeDatabaseService(DatabaseRepositoryFactory factory) {
        this.factory = factory;
    }

    /**
     * Returns the entity with the given {@code uuid}, or {@code null} if not found.
     */
    public Object get(Class<?> entityClass, String uuid) {
        return repositoryFor(entityClass).get(uuid);
    }

    /**
     * Creates or updates (upserts) an entity. The entity must implement
     * {@link DatabaseEntity}; its {@code uuid()} value is used as the document key.
     */
    public void save(Object entity) {
        if (!(entity instanceof DatabaseEntity de)) {
            throw new IllegalArgumentException(
                    "Entity must implement DatabaseEntity: " + entity.getClass().getName());
        }
        ((DatabaseRepository<DatabaseEntity>) repositoryFor(entity.getClass())).save(de);
    }

    /**
     * Deletes the entity with the given {@code uuid}. No-op if not found.
     */
    public void delete(Class<?> entityClass, String uuid) {
        repositoryFor(entityClass).delete(uuid);
    }

    /**
     * Returns a lazily-loaded stream of entities for the given page.
     * <strong>Must be closed</strong> by the caller (use try-with-resources).
     */
    public Stream<Object> list(Class<?> entityClass, int page, int pageSize) {
        return repositoryFor(entityClass).list(page, pageSize).map(e -> (Object) e);
    }

    /**
     * Returns the total number of stored entities of the given type.
     */
    public long count(Class<?> entityClass) {
        return repositoryFor(entityClass).count();
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Returns a lazily-loaded, sorted, paged stream of entities that match all
     * supplied {@code queries} (AND semantics).
     * <strong>Must be closed</strong> by the caller (use try-with-resources).
     */
    public Stream<Object> search(Class<?> entityClass, int page, int pageSize,
                                  String sortField, SortOrder sortOrder,
                                  SearchQuery... queries) {
        return repositoryFor(entityClass)
                .search(page, pageSize, sortField, sortOrder, queries)
                .map(e -> (Object) e);
    }

    /**
     * Returns the count of entities matching all supplied {@code queries}.
     */
    public long searchCount(Class<?> entityClass, SearchQuery... queries) {
        return repositoryFor(entityClass).searchCount(queries);
    }

    /**
     * Searches using a raw MongoDB JSON filter document.
     * <strong>Must be closed</strong> by the caller (use try-with-resources).
     *
     * @param filterJson a MongoDB filter as a JSON string,
     *                   e.g. {@code {"status":"active","age":{"$gt":18}}}
     */
    public Stream<Object> searchByMongoFilter(Class<?> entityClass, String filterJson,
                                               int page, int pageSize,
                                               String sortField, SortOrder sortOrder) {
        return repositoryFor(entityClass)
                .searchRaw(page, pageSize, sortField, sortOrder, filterJson)
                .map(e -> (Object) e);
    }

    /**
     * Returns the count of entities that match the supplied raw MongoDB filter.
     */
    public long searchCountByMongoFilter(Class<?> entityClass, String filterJson) {
        return repositoryFor(entityClass).searchCountRaw(filterJson);
    }

    /**
     * Searches using a SQL-like WHERE clause (without the {@code WHERE} keyword).
     * The clause is translated to {@link SearchQuery} predicates via
     * {@link SqlQueryParser}.
     *
     * <p>Examples: {@code "name = 'Alice' AND age > 18"},
     * {@code "status IN ('active','pending')"},
     * {@code "address.city = 'London'"}.
     *
     * <strong>Must be closed</strong> by the caller (use try-with-resources).
     *
     * @throws SqlParseException if the WHERE clause cannot be parsed
     */
    public Stream<Object> searchBySql(Class<?> entityClass, String sqlWhere,
                                       int page, int pageSize,
                                       String sortField, SortOrder sortOrder) {
        SearchQuery query = SqlQueryParser.parse(sqlWhere);
        return search(entityClass, page, pageSize, sortField, sortOrder, query);
    }

    /**
     * Returns the count of entities that match the supplied SQL-like WHERE clause.
     *
     * @throws SqlParseException if the WHERE clause cannot be parsed
     */
    public long searchCountBySql(Class<?> entityClass, String sqlWhere) {
        SearchQuery query = SqlQueryParser.parse(sqlWhere);
        return searchCount(entityClass, query);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private <T extends DatabaseEntity> DatabaseRepository<T> repositoryFor(Class<?> clazz) {
        if (!DatabaseEntity.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException(
                    "Class must implement DatabaseEntity: " + clazz.getName());
        }
        return (DatabaseRepository<T>) repositories.computeIfAbsent(
                clazz, c -> factory.create((Class<T>) c));
    }
}
