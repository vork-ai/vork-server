package sh.vork.typegen;

import sh.vork.database.DatabaseRepository;
import sh.vork.database.DatabaseRepositoryFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TypeGeneratorConfig {

    @Bean
    public DatabaseRepository<JavaType> javaTypeRepository(DatabaseRepositoryFactory factory) {
        return factory.create(JavaType.class);
    }
}
