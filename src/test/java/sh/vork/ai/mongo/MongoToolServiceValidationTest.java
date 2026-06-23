package sh.vork.ai.mongo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import sh.vork.ai.function.CreateMongoDbConnectionRequest;
import sh.vork.ai.function.ListMongoDbCollectionsRequest;
import sh.vork.security.SecureCredentialStore;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MongoToolServiceValidationTest {

    private final SecureCredentialStore credentialStore = mock(SecureCredentialStore.class);
    private final MongoToolService service = new MongoToolService(credentialStore, new ObjectMapper());

    @Test
    void createConnection_requiresAuthenticatedUser() {
        String result = service.createConnection(null,
                new CreateMongoDbConnectionRequest(
                        "crm",
                        null,
                        "localhost",
                        27017,
                        "customers",
                        null,
                        null,
                    null,
                    null));

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Authenticated user is required"));
    }

    @Test
    void createConnection_requiresDatabaseWithoutConnectionString() {
        String result = service.createConnection("alice",
                new CreateMongoDbConnectionRequest(
                        "crm",
                        null,
                        "localhost",
                        27017,
                        null,
                        null,
                        null,
                    null,
                    null));

        assertTrue(result.contains("error"));
        assertTrue(result.contains("database is required"));
    }

    @Test
    void listCollections_failsWhenProfileNotFound() {
        String result = service.listCollections("alice", new ListMongoDbCollectionsRequest("crm"));

        assertTrue(result.contains("error"));
        assertTrue(result.contains("createMongoDBConnection"));
    }
}
