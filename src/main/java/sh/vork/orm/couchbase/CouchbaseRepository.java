package sh.vork.orm.couchbase;

import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.GetResult;
import com.couchbase.client.java.query.QueryOptions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import sh.vork.orm.DatabaseEntity;
import sh.vork.orm.DatabaseException;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.MongoLikeFilterEvaluator;
import sh.vork.orm.SearchQuery;
import sh.vork.orm.SortOrder;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Couchbase-backed implementation of {@link DatabaseRepository}.
 *
 * <p>Entities are stored as JSON documents keyed by
 * {@code {collection_name}:{uuid}} in the configured bucket/scope/collection.
 * List and search operations run a prefix query and evaluate predicates in-memory.
 */
public class CouchbaseRepository<T extends DatabaseEntity> implements DatabaseRepository<T> {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Class<T> entityClass;
    private final Cluster cluster;
    private final Collection collection;
    private final ObjectMapper mapper;
    private final String fromExpr;
    private final String keyPrefix;

    public CouchbaseRepository(Class<T> entityClass,
                               Cluster cluster,
                               Collection collection,
                               ObjectMapper mapper,
                               String bucket,
                               String scope,
                               String collectionName) {
        this.entityClass = entityClass;
        this.cluster = cluster;
        this.collection = collection;
        this.mapper = mapper;
        this.fromExpr = "`" + bucket + "`.`" + scope + "`.`" + collectionName + "`";
        this.keyPrefix = collectionName(entityClass) + ":";
    }

    @Override
    public T get(String uuid) {
        try {
            GetResult result = collection.get(key(uuid));
            return deserialize(result.contentAsObject());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void save(T entity) {
        try {
            JsonObject payload = JsonObject.fromJson(mapper.writeValueAsString(entity));
            collection.upsert(key(entity.uuid()), payload);
        } catch (Exception e) {
            throw new DatabaseException("Failed to save entity: " + entity.uuid(), e);
        }
    }

    @Override
    public void delete(String uuid) {
        try {
            collection.remove(key(uuid));
        } catch (Exception ignored) {
            // Ignore missing keys to preserve no-op semantics.
        }
    }

    @Override
    public Stream<T> list(int page, int pageSize) {
        List<T> all = fetchAllMaps().stream()
                .map(this::fromMap)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(DatabaseEntity::uuid))
                .toList();
        return all.stream().skip((long) page * pageSize).limit(pageSize);
    }

    @Override
    public long count() {
        return fetchAllMaps().size();
    }

    @Override
    public T get(SearchQuery... queries) {
        return fetchAllMaps().stream()
                .filter(doc -> matchesAll(doc, queries))
                .findFirst()
                .map(this::fromMap)
                .orElse(null);
    }

    @Override
    public Stream<T> search(int page, int pageSize, String sortField, SortOrder sortOrder,
                            SearchQuery... queries) {
        Comparator<Map<String, Object>> cmp = mapComparator(sortField, sortOrder);
        List<T> results = fetchAllMaps().stream()
                .filter(doc -> matchesAll(doc, queries))
                .sorted(cmp)
                .skip((long) page * pageSize)
                .limit(pageSize)
                .map(this::fromMap)
                .filter(Objects::nonNull)
                .toList();
        return results.stream();
    }

    @Override
    public long searchCount(SearchQuery... queries) {
        return fetchAllMaps().stream().filter(doc -> matchesAll(doc, queries)).count();
    }

    @Override
    public Stream<T> searchRaw(int page, int pageSize, String sortField, SortOrder sortOrder,
                               String filterJson) {
        Map<String, Object> filter = MongoLikeFilterEvaluator.parseFilter(mapper, filterJson);
        Comparator<Map<String, Object>> cmp = mapComparator(sortField, sortOrder);
        List<T> results = fetchAllMaps().stream()
                .filter(doc -> MongoLikeFilterEvaluator.matches(doc, filter))
                .sorted(cmp)
                .skip((long) page * pageSize)
                .limit(pageSize)
                .map(this::fromMap)
                .filter(Objects::nonNull)
                .toList();
        return results.stream();
    }

    @Override
    public long searchCountRaw(String filterJson) {
        Map<String, Object> filter = MongoLikeFilterEvaluator.parseFilter(mapper, filterJson);
        return fetchAllMaps().stream().filter(doc -> MongoLikeFilterEvaluator.matches(doc, filter)).count();
    }

    private List<Map<String, Object>> fetchAllMaps() {
        String sql = "SELECT d.* FROM " + fromExpr + " AS d WHERE META(d).id LIKE $prefix";
        JsonObject params = JsonObject.create().put("prefix", keyPrefix + "%");
        try {
            return cluster.query(sql, QueryOptions.queryOptions().parameters(params))
                    .rowsAsObject().stream()
                    .map(this::toMap)
                    .toList();
        } catch (Exception e) {
            throw new DatabaseException("Failed to query Couchbase collection", e);
        }
    }

    private T fromMap(Map<String, Object> doc) {
        try {
            return mapper.readValue(mapper.writeValueAsString(doc), entityClass);
        } catch (JsonProcessingException e) {
            throw new DatabaseException("Failed to deserialize " + entityClass.getSimpleName(), e);
        }
    }

    private T deserialize(JsonObject payload) {
        try {
            return mapper.readValue(payload.toString(), entityClass);
        } catch (JsonProcessingException e) {
            throw new DatabaseException("Failed to deserialize " + entityClass.getSimpleName(), e);
        }
    }

    private Map<String, Object> toMap(JsonObject row) {
        try {
            return mapper.readValue(row.toString(), MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new DatabaseException("Failed to deserialize row map", e);
        }
    }

    private String key(String uuid) {
        return keyPrefix + uuid;
    }

    private static boolean matchesAll(Map<String, Object> doc, SearchQuery[] queries) {
        for (SearchQuery q : queries) {
            if (!q.test(doc)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings({"unchecked"})
    private static Comparator<Map<String, Object>> mapComparator(String field, SortOrder order) {
        Comparator<Map<String, Object>> cmp = (a, b) -> {
            Object va = getNestedValue(a, field);
            Object vb = getNestedValue(b, field);
            if (va == null && vb == null) return 0;
            if (va == null) return 1;
            if (vb == null) return -1;
            if (va instanceof Comparable ca && vb instanceof Comparable) {
                try {
                    return ca.compareTo(vb);
                } catch (ClassCastException ignored) {
                    // Fall through to String comparison.
                }
            }
            return va.toString().compareTo(vb.toString());
        };
        return order == SortOrder.DESC ? cmp.reversed() : cmp;
    }

    @SuppressWarnings("unchecked")
    private static Object getNestedValue(Map<String, Object> map, String dotPath) {
        String[] parts = dotPath.split("\\.", 2);
        Object value = map.get(parts[0]);
        if (parts.length == 1 || !(value instanceof Map)) {
            return value;
        }
        return getNestedValue((Map<String, Object>) value, parts[1]);
    }

    static String collectionName(Class<?> clazz) {
        return clazz.getSimpleName()
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toLowerCase();
    }
}
