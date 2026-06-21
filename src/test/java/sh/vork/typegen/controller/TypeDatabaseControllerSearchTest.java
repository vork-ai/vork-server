package sh.vork.typegen.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.orm.DatabaseRepository;
import sh.vork.typegen.FormToObjectConverter;
import sh.vork.typegen.JavaType;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.SqlParseException;
import sh.vork.typegen.TypeDatabaseService;

class TypeDatabaseControllerSearchTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void search_sqlQuery_returnsPagedResultsAndTotal() throws Exception {
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
        FormToObjectConverter formConverter = mock(FormToObjectConverter.class);
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<JavaType> javaTypeRepository = (DatabaseRepository<JavaType>) mock(DatabaseRepository.class);

        doReturn(DummyRecord.class).when(classLoader).loadClass("sh.vork.generated.Cat");
        when(typeDatabaseService.searchBySql(eq(DummyRecord.class), eq("name LIKE '%cat%'"), eq(0), eq(20), eq("uuid"), any()))
                .thenReturn(Stream.of(new DummyRecord("c-1", "Milo")));
        when(typeDatabaseService.searchCountBySql(DummyRecord.class, "name LIKE '%cat%'")).thenReturn(1L);

        TypeDatabaseController controller = new TypeDatabaseController(
                typeDatabaseService,
                formConverter,
                classLoader,
                objectMapper,
                javaTypeRepository);

        ResponseEntity<String> response = controller.search(
                "sh.vork.generated.Cat",
                "name LIKE '%cat%'",
                "SQL",
                "uuid",
                "ASC",
                0,
                20);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> payload = objectMapper.readValue(response.getBody(), new TypeReference<>() {});
        assertEquals(1, payload.get("total"));
        assertEquals(0, payload.get("page"));
        assertEquals(20, payload.get("pageSize"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) payload.get("results");
        assertEquals(1, results.size());
        assertEquals("c-1", results.get(0).get("uuid"));
        assertEquals("Milo", results.get(0).get("name"));
    }

    @Test
    void search_sqlParseError_returnsBadRequest() {
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
        FormToObjectConverter formConverter = mock(FormToObjectConverter.class);
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<JavaType> javaTypeRepository = (DatabaseRepository<JavaType>) mock(DatabaseRepository.class);

        try {
                        doReturn(DummyRecord.class).when(classLoader).loadClass("sh.vork.generated.Cat");
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
        when(typeDatabaseService.searchBySql(eq(DummyRecord.class), eq("name ="), eq(0), eq(20), eq("uuid"), any()))
                .thenThrow(new SqlParseException("Unexpected end of input"));

        TypeDatabaseController controller = new TypeDatabaseController(
                typeDatabaseService,
                formConverter,
                classLoader,
                objectMapper,
                javaTypeRepository);

        ResponseEntity<String> response = controller.search(
                "sh.vork.generated.Cat",
                "name =",
                "SQL",
                "uuid",
                "ASC",
                0,
                20);

        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().contains("SQL parse error"));
    }

    private record DummyRecord(String uuid, String name) {}
}
