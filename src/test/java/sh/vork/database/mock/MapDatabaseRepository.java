package sh.vork.database.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import sh.vork.database.DatabaseEntity;
import sh.vork.database.DatabaseRepository;
import sh.vork.database.SearchQuery;
import sh.vork.database.SortOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * In-memory implementation of {@link DatabaseRepository#search}.
     *
     * <p>Each stored entity is deserialised to a {@code Map<String, Object>},
     * tested against every supplied {@code query} (AND semantics), sorted by
     * {@code sortField}, paged, and then deserialised to {@code T}.
     */
    @Override
    public Stream<T> search(int page, int pageSize, String sortField, SortOrder sortOrder,
                             SearchQuery... queries) {
        Comparator<Map<String, Object>> comparator = mapComparator(sortField, sortOrder);

        List<T> results = store.values().stream()
                .map(this::toMap)
                .filter(doc -> matchesAll(doc, queries))
                .sorted(comparator)
                .skip((long) page * pageSize)
                .limit(pageSize)
                .map(this::fromMap)
                .toList();

        return results.stream();
    }

    /** Returns the count of entities that match all supplied {@code queries}. */
    @Override
    public long searchCount(SearchQuery... queries) {
        return store.values().stream()
                .map(this::toMap)
                .filter(doc -> matchesAll(doc, queries))
                .count();
    }

    // -------------------------------------------------------------------------
    // Search helpers
    // -------------------------------------------------------------------------

    private static boolean matchesAll(Map<String, Object> doc, SearchQuery[] queries) {
        for (SearchQuery q : queries) {
            if (!q.test(doc)) return false;
        }
        return true;
    }

    private static Comparator<Map<String, Object>> mapComparator(String sortField,
                                                                   SortOrder sortOrder) {
        Comparator<Map<String, Object>> c = (a, b) -> {
            Object av = SearchQuery.normalize(SearchQuery.resolve(a, sortField));
            Object bv = SearchQuery.normalize(SearchQuery.resolve(b, sortField));
            if (av == null && bv == null) return 0;
            if (av == null) return 1;   // nulls last
            if (bv == null) return -1;
            return SearchQuery.compareValues(av, bv);
        };
        return sortOrder == SortOrder.DESC ? c.reversed() : c;
    }

    private Map<String, Object> toMap(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialise entity to map", e);
        }
    }

    private T fromMap(Map<String, Object> doc) {
        try {
            return mapper.readValue(mapper.writeValueAsString(doc), entityClass);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialise map to " + entityClass.getSimpleName(), e);
        }
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
