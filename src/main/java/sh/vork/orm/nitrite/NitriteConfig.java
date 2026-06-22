package sh.vork.orm.nitrite;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dizitart.no2.Nitrite;
import org.dizitart.no2.mvstore.MVStoreModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spring configuration for the Nitrite embedded document-store backend.
 *
 * <p>Active only when {@code db.backend=nitrite} is set in
 * {@code conf.d/database.properties}.  The database file is created
 * automatically at the path specified by {@code nitrite.file.path}
 * (default: {@code conf.d/vork.db}).
 */
@Configuration
@ConditionalOnProperty(name = "db.backend", havingValue = "nitrite", matchIfMissing = true)
public class NitriteConfig {

    private static final Logger log = LoggerFactory.getLogger(NitriteConfig.class);

    @Value("${nitrite.file.path:conf.d/vork.db}")
    private String filePath;

    @Bean(destroyMethod = "close")
    public Nitrite nitriteDb() throws IOException {
        Path path = Path.of(filePath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        log.info("Opening Nitrite embedded database: {}", path.toAbsolutePath());
        return Nitrite.builder()
                .loadModule(MVStoreModule.withConfig()
                        .filePath(filePath)
                        .build())
                .openOrCreate();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
