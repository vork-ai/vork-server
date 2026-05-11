package sh.vork.ai.config;

import sh.vork.ai.entity.AiSession;
import sh.vork.database.DatabaseRepository;
import sh.vork.database.DatabaseRepositoryFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiRepositoryConfig {

    @Bean
    public DatabaseRepository<AiSession> aiSessionRepository(DatabaseRepositoryFactory factory) {
        return factory.create(AiSession.class);
    }
}
