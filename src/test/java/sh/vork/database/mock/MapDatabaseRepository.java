package sh.vork.database.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import sh.vork.database.DatabaseEntity;
import sh.vork.database.DatabaseRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * In-memory mock implementation of {@link DatabaseRepository}.
 *
 * <p>Entities are serialised to JSON strings with Jackson and stored in a
 * {@link LinkedHashMap} keyed on their UUID. Insertion order is preserved so
 * that {@link #list} returns results in the order they were first saved.
 *
 * <p>This class is intended exclusively for tests; it has no external
 * dependencies and no Spring wiring required.
 *
 * <pre>{@code
 * DatabaseRepository<MyEntity> db = new MapDatabaseRepository<>(MyEntity.class);
 * }</pre>
 *
 * @param <T> the entity type
 */
public class MapDatabaseRepository<T extends DatabaseEntity> implements DatabaseRepository<T> {

    private final Map<String, String> store = new LinkedHashMap<>();
    private final Class<T> entityClass;
    private final ObjectMapper mapper;

    public MapDatabaseRepository(Class<T> entityClass) {
        this.entityClass = entityClass;
        this.mapper = new ObjectMapper().findAndRegisterModules();
    }

    // -------------------------------------------------------------------------
    // DatabaseRepository implementation
    // -------------------------------------------------------------------------

    @Override
    public void save(T entity) {
        try {
            store.put(entity.uuid(), mapper.writeValueAsString(entity));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(
                    "Failed to serialise entity of type " + entityClass.getSimpleName(), e);
        }
    }

    @Override
    public T get(String uuid) {
        String json = store.get(uuid);
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, entityClass);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(
                    "Failed to deserialise entity of type " + entityClass.getSimpleName(), e);
        }
    }

    @Override
    public void delete(String uuid) {
        store.remove(uuid);
    }

    /**
     * Returns a stream over the requested page of entities.
     *
     * <p>The stream is backed by an in-memory list, so closing it is a no-op —
     * but callers should still use try-with-resources to mirror production usage
     * and to be forwards-compatible if the backing store changes.
     */
    @Override
    public Stream<T> list(int page, int pageSize) {
        var pageData = new ArrayList<>(store.values()).stream()
                .skip((long) page * pageSize)
                .limit(pageSize)
                .map(json -> {
                    try {
                        return mapper.readValue(json, entityClass);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException("Failed to deserialise entity", e);
                    }
                })
                .toList();
        return pageData.stream();
    }

    @Override
    public long count() {
        return store.size();
    }

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    /**
     * Exposes the raw JSON store for test assertions.
     *
     * @return an unmodifiable view of the underlying map (UUID → JSON string)
     */
    public Map<String, String> getJsonStore() {
        return Collections.unmodifiableMap(store);
    }
}
