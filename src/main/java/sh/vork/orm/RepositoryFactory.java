package sh.vork.orm;

/**
 * Factory for creating typed {@link DatabaseRepository} instances.
 *
 * <p>Implementations are provided for specific backends:
 * <ul>
 *   <li>{@link sh.vork.orm.nitrite.NitriteRepositoryFactory} — Nitrite</li>
 *   <li>{@link sh.vork.orm.mongo.MongoRepositoryFactory} — MongoDB</li>
 *   <li>{@link sh.vork.orm.redis.RedisRepositoryFactory} — Redis</li>
 *   <li>{@link sh.vork.orm.couchbase.CouchbaseRepositoryFactory} — Couchbase</li>
 * </ul>
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
 */
public interface RepositoryFactory {

    /**
     * Creates a new {@link DatabaseRepository} for the given entity class.
     */
    <T extends DatabaseEntity> DatabaseRepository<T> create(Class<T> entityClass);
}
