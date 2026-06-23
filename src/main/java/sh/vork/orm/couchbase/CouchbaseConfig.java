package sh.vork.orm.couchbase;

import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.ClusterOptions;
import com.couchbase.client.java.Collection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Loads Couchbase connection settings from {@code conf.d/database.properties}
 * and exposes the required Spring beans.
 *
 * <p>Active only when {@code db.backend=couchbase}.
 */
@Configuration
@ConditionalOnProperty(name = "db.backend", havingValue = "couchbase")
public class CouchbaseConfig {

    private static final Logger log = LoggerFactory.getLogger(CouchbaseConfig.class);

    @Value("${couchbase.host:localhost}")
    private String host;

    @Value("${couchbase.port:8091}")
    private int port;

    @Value("${couchbase.bucket:vork}")
    private String bucketName;

    @Value("${couchbase.scope:_default}")
    private String scopeName;

    @Value("${couchbase.collection:_default}")
    private String collectionName;

    @Value("${couchbase.username:Administrator}")
    private String username;

    @Value("${couchbase.password:password}")
    private String password;

    @Bean(destroyMethod = "disconnect")
    public Cluster couchbaseCluster() {
        String connStr = "couchbase://" + host + ":" + port;
        log.info("Connecting to Couchbase cluster: {}", connStr);
        Cluster cluster = Cluster.connect(connStr, ClusterOptions.clusterOptions(username, password));
        cluster.waitUntilReady(Duration.ofSeconds(10));
        return cluster;
    }

    @Bean
    public Bucket couchbaseBucket(Cluster couchbaseCluster) {
        Bucket bucket = couchbaseCluster.bucket(bucketName);
        bucket.waitUntilReady(Duration.ofSeconds(10));
        return bucket;
    }

    @Bean
    public Collection couchbaseCollection(Bucket couchbaseBucket) {
        return couchbaseBucket.scope(scopeName).collection(collectionName);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
