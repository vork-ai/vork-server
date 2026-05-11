package sh.vork.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoDatabase;
import sh.vork.database.mongo.MongoDBRepository;
import org.springframework.stereotype.Component;

/**
 * Spring-managed factory for creating typed {@link DatabaseRepository} instances.
 *
 * <p>Declare a {@code @Bean} for each entity type in a {@code @Configuration} class:
 *
 * <pre>{@code
 * @Configuration
 * public class RepositoryConfig {
 *
 *     @Bean
 *     public DatabaseRepository<Product> productRepository(DatabaseRepositoryFactory factory) {
 *         return factory.create(Product.class);
 *     }
 * }
 * }</pre>
 *
 * Spring will then satisfy {@code @Autowired DatabaseRepository<Product>} injections
 * automatically by matching the generic type parameter.
 */
@Component
public class DatabaseRepositoryFactory {

    private final MongoDatabase mongoDatabase;
    private final ObjectMapper objectMapper;

    public DatabaseRepositoryFactory(MongoDatabase mongoDatabase, ObjectMapper objectMapper) {
        this.mongoDatabase = mongoDatabase;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a new {@link MongoDBRepository} for the given entity class.
     * The MongoDB collection name is derived from the class simple name
     * via {@code CamelCase → snake_case} conversion.
     */
    public <T extends DatabaseEntity> DatabaseRepository<T> create(Class<T> entityClass) {
        return new MongoDBRepository<>(entityClass, mongoDatabase, objectMapper);
    }
}
