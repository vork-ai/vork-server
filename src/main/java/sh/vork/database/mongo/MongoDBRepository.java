package sh.vork.database.mongo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import sh.vork.database.DatabaseEntity;
import sh.vork.database.DatabaseException;
import sh.vork.database.DatabaseRepository;
import org.bson.Document;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * MongoDB-backed implementation of {@link DatabaseRepository}.
 *
 * <h3>Serialisation strategy</h3>
 * <ol>
 *   <li>Jackson serialises the entity record to a JSON string.</li>
 *   <li>The JSON is parsed into a MongoDB {@link Document}.</li>
 *   <li>The entity's {@code uuid()} value is stored as the document's {@code _id}.</li>
 * </ol>
 * On retrieval the {@code _id} field is stripped before handing the document back to
 * Jackson; the {@code uuid} field within the document body carries the identifier.
 *
 * <h3>Collection naming</h3>
 * The MongoDB collection name is derived automatically from the entity class simple
 * name via {@code CamelCase → snake_case}: {@code ProductEntity → product_entity}.
 *
 * <h3>Stream lifecycle</h3>
 * {@link #list} returns a {@link Stream} whose close handler shuts the underlying
 * MongoDB cursor. Always consume the stream inside a try-with-resources block.
 *
 * @param <T> the entity type
 */
public class MongoDBRepository<T extends DatabaseEntity> implements DatabaseRepository<T> {

    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);

    private final Class<T> entityClass;
    private final MongoCollection<Document> collection;
    private final ObjectMapper mapper;

    public MongoDBRepository(Class<T> entityClass, MongoDatabase database, ObjectMapper mapper) {
        this.entityClass = entityClass;
        this.mapper = mapper;
        this.collection = database.getCollection(collectionName(entityClass));
    }

    // -------------------------------------------------------------------------
    // DatabaseRepository implementation
    // -------------------------------------------------------------------------

    @Override
    public T get(String uuid) {
        Document doc = collection.find(Filters.eq("_id", uuid)).first();
        return doc == null ? null : deserialize(doc);
    }

    @Override
    public void save(T entity) {
        collection.replaceOne(Filters.eq("_id", entity.uuid()), serialize(entity), UPSERT);
    }

    @Override
    public void delete(String uuid) {
        collection.deleteOne(Filters.eq("_id", uuid));
    }

    /**
     * Returns a lazily-loaded stream backed by a MongoDB cursor.
     *
     * <p>Note: MongoDB's {@code skip()} scans over skipped documents, which can be
     * slow on very large collections. For high-volume use consider cursor-based or
     * keyset pagination instead.
     *
     * <p><strong>The caller must close this stream.</strong>
     */
    @Override
    public Stream<T> list(int page, int pageSize) {
        MongoCursor<Document> cursor = collection
                .find()
                .skip(page * pageSize)
                .limit(pageSize)
                .cursor();

        Iterator<T> iterator = new Iterator<>() {
            @Override
            public boolean hasNext() {
                return cursor.hasNext();
            }

            @Override
            public T next() {
                return deserialize(cursor.next());
            }
        };

        return StreamSupport
                .stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false)
                .onClose(cursor::close);
    }

    @Override
    public long count() {
        return collection.countDocuments();
    }

    // -------------------------------------------------------------------------
    // Serialisation helpers
    // -------------------------------------------------------------------------

    /**
     * Serialises the entity to a MongoDB {@link Document}, setting {@code _id}
     * to the entity's UUID so MongoDB can index it efficiently.
     */
    private Document serialize(T entity) {
        try {
            String json = mapper.writeValueAsString(entity);
            Document doc = Document.parse(json);
            doc.put("_id", entity.uuid());
            return doc;
        } catch (JsonProcessingException e) {
            throw new DatabaseException(
                    "Failed to serialise " + entityClass.getSimpleName() + " with uuid=" + entity.uuid(), e);
        }
    }

    /**
     * Deserialises a MongoDB {@link Document} back to the entity record type.
     * The {@code _id} field is removed; the {@code uuid} field in the document
     * body provides the identifier for the record's canonical constructor.
     */
    private T deserialize(Document doc) {
        try {
            // Copy into a plain map so we can safely remove the _id key without
            // mutating the driver-owned Document.
            Map<String, Object> map = new LinkedHashMap<>(doc);
            map.remove("_id");
            String json = mapper.writeValueAsString(map);
            return mapper.readValue(json, entityClass);
        } catch (JsonProcessingException e) {
            throw new DatabaseException("Failed to deserialise " + entityClass.getSimpleName(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * Converts a {@code CamelCase} class name to a {@code snake_case} collection name.
     * Examples: {@code Product → product}, {@code ProductEntity → product_entity}.
     */
    static String collectionName(Class<?> clazz) {
        return clazz.getSimpleName()
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toLowerCase();
    }
}
