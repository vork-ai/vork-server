package sh.vork.setup;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.ClusterOptions;
import org.dizitart.no2.Nitrite;
import org.dizitart.no2.mvstore.MVStoreModule;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Manages the database backend configuration written to
 * {@code conf.d/database.properties}.
 *
 * <p>This service is intentionally independent of any database connection —
 * it reads and writes the properties file directly, and can probe a candidate
 * configuration without touching the running Spring context.
 */
@Service
public class DatabaseSetupService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSetupService.class);
    private static final Path DB_PROPS = Path.of("conf.d/database.properties");

    // -------------------------------------------------------------------------
    // State queries
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code conf.d/database.properties} exists,
     * indicating that the setup wizard has already run the database step.
     */
    public boolean isDatabaseConfigured() {
        return Files.exists(DB_PROPS);
    }

    /**
     * Returns the currently persisted settings, or defaults targeting a local
     * embedded Nitrite instance when the file is absent or incomplete.
     */
    public DatabaseSettings getCurrentSettings() {
        log.debug("ENTER getCurrentSettings");
        Properties props = loadPropertiesFile();
        String backend = props.getProperty("db.backend", "nitrite");

        DatabaseSettings result = switch (backend) {
            case "couchbase" -> new DatabaseSettings(
                "couchbase",
                props.getProperty("couchbase.host", "localhost"),
                parsePort(props.getProperty("couchbase.port"), 8091),
                props.getProperty("couchbase.bucket", "vork"),
                props.getProperty("couchbase.username", "Administrator"),
                props.getProperty("couchbase.password", "password"));
            case "redis" -> new DatabaseSettings(
                    "redis",
                    props.getProperty("redis.host", "localhost"),
                    parsePort(props.getProperty("redis.port"), 6379),
                    null, null,
                    props.getProperty("redis.password", ""));
            case "nitrite" -> new DatabaseSettings(
                    "nitrite", null, 0,
                    props.getProperty("nitrite.file.path", "conf.d/vork.db"),
                    null, null);
            default -> new DatabaseSettings(
                    "mongo",
                    props.getProperty("mongo.host", "localhost"),
                    parsePort(props.getProperty("mongo.port"), 27017),
                    props.getProperty("mongo.database", "vork"),
                    props.getProperty("mongo.username", ""),
                    props.getProperty("mongo.password", ""));
        };
        log.debug("EXIT getCurrentSettings: backend={}", result.backend());
        return result;
    }

    /**
     * Returns the {@code db.backend} value from the properties file,
     * defaulting to {@code "nitrite"} when the file is absent.
     */
    public String getCurrentBackend() {
        return loadPropertiesFile().getProperty("db.backend", "nitrite");
    }

    // -------------------------------------------------------------------------
    // Connection test
    // -------------------------------------------------------------------------

    /**
     * Tests connectivity to the database described by {@code settings}.
     * Does not alter any state.
     */
    public TestResult testConnection(DatabaseSettings settings) {
        log.debug("ENTER testConnection: backend={}, host={}, port={}",
                settings.backend(), settings.host(), settings.port());
        TestResult result = switch (settings.backend()) {
            case "couchbase" -> testCouchbase(settings);
            case "redis"    -> testRedis(settings);
            case "nitrite"  -> testNitrite(settings);
            default         -> testMongo(settings);
        };
        log.debug("EXIT testConnection: ok={}", result.ok());
        return result;
    }

    private TestResult testNitrite(DatabaseSettings s) {
        String path = dbFilePath(s);
        try {
            java.nio.file.Path p = java.nio.file.Path.of(path);
            if (p.getParent() != null) java.nio.file.Files.createDirectories(p.getParent());
            Nitrite db = Nitrite.builder()
                    .loadModule(MVStoreModule.withConfig().filePath(path).build())
                    .openOrCreate();
            db.close();
            return TestResult.success();
        } catch (Exception e) {
            if (isActiveNitriteSelfLock(path, e)) {
                log.debug("Nitrite self-lock detected for active path {}; treating as success.", path);
                return TestResult.success();
            }
            log.debug("Nitrite connection test failed: {}", e.getMessage());
            return TestResult.failure("Cannot open Nitrite database: " + e.getMessage());
        }
    }

    private boolean isActiveNitriteSelfLock(String candidatePath, Exception ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        if (!message.contains("already opened in other process")) {
            return false;
        }

        String currentBackend = getCurrentBackend();
        if (!"nitrite".equalsIgnoreCase(currentBackend)) {
            return false;
        }

        String activePath = dbFilePath(getCurrentSettings());
        return java.nio.file.Path.of(activePath).normalize()
                .equals(java.nio.file.Path.of(candidatePath).normalize());
    }

    private static String dbFilePath(DatabaseSettings s) {
        return (s.database() != null && !s.database().isBlank()) ? s.database() : "conf.d/vork.db";
    }

    private TestResult testMongo(DatabaseSettings s) {
        try (MongoClient client = buildMongoClient(s)) {
            String db = (s.database() != null && !s.database().isBlank()) ? s.database() : "vork";
            client.getDatabase(db).runCommand(new Document("ping", 1));
            return TestResult.success();
        } catch (Exception e) {
            log.debug("MongoDB connection test failed: {}", e.getMessage());
            return TestResult.failure("Cannot connect to MongoDB: " + e.getMessage());
        }
    }

    private TestResult testRedis(DatabaseSettings s) {
        JedisPoolConfig cfg = new JedisPoolConfig();
        try {
            JedisPool pool = (s.password() != null && !s.password().isBlank())
                    ? new JedisPool(cfg, s.host(), s.port(), 2000, s.password())
                    : new JedisPool(cfg, s.host(), s.port(), 2000);
            try (pool; Jedis jedis = pool.getResource()) {
                jedis.ping();
            }
            return TestResult.success();
        } catch (Exception e) {
            log.debug("Redis connection test failed: {}", e.getMessage());
            return TestResult.failure("Cannot connect to Redis: " + e.getMessage());
        }
    }

    private TestResult testCouchbase(DatabaseSettings s) {
        String host = (s.host() == null || s.host().isBlank()) ? "localhost" : s.host();
        int port = s.port() > 0 ? s.port() : 8091;
        String bucketName = (s.database() == null || s.database().isBlank()) ? "vork" : s.database();
        if (s.username() == null || s.username().isBlank()) {
            return TestResult.failure("Couchbase username is required.");
        }

        String connStr = "couchbase://" + host + ":" + port;
        try (Cluster cluster = Cluster.connect(connStr,
                ClusterOptions.clusterOptions(s.username(), s.password() != null ? s.password() : ""))) {
            cluster.waitUntilReady(Duration.ofSeconds(5));
            Bucket bucket = cluster.bucket(bucketName);
            bucket.waitUntilReady(Duration.ofSeconds(5));
            return TestResult.success();
        } catch (Exception e) {
            log.debug("Couchbase connection test failed: {}", e.getMessage());
            return TestResult.failure("Cannot connect to Couchbase: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    /**
     * Writes the supplied settings to {@code conf.d/database.properties},
     * creating the directory if necessary.
     */
    public void saveConfig(DatabaseSettings settings) throws IOException {
        log.debug("ENTER saveConfig: backend={}", settings.backend());
        Files.createDirectories(DB_PROPS.getParent());

        Properties props = new Properties();
        props.setProperty("db.backend", settings.backend());

        if ("redis".equals(settings.backend())) {
            props.setProperty("redis.host", settings.host());
            props.setProperty("redis.port", String.valueOf(settings.port()));
            if (settings.password() != null && !settings.password().isBlank()) {
                props.setProperty("redis.password", settings.password());
            }
        } else if ("couchbase".equals(settings.backend())) {
            props.setProperty("couchbase.host",
                settings.host() != null && !settings.host().isBlank() ? settings.host() : "localhost");
            props.setProperty("couchbase.port", String.valueOf(settings.port() > 0 ? settings.port() : 8091));
            props.setProperty("couchbase.bucket",
                settings.database() != null && !settings.database().isBlank() ? settings.database() : "vork");
            props.setProperty("couchbase.username",
                settings.username() != null ? settings.username() : "");
            props.setProperty("couchbase.password",
                settings.password() != null ? settings.password() : "");
        } else if ("nitrite".equals(settings.backend())) {
            props.setProperty("nitrite.file.path", dbFilePath(settings));
        } else {
            props.setProperty("mongo.host", settings.host());
            props.setProperty("mongo.port", String.valueOf(settings.port()));
            props.setProperty("mongo.database",
                    settings.database() != null && !settings.database().isBlank()
                            ? settings.database() : "vork");
            if (settings.username() != null && !settings.username().isBlank()) {
                props.setProperty("mongo.username", settings.username());
                props.setProperty("mongo.password",
                        settings.password() != null ? settings.password() : "");
            }
        }

        try (OutputStream os = Files.newOutputStream(DB_PROPS)) {
            props.store(os, "Vork database configuration — managed by setup wizard");
        }
        log.info("Database configuration saved: backend={}, host={}, port={}",
                settings.backend(), settings.host(), settings.port());
    }

    // -------------------------------------------------------------------------
    // Restart
    // -------------------------------------------------------------------------

    /**
     * Schedules a JVM exit after 1.5 s so the HTTP response can be flushed.
     * The process manager (systemd, WinSW, Docker restart policy, etc.)
     * is expected to restart the application after it exits.
     */
    public void scheduleRestart() {
        log.info("Scheduling application restart to apply database backend change.");
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            log.info("Executing scheduled restart — System.exit(0)");
            System.exit(0);
        });
        t.setDaemon(true);
        t.setName("restart-scheduler");
        t.start();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Properties loadPropertiesFile() {
        Properties props = new Properties();
        if (Files.exists(DB_PROPS)) {
            try (InputStream is = Files.newInputStream(DB_PROPS)) {
                props.load(is);
            } catch (IOException e) {
                log.warn("Could not read {}: {}", DB_PROPS, e.getMessage());
            }
        }
        return props;
    }

    private static int parsePort(String value, int defaultPort) {
        if (value == null || value.isBlank()) return defaultPort;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultPort;
        }
    }

    private static MongoClient buildMongoClient(DatabaseSettings s) {
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyToClusterSettings(cs ->
                        cs.hosts(List.of(new ServerAddress(s.host(), s.port()))))
                .applyToSocketSettings(sc -> sc
                        .connectTimeout(3, TimeUnit.SECONDS)
                        .readTimeout(3, TimeUnit.SECONDS))
                .applyToConnectionPoolSettings(cp -> cp
                        .maxSize(1)
                        .maxWaitTime(3, TimeUnit.SECONDS));

        if (s.username() != null && !s.username().isBlank()) {
            MongoCredential cred = MongoCredential.createCredential(
                    s.username(),
                    s.database() != null ? s.database() : "admin",
                    s.password() != null ? s.password().toCharArray() : new char[0]);
            builder.credential(cred);
        }
        return MongoClients.create(builder.build());
    }

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    /** Outcome of a connection probe. */
    public record TestResult(boolean ok, String error) {
        static TestResult success() {
            return new TestResult(true, null);
        }
        static TestResult failure(String msg) {
            return new TestResult(false, msg);
        }
    }
}
