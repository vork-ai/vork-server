package sh.vork.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseSetupServiceCouchbaseSettingsTest {

    private static final Path DB_PROPS = Path.of("conf.d/database.properties");

    private Path backupPath;

    @BeforeEach
    void moveExistingConfigOutOfTheWay() throws IOException {
        if (Files.exists(DB_PROPS)) {
            backupPath = Files.createTempFile("database-properties-backup", ".properties");
            Files.move(DB_PROPS, backupPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @AfterEach
    void restoreConfig() throws IOException {
        Files.deleteIfExists(DB_PROPS);
        if (backupPath != null && Files.exists(backupPath)) {
            if (DB_PROPS.getParent() != null) {
                Files.createDirectories(DB_PROPS.getParent());
            }
            Files.move(backupPath, DB_PROPS, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test
    void getCurrentSettings_readsCouchbaseConfiguration() throws IOException {
        if (DB_PROPS.getParent() != null) {
            Files.createDirectories(DB_PROPS.getParent());
        }

        Properties props = new Properties();
        props.setProperty("db.backend", "couchbase");
        props.setProperty("couchbase.host", "cb.local");
        props.setProperty("couchbase.port", "18091");
        props.setProperty("couchbase.bucket", "vorkdata");
        props.setProperty("couchbase.username", "admin");
        props.setProperty("couchbase.password", "secret");
        try (java.io.OutputStream os = Files.newOutputStream(DB_PROPS)) {
            props.store(os, "test");
        }

        DatabaseSetupService service = new DatabaseSetupService();
        DatabaseSettings settings = service.getCurrentSettings();

        assertEquals("couchbase", settings.backend());
        assertEquals("cb.local", settings.host());
        assertEquals(18091, settings.port());
        assertEquals("vorkdata", settings.database());
        assertEquals("admin", settings.username());
        assertEquals("secret", settings.password());
    }

    @Test
    void saveConfig_writesCouchbaseProperties() throws IOException {
        DatabaseSetupService service = new DatabaseSetupService();
        DatabaseSettings settings = new DatabaseSettings(
                "couchbase",
                "localhost",
                8091,
                "vork",
                "Administrator",
                "password"
        );

        service.saveConfig(settings);

        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(DB_PROPS)) {
            props.load(is);
        }

        assertEquals("couchbase", props.getProperty("db.backend"));
        assertEquals("localhost", props.getProperty("couchbase.host"));
        assertEquals("8091", props.getProperty("couchbase.port"));
        assertEquals("vork", props.getProperty("couchbase.bucket"));
        assertEquals("Administrator", props.getProperty("couchbase.username"));
        assertEquals("password", props.getProperty("couchbase.password"));
        assertTrue(Files.exists(DB_PROPS));
    }
}
