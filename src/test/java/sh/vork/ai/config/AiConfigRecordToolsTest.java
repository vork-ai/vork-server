package sh.vork.ai.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.orm.DatabaseEntity;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.TypeDatabaseService;

class AiConfigRecordToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void getTypeInstance_returnsRecordJsonWhenFound() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);

        doReturn(CatRecord.class).when(classLoader).loadClass("sh.vork.generated.Cat");
        when(typeDatabaseService.get(CatRecord.class, "cat-1")).thenReturn(new CatRecord("cat-1", "Milo"));

        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);
        ToolCallback tool = config.getTypeInstance();

        String args = "{\"fqn\":\"sh.vork.generated.Cat\",\"uuid\":\"cat-1\"}";
        String output = tool.call(args);

        var map = objectMapper.readValue(output, new TypeReference<java.util.Map<String, Object>>() {});
        assertEquals("cat-1", map.get("uuid"));
        assertEquals("Milo", map.get("name"));
    }

    @Test
    void countTypeInstances_supportsUnfilteredAndSqlFilteredCounts() {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);

        try {
            doReturn(CatRecord.class).when(classLoader).loadClass("sh.vork.generated.Cat");
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
        when(typeDatabaseService.count(CatRecord.class)).thenReturn(7L);
        when(typeDatabaseService.searchCountBySql(CatRecord.class, "name LIKE '%mi%'"))
                .thenReturn(2L);

        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);
        ToolCallback tool = config.countTypeInstances();

        String noFilterOutput = tool.call("{\"fqn\":\"sh.vork.generated.Cat\"}");
        assertTrue(noFilterOutput.contains("\"count\":7"));

        String sqlFilterOutput = tool.call("{\"fqn\":\"sh.vork.generated.Cat\",\"query\":\"name LIKE '%mi%'\",\"queryType\":\"SQL\"}");
        assertTrue(sqlFilterOutput.contains("\"count\":2"));
    }

    @Test
    void getDateTime_returnsLocalDateTimeFields() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);

        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);
        ToolCallback tool = config.getDateTime();

        String output = tool.call("{}");

        var map = objectMapper.readValue(output, new TypeReference<java.util.Map<String, Object>>() {});
        assertEquals("ok", map.get("status"));
        assertNotNull(map.get("isoDateTime"));
        assertNotNull(map.get("localDate"));
        assertNotNull(map.get("localTime"));
        assertNotNull(map.get("zoneId"));

        String isoDateTime = String.valueOf(map.get("isoDateTime"));
        java.time.ZonedDateTime parsed = java.time.ZonedDateTime.parse(isoDateTime);
        assertNotNull(parsed);
    }

    private record CatRecord(String uuid, String name) implements DatabaseEntity {}
}
