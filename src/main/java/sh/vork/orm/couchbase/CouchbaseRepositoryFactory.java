package sh.vork.orm.couchbase;

import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import sh.vork.orm.DatabaseEntity;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;

/**
 * Spring-managed factory for creating Couchbase-backed repositories.
 */
@Component
@ConditionalOnProperty(name = "db.backend", havingValue = "couchbase")
public class CouchbaseRepositoryFactory implements RepositoryFactory {

    private final Cluster cluster;
    private final Collection collection;
    private final ObjectMapper objectMapper;
    private final String bucketName;
    private final String scopeName;
    private final String collectionName;

    public CouchbaseRepositoryFactory(
            Cluster cluster,
            Collection collection,
            ObjectMapper objectMapper,
            @Value("${couchbase.bucket:vork}") String bucketName,
            @Value("${couchbase.scope:_default}") String scopeName,
            @Value("${couchbase.collection:_default}") String collectionName) {
        this.cluster = cluster;
        this.collection = collection;
        this.objectMapper = objectMapper;
        this.bucketName = bucketName;
        this.scopeName = scopeName;
        this.collectionName = collectionName;
    }

    @Override
    public <T extends DatabaseEntity> DatabaseRepository<T> create(Class<T> entityClass) {
        return new CouchbaseRepository<>(
                entityClass,
                cluster,
                collection,
                objectMapper,
                bucketName,
                scopeName,
                collectionName);
    }
}
