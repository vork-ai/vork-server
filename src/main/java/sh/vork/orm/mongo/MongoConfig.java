package sh.vork.orm.mongo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Loads MongoDB connection settings from {@code conf.d/database.properties}
 * (relative to the working directory at startup) and exposes the necessary
 * Spring beans.
 *
 * <p>Active only when {@code db.backend=mongo}.
 */
@Configuration
@ConditionalOnProperty(name = "db.backend", havingValue = "mongo")
public class MongoConfig {

    @Value("${mongo.host:localhost}")
    private String host;

    @Value("${mongo.port:27017}")
    private int port;

    @Value("${mongo.database:vork}")
    private String databaseName;

    @Value("${mongo.username:}")
    private String username;

    @Value("${mongo.password:}")
    private String password;

    @Bean(destroyMethod = "close")
    public MongoClient mongoClient() {
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyToClusterSettings(cs ->
                        cs.hosts(List.of(new ServerAddress(host, port))));

        if (!username.isBlank() && !password.isBlank()) {
            MongoCredential credential = MongoCredential.createCredential(
                    username, databaseName, password.toCharArray());
            builder.credential(credential);
        }

        return MongoClients.create(builder.build());
    }

    @Bean
    public MongoDatabase mongoDatabase(MongoClient mongoClient) {
        return mongoClient.getDatabase(databaseName);
    }

    /**
     * Shared {@link ObjectMapper} configured with all available Jackson modules
     * (including native Java record support via the parameter-names module).
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
