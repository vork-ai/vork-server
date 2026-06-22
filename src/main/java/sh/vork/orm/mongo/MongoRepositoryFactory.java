package sh.vork.orm.mongo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoDatabase;
import sh.vork.orm.DatabaseEntity;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Spring-managed factory for creating MongoDB-backed {@link DatabaseRepository} instances.
 *
 * <p>Declare a {@code @Bean} for each entity type in a {@code @Configuration} class:
 *
 * <pre>{@code
 * @Configuration
 * public class RepositoryConfig {
 *
 *     @Bean
 *     public DatabaseRepository<Product> productRepository(RepositoryFactory factory) {
 *         return factory.create(Product.class);
 *     }
 * }
 * }</pre>
 *
 * Spring will then satisfy {@code @Autowired DatabaseRepository<Product>} injections
 * automatically by matching the generic type parameter.
 */
@Component
@ConditionalOnProperty(name = "db.backend", havingValue = "mongo")
public class MongoRepositoryFactory implements RepositoryFactory {

    private final MongoDatabase mongoDatabase;
    private final ObjectMapper objectMapper;

    public MongoRepositoryFactory(MongoDatabase mongoDatabase, ObjectMapper objectMapper) {
        this.mongoDatabase = mongoDatabase;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a new {@link MongoDBRepository} for the given entity class.
     * The MongoDB collection name is derived from the class simple name
     * via {@code CamelCase → snake_case} conversion.
     */
    @Override
    public <T extends DatabaseEntity> DatabaseRepository<T> create(Class<T> entityClass) {
        return new MongoDBRepository<>(entityClass, mongoDatabase, objectMapper);
    }
}
