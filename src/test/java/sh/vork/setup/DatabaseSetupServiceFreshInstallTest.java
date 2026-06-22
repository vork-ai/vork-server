package sh.vork.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseSetupServiceFreshInstallTest {

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
    void freshInstall_withoutDatabaseProperties_isNotConfiguredAndDefaultsToNitrite() {
        DatabaseSetupService service = new DatabaseSetupService();

        assertFalse(service.isDatabaseConfigured(),
                "Fresh startup should report database as not configured when conf.d/database.properties is absent");
        assertEquals("nitrite", service.getCurrentBackend(),
                "Fresh startup default backend should be nitrite so setup wizard can choose an external backend later");
    }
}
