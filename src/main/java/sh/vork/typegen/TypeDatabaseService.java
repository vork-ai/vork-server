package sh.vork.typegen;

import sh.vork.database.DatabaseEntity;
import sh.vork.database.DatabaseRepository;
import sh.vork.database.DatabaseRepositoryFactory;
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

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

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
