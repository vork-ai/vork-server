package sh.vork.storage;

import sh.vork.database.DatabaseRepository;
import sh.vork.database.DatabaseRepositoryFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link DatabaseRepository}{@code <StoredFile>} Spring bean.
 */
@Configuration
public class FileStorageConfig {

    @Bean
    public DatabaseRepository<StoredFile> storedFileRepository(DatabaseRepositoryFactory factory) {
        return factory.create(StoredFile.class);
    }
}
