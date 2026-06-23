package sh.vork.ai.mongo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.springframework.stereotype.Service;
import sh.vork.ai.function.CreateMongoDbConnectionRequest;
import sh.vork.ai.function.DeleteMongoDbDocumentsRequest;
import sh.vork.ai.function.GetMongoDbCollectionSchemaRequest;
import sh.vork.ai.function.InsertMongoDbDocumentRequest;
import sh.vork.ai.function.ListMongoDbCollectionsRequest;
import sh.vork.ai.function.SearchMongoDbDocumentsRequest;
import sh.vork.ai.function.UpdateMongoDbDocumentsRequest;
import sh.vork.security.SecureCredentialStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Generic MongoDB utility service used by AI tools for connecting and running
 * read/write/search/delete operations against arbitrary external MongoDB databases.
 */
@Service
public class MongoToolService {

    private static final String SECRET_PREFIX = "MONGODB_CONNECTION_";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final SecureCredentialStore secureCredentialStore;
    private final ObjectMapper objectMapper;

    public MongoToolService(SecureCredentialStore secureCredentialStore, ObjectMapper objectMapper) {
        this.secureCredentialStore = secureCredentialStore;
        this.objectMapper = objectMapper;
    }

    public String createConnection(String username, CreateMongoDbConnectionRequest req) {
        if (username == null || username.isBlank()) {
            return error("Authenticated user is required.");
        }
        ConnectionProfile profile;
        try {
            profile = profileFromRequest(req);
        } catch (IllegalArgumentException ex) {
            return error(ex.getMessage());
        }

        try (MongoClient client = buildClient(profile)) {
            client.getDatabase(profile.database()).runCommand(new Document("ping", 1));
        } catch (Exception e) {
            return error("Cannot connect to MongoDB: " + e.getMessage());
        }

        try {
            String secretKey = secretKeyFor(profile.connectionName());
            secureCredentialStore.saveSecretForUser(username, secretKey, objectMapper.writeValueAsString(profile));
            return objectMapper.writeValueAsString(Map.of(
                    "status", "ok",
                    "connectionName", profile.connectionName(),
                    "database", profile.database()));
        } catch (Exception e) {
            return error("Failed to store MongoDB connection profile: " + e.getMessage());
        }
    }

    public String listCollections(String username, ListMongoDbCollectionsRequest req) {
        try {
            ConnectionProfile profile = resolveProfile(username, req == null ? null : req.connectionName());
            try (MongoClient client = buildClient(profile)) {
                MongoDatabase db = client.getDatabase(profile.database());
            List<String> collections = new ArrayList<>();
            db.listCollectionNames().into(collections);
            return objectMapper.writeValueAsString(Map.of(
                    "connectionName", profile.connectionName(),
                    "database", db.getName(),
                    "collections", collections));
            }
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    public String getCollectionSchema(String username, GetMongoDbCollectionSchemaRequest req) {
        try {
            ConnectionProfile profile = resolveProfile(username, req == null ? null : req.connectionName());
            try (MongoClient client = buildClient(profile)) {
                MongoDatabase db = client.getDatabase(profile.database());
                List<String> allCollections = new ArrayList<>();
                db.listCollectionNames().into(allCollections);
                String collectionName = resolveCollection(req == null ? null : req.collection(),
                        req == null ? null : req.query(), allCollections);
                if (collectionName == null) {
                    return error("Could not resolve collection. Provide collection explicitly.");
                }
                MongoCollection<Document> collection = db.getCollection(collectionName);
                int sampleSize = req != null && req.sampleSize() != null && req.sampleSize() > 0
                        ? req.sampleSize() : 20;

                List<Document> sampleDocs = collection.find().limit(sampleSize).into(new ArrayList<>());
                Map<String, Set<String>> fieldTypes = new LinkedHashMap<>();
                for (Document doc : sampleDocs) {
                    for (Map.Entry<String, Object> entry : doc.entrySet()) {
                        fieldTypes.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>())
                                .add(inferType(entry.getValue()));
                    }
                }

                Map<String, Object> fields = new LinkedHashMap<>();
                for (Map.Entry<String, Set<String>> e : fieldTypes.entrySet()) {
                    fields.put(e.getKey(), e.getValue());
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("connectionName", profile.connectionName());
                result.put("database", profile.database());
                result.put("collection", collectionName);
                result.put("sampleSize", sampleDocs.size());
                result.put("fields", fields);
                return objectMapper.writeValueAsString(result);
            }
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    public String searchDocuments(String username, SearchMongoDbDocumentsRequest req) {
        try {
            ConnectionProfile profile = resolveProfile(username, req == null ? null : req.connectionName());
            try (MongoClient client = buildClient(profile)) {
                MongoDatabase db = client.getDatabase(profile.database());
                List<String> allCollections = new ArrayList<>();
                db.listCollectionNames().into(allCollections);
                String collectionName = resolveCollection(
                        req == null ? null : req.collection(),
                        req == null ? null : req.query(),
                        allCollections);
                if (collectionName == null) {
                    return error("Could not resolve collection. Provide collection explicitly.");
                }

                MongoCollection<Document> collection = db.getCollection(collectionName);
                Document filter = buildFilter(req == null ? null : req.filterJson(), req == null ? null : req.query(), collection);
                int page = req != null && req.page() != null && req.page() >= 0 ? req.page() : 0;
                int pageSize = req != null && req.pageSize() != null && req.pageSize() > 0 ? req.pageSize() : 20;
                String sortField = req != null && req.sortField() != null && !req.sortField().isBlank() ? req.sortField() : "_id";
                boolean desc = req != null && "DESC".equalsIgnoreCase(req.sortOrder());

                var find = collection.find(filter)
                        .sort(desc ? Sorts.descending(sortField) : Sorts.ascending(sortField))
                        .skip(page * pageSize)
                        .limit(pageSize);
                Document projection = parseProjection(req == null ? null : req.projectionJson());
                if (projection != null) {
                    find = find.projection(projection);
                }

                List<Map<String, Object>> docs = new ArrayList<>();
                for (Document d : find) {
                    docs.add(objectMapper.readValue(d.toJson(), MAP_TYPE));
                }

                long total = collection.countDocuments(filter);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("connectionName", profile.connectionName());
                result.put("database", profile.database());
                result.put("collection", collectionName);
                result.put("total", total);
                result.put("page", page);
                result.put("pageSize", pageSize);
                result.put("filter", filter);
                result.put("results", docs);
                return objectMapper.writeValueAsString(result);
            }
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    public String insertDocument(String username, InsertMongoDbDocumentRequest req) {
        try {
            if (req == null || req.collection() == null || req.collection().isBlank()) {
                return error("collection is required.");
            }
            if (req.documentJson() == null || req.documentJson().isBlank()) {
                return error("documentJson is required.");
            }
            ConnectionProfile profile = resolveProfile(username, req.connectionName());
            try (MongoClient client = buildClient(profile)) {
                MongoCollection<Document> collection = client.getDatabase(profile.database()).getCollection(req.collection().trim());
                Document doc = Document.parse(req.documentJson());
                InsertOneResult result = collection.insertOne(doc);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "ok");
                out.put("insertedId", result.getInsertedId());
                out.put("collection", req.collection().trim());
                return objectMapper.writeValueAsString(out);
            }
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    public String updateDocuments(String username, UpdateMongoDbDocumentsRequest req) {
        try {
            ConnectionProfile profile = resolveProfile(username, req == null ? null : req.connectionName());
            try (MongoClient client = buildClient(profile)) {
                MongoDatabase db = client.getDatabase(profile.database());
                List<String> allCollections = new ArrayList<>();
                db.listCollectionNames().into(allCollections);
                String collectionName = resolveCollection(
                        req == null ? null : req.collection(),
                        req == null ? null : req.query(),
                        allCollections);
                if (collectionName == null) {
                    return error("Could not resolve collection. Provide collection explicitly.");
                }
                if (req == null || req.updateJson() == null || req.updateJson().isBlank()) {
                    return error("updateJson is required.");
                }

                MongoCollection<Document> collection = db.getCollection(collectionName);
                Document filter = buildFilter(req.filterJson(), req.query(), collection);
                Document update = Document.parse(req.updateJson());
                boolean multi = Boolean.TRUE.equals(req.multi());
                UpdateOptions options = new UpdateOptions().upsert(Boolean.TRUE.equals(req.upsert()));

                UpdateResult result = multi
                        ? collection.updateMany(filter, update, options)
                        : collection.updateOne(filter, update, options);

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "ok");
                out.put("collection", collectionName);
                out.put("matchedCount", result.getMatchedCount());
                out.put("modifiedCount", result.getModifiedCount());
                out.put("upsertedId", result.getUpsertedId());
                return objectMapper.writeValueAsString(out);
            }
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    public String deleteDocuments(String username, DeleteMongoDbDocumentsRequest req) {
        try {
            ConnectionProfile profile = resolveProfile(username, req == null ? null : req.connectionName());
            try (MongoClient client = buildClient(profile)) {
                MongoDatabase db = client.getDatabase(profile.database());
                List<String> allCollections = new ArrayList<>();
                db.listCollectionNames().into(allCollections);
                String collectionName = resolveCollection(
                        req == null ? null : req.collection(),
                        req == null ? null : req.query(),
                        allCollections);
                if (collectionName == null) {
                    return error("Could not resolve collection. Provide collection explicitly.");
                }

                MongoCollection<Document> collection = db.getCollection(collectionName);
                Document filter = buildFilter(req == null ? null : req.filterJson(), req == null ? null : req.query(), collection);
                boolean multi = req != null && Boolean.TRUE.equals(req.multi());
                DeleteResult result = multi ? collection.deleteMany(filter) : collection.deleteOne(filter);

                return objectMapper.writeValueAsString(Map.of(
                        "status", "ok",
                        "collection", collectionName,
                        "deletedCount", result.getDeletedCount()));
            }
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    private ConnectionProfile resolveProfile(String username, String connectionName) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Authenticated user is required.");
        }
        String effectiveName = normalizeConnectionName(connectionName);
        String secret = secureCredentialStore.getSecretForUser(username, secretKeyFor(effectiveName));
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("MongoDB connection profile not found: " + effectiveName
                    + ". Call createMongoDBConnection first.");
        }
        try {
            return objectMapper.readValue(secret, ConnectionProfile.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Stored MongoDB connection profile is invalid: " + effectiveName);
        }
    }

    private ConnectionProfile profileFromRequest(CreateMongoDbConnectionRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("Connection input is required.");
        }
        String connectionName = normalizeConnectionName(req.connectionName());
        String connectionString = blankToNull(req.connectionString());
        String host = blankToNull(req.host());
        Integer port = req.port();
        String database = blankToNull(req.database());
        String authDatabase = blankToNull(req.authDatabase());
        String username = blankToNull(req.username());
        String password = req.password();

        if (connectionString == null) {
            host = host == null ? "localhost" : host;
            port = (port == null || port <= 0) ? 27017 : port;
            if (database == null) {
                throw new IllegalArgumentException("database is required when connectionString is not provided.");
            }
        } else if (database == null) {
            String parsedDb = parseDatabaseFromConnectionString(connectionString);
            if (parsedDb != null) {
                database = parsedDb;
            }
        }

        if (database == null) {
            throw new IllegalArgumentException("database is required.");
        }

        return new ConnectionProfile(connectionName, connectionString, host, port, database,
                authDatabase != null ? authDatabase : "admin", username, password);
    }

    private MongoClient buildClient(ConnectionProfile profile) {
        if (profile.connectionString() != null && !profile.connectionString().isBlank()) {
            return MongoClients.create(profile.connectionString());
        }

        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyToClusterSettings(cs -> cs.hosts(List.of(new ServerAddress(profile.host(), profile.port()))))
                .applyToSocketSettings(ss -> ss.connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS))
                .applyToConnectionPoolSettings(cp -> cp.maxWaitTime(5, TimeUnit.SECONDS));

        if (profile.username() != null && !profile.username().isBlank()) {
            builder.credential(MongoCredential.createCredential(
                    profile.username(),
                    profile.authDatabase() != null ? profile.authDatabase() : "admin",
                    profile.password() != null ? profile.password().toCharArray() : new char[0]));
        }
        return MongoClients.create(builder.build());
    }

    private Document buildFilter(String filterJson, String naturalLanguageQuery, MongoCollection<Document> collection) {
        if (filterJson != null && !filterJson.isBlank()) {
            return Document.parse(filterJson);
        }
        if (naturalLanguageQuery == null || naturalLanguageQuery.isBlank()) {
            return new Document();
        }

        String query = naturalLanguageQuery.trim();
        List<Document> sampleDocs = collection.find().limit(25).into(new ArrayList<>());
        Set<String> stringFields = new LinkedHashSet<>();
        for (Document doc : sampleDocs) {
            for (Map.Entry<String, Object> e : doc.entrySet()) {
                if (e.getValue() instanceof String) {
                    stringFields.add(e.getKey());
                }
            }
        }

        if (stringFields.isEmpty()) {
            return new Document();
        }

        List<Document> clauses = new ArrayList<>();
        for (String field : stringFields) {
            clauses.add(new Document(field, new Document("$regex", query).append("$options", "i")));
        }
        return new Document("$or", clauses);
    }

    private Document parseProjection(String projectionJson) {
        if (projectionJson == null || projectionJson.isBlank()) {
            return null;
        }
        return Document.parse(projectionJson);
    }

    private String resolveCollection(String explicitCollection, String query, List<String> collections) {
        if (explicitCollection != null && !explicitCollection.isBlank()) {
            String c = explicitCollection.trim();
            if (collections.contains(c)) {
                return c;
            }
            for (String existing : collections) {
                if (existing.equalsIgnoreCase(c)) {
                    return existing;
                }
            }
            return null;
        }

        if (collections == null || collections.isEmpty()) {
            return null;
        }
        if (collections.size() == 1) {
            return collections.get(0);
        }
        if (query == null || query.isBlank()) {
            return null;
        }

        String normalizedQuery = normalizeText(query);
        int bestScore = 0;
        String best = null;
        for (String collection : collections) {
            int score = scoreCollectionMatch(collection, normalizedQuery);
            if (score > bestScore) {
                bestScore = score;
                best = collection;
            }
        }
        return bestScore > 0 ? best : null;
    }

    private static int scoreCollectionMatch(String collection, String normalizedQuery) {
        if (collection == null || collection.isBlank()) {
            return 0;
        }
        String normalizedCollection = normalizeText(collection);
        int score = 0;

        if (normalizedQuery.contains(normalizedCollection)) {
            score += 5;
        }

        String singular = singularize(normalizedCollection);
        if (!singular.equals(normalizedCollection) && normalizedQuery.contains(singular)) {
            score += 3;
        }

        String plural = pluralize(normalizedCollection);
        if (!plural.equals(normalizedCollection) && normalizedQuery.contains(plural)) {
            score += 3;
        }

        for (String token : normalizedCollection.split("\\s+")) {
            if (!token.isBlank() && normalizedQuery.contains(token)) {
                score += 1;
            }
        }
        return score;
    }

    private static String normalizeText(String value) {
        return value == null ? ""
                : value.toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String singularize(String value) {
        if (value.endsWith("ies") && value.length() > 3) {
            return value.substring(0, value.length() - 3) + "y";
        }
        if (value.endsWith("s") && value.length() > 1) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String pluralize(String value) {
        if (value.endsWith("y") && value.length() > 1) {
            return value.substring(0, value.length() - 1) + "ies";
        }
        if (!value.endsWith("s")) {
            return value + "s";
        }
        return value;
    }

    private static String inferType(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Integer || value instanceof Long) {
            return "integer";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        if (value instanceof Document || value instanceof Map<?, ?>) {
            return "object";
        }
        return value.getClass().getSimpleName();
    }

    private static String parseDatabaseFromConnectionString(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        int slash = uri.lastIndexOf('/');
        if (slash < 0 || slash == uri.length() - 1) {
            return null;
        }
        String trailing = uri.substring(slash + 1);
        int queryIdx = trailing.indexOf('?');
        if (queryIdx >= 0) {
            trailing = trailing.substring(0, queryIdx);
        }
        return trailing.isBlank() ? null : trailing;
    }

    private static String normalizeConnectionName(String connectionName) {
        String raw = blankToNull(connectionName);
        return raw == null ? "default" : raw.trim();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static String secretKeyFor(String connectionName) {
        return SECRET_PREFIX + connectionName.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    }

    private static String error(String message) {
        return "{\"status\":\"error\",\"message\":\""
                + (message == null ? "unknown error" : message.replace("\"", "'")) + "\"}";
    }

    public record ConnectionProfile(
            String connectionName,
            String connectionString,
            String host,
            Integer port,
            String database,
            String authDatabase,
            String username,
            String password
    ) {
        public ConnectionProfile {
            Objects.requireNonNull(connectionName, "connectionName");
            Objects.requireNonNull(database, "database");
        }
    }
}
