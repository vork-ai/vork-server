package sh.vork.ai.config;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import sh.vork.ai.AiProvider;
import sh.vork.ai.function.CompileTypeRequest;
import sh.vork.ai.function.DeleteTypeInstanceRequest;
import sh.vork.ai.function.GetTypeSchemaRequest;
import sh.vork.ai.function.ListJavaTypesRequest;
import sh.vork.ai.function.ListTypeInstancesRequest;
import sh.vork.ai.function.SaveTypeInstanceRequest;
import sh.vork.database.DatabaseRepository;
import sh.vork.typegen.JavaType;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.TypeDatabaseService;
import sh.vork.typegen.TypeGenerationException;
import sh.vork.typegen.TypeGeneratorService;

/**
 * Wires all AI-related Spring beans.
 *
 * <h3>How the routing works</h3>
 * Each supported provider gets its own {@code @Bean ChatClient}. All clients
 * are collected into a single {@code Map<AiProvider, ChatClient>} registry bean.
 * {@code AiOrchestrationService} resolves the correct client at call-time by
 * looking up the caller-supplied {@link AiProvider} key.
 *
 * <h3>Adding a new provider</h3>
 * <ol>
 *   <li>Add the enum entry in {@link AiProvider}.</li>
 *   <li>Add a {@code @Bean ChatClient} here (inject the provider's auto-configured
 *       {@code ChatModel}).</li>
 *   <li>Add an entry in {@link #chatClientRegistry}.</li>
 * </ol>
 * No other class needs to change.
 */
@Configuration
public class AiConfig {

    private final JavaTypeClassLoader typeClassLoader;
    private final TypeDatabaseService typeDatabaseService;
    private final ObjectMapper objectMapper;

    public AiConfig(JavaTypeClassLoader typeClassLoader,
                    TypeDatabaseService typeDatabaseService,
                    ObjectMapper objectMapper) {
        this.typeClassLoader = typeClassLoader;
        this.typeDatabaseService = typeDatabaseService;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // ChatClient beans - one per active provider
    // -------------------------------------------------------------------------

    /**
     * Gemini ChatClient.
     *
     * <p>{@link ChatClient.Builder} is auto-configured by
     * {@code spring-ai-starter-model-google-genai} and already wraps the
     * Google GenAI {@code ChatModel}. We attach the weather tool as a default
     * so every prompt sent through this client can trigger it automatically.
     *
     * <p>When a second provider is added, inject its specific {@code ChatModel}
     * directly rather than relying on {@code ChatClient.Builder} auto-injection
     * to avoid ambiguity:
     * <pre>{@code
     * @Bean
     * public ChatClient openAiChatClient(OpenAiChatModel openAiModel,
     *                                    ToolCallback getCurrentWeather) {
     *     return ChatClient.builder(openAiModel)
     *             .defaultToolCallbacks(getCurrentWeather)
     *             .build();
     * }
     * }</pre>
     */
    @Bean
    public ChatClient geminiChatClient(ChatClient.Builder chatClientBuilder,
                                       List<ToolCallback> toolCallbacks) {
        return chatClientBuilder
                .defaultToolCallbacks(toolCallbacks.toArray(ToolCallback[]::new))
                .build();
    }

    // -------------------------------------------------------------------------
    // Provider registry
    // -------------------------------------------------------------------------

    /**
     * Central routing table: {@link AiProvider} to {@link ChatClient}.
     *
     * <p>This is the only place that needs to change when a new provider is added.
     */
    @Bean
    public Map<AiProvider, ChatClient> chatClientRegistry(
            @Qualifier("geminiChatClient") ChatClient geminiChatClient) {
        return Map.of(
                AiProvider.GEMINI, geminiChatClient
                // AiProvider.OPENAI, openAiChatClient,
                // AiProvider.ANTHROPIC, anthropicChatClient
        );
    }

    // -------------------------------------------------------------------------
    // Function-calling tools
    // -------------------------------------------------------------------------

    /**
     * {@code compileJavaType} tool — compiles a Java type from source code
     * supplied by the model, persists it to MongoDB, and loads it into the
     * running JVM so it is available for subsequent operations.
     *
     * <p>The tool returns a small JSON object:
     * <ul>
     *   <li>{@code {"status":"ok","class":"sh.vork.generated.Foo"}} on success.</li>
     *   <li>{@code {"status":"error","message":"..."}} on failure.</li>
     * </ul>
     */
    @Bean
    public ToolCallback compileJavaType(TypeGeneratorService typeGeneratorService) {
        return FunctionToolCallback
                .builder("compileJavaType", (CompileTypeRequest req) -> {
                    try {
                        Class<?> clazz = typeGeneratorService.compileAndSave(req.source());
                        return "{\"status\":\"ok\",\"class\":\"" + clazz.getName() + "\"}";
                    } catch (TypeGenerationException e) {
                        return "{\"status\":\"error\",\"message\":\"" +
                                e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description(
                        "Compile a Java type (record, class, interface, or enum) from source code and load " +
                        "it into the running application. The type is persisted to MongoDB and will be " +
                        "available after a restart. Returns the fully-qualified class name on success." + 
                        "Any record should implement sh.vork.database.DatabaseEntit." + 
                        "All types should use a sub-package of sh.vork.generated.")
                .inputType(CompileTypeRequest.class)
                .build();
    }

    /**
     * {@code listJavaTypes} tool — returns all custom Java types that have been
     * compiled and persisted to MongoDB via {@link #compileJavaType}.
     */
    @Bean
    public ToolCallback listJavaTypes(DatabaseRepository<JavaType> javaTypeRepository) {
        return FunctionToolCallback
                .builder("listJavaTypes", (ListJavaTypesRequest req) -> {
                    List<String> entries = new ArrayList<>();
                    try (var stream = javaTypeRepository.list(0, Integer.MAX_VALUE)) {
                        stream.forEach(jt -> entries.add(
                                "{\"fqn\":\"" + jt.uuid() + "\"," +
                                "\"classFiles\":" + jt.bytecode().size() + "," +
                                "\"createdAt\":\"" + new java.util.Date(jt.createdAt()) + "\"}"));
                    }
                    if (entries.isEmpty()) {
                        return "{\"types\":[]}";
                    }
                    return "{\"types\":[" + String.join(",", entries) + "]}";
                })
                .description(
                        "List all custom Java types that have been compiled and persisted to MongoDB. " +
                        "Returns each type's fully-qualified class name, number of class files " +
                        "(including inner classes), and the date it was first created.")
                .inputType(ListJavaTypesRequest.class)
                .build();
    }

    // -------------------------------------------------------------------------
    // TypeDatabase CRUD tools
    // -------------------------------------------------------------------------

    /**
     * {@code getTypeSchema} tool — returns a JSON schema derived from the record's
     * components, so the model knows exactly what fields and types to supply.
     */
    @Bean
    public ToolCallback getTypeSchema() {
        return FunctionToolCallback
                .builder("getTypeSchema", (GetTypeSchemaRequest req) -> {
                    try {
                        Class<?> clazz = typeClassLoader.loadClass(req.fqn());
                        return "{\"schema\":" + buildSchema(clazz) + "}";
                    } catch (ClassNotFoundException e) {
                        return "{\"status\":\"error\",\"message\":\"Type not found: " + req.fqn() + "\"}";
                    }
                })
                .description(
                        "Get the JSON schema for a custom Java type by its fully-qualified class name. " +
                        "Use listJavaTypes first to discover available types.")
                .inputType(GetTypeSchemaRequest.class)
                .build();
    }

    /**
     * {@code saveTypeInstance} tool — deserialises a JSON string into the named type
     * and persists it via {@link TypeDatabaseService}.
     */
    @Bean
    public ToolCallback saveTypeInstance() {
        return FunctionToolCallback
                .builder("saveTypeInstance", (SaveTypeInstanceRequest req) -> {
                    try {
                        Class<?> clazz = typeClassLoader.loadClass(req.fqn());
                        Object instance = objectMapper.readValue(req.json(), clazz);
                        typeDatabaseService.save(instance);
                        String uuid = (String) clazz.getMethod("uuid").invoke(instance);
                        return "{\"status\":\"ok\",\"uuid\":\"" + uuid + "\"}";
                    } catch (ClassNotFoundException e) {
                        return "{\"status\":\"error\",\"message\":\"Type not found: " + req.fqn() + "\"}";
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description(
                        "Save (create or update) an instance of a custom Java type. " +
                        "Provide the fully-qualified class name and a JSON string representing the instance. " +
                        "The JSON must include a uuid field — generate a random UUID v4 string for new instances. " +
                        "Use getTypeSchema to discover the required fields first.")
                .inputType(SaveTypeInstanceRequest.class)
                .build();
    }

    /**
     * {@code listTypeInstances} tool — returns all persisted instances of a custom type
     * as a JSON array.
     */
    @Bean
    public ToolCallback listTypeInstances() {
        return FunctionToolCallback
                .builder("listTypeInstances", (ListTypeInstancesRequest req) -> {
                    try {
                        Class<?> clazz = typeClassLoader.loadClass(req.fqn());
                        int page = req.page() != null ? req.page() : 0;
                        int pageSize = req.pageSize() != null && req.pageSize() > 0 ? req.pageSize() : 20;
                        List<Object> items = new ArrayList<>();
                        try (var stream = typeDatabaseService.list(clazz, page, pageSize)) {
                            stream.forEach(items::add);
                        }
                        return objectMapper.writeValueAsString(items);
                    } catch (ClassNotFoundException e) {
                        return "{\"status\":\"error\",\"message\":\"Type not found: " + req.fqn() + "\"}";
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description(
                        "List all stored instances of a custom Java type by its fully-qualified class name. " +
                        "Supports pagination via page (default 0) and pageSize (default 20).")
                .inputType(ListTypeInstancesRequest.class)
                .build();
    }

    /**
     * {@code deleteTypeInstance} tool — deletes a persisted instance by UUID.
     */
    @Bean
    public ToolCallback deleteTypeInstance() {
        return FunctionToolCallback
                .builder("deleteTypeInstance", (DeleteTypeInstanceRequest req) -> {
                    try {
                        Class<?> clazz = typeClassLoader.loadClass(req.fqn());
                        typeDatabaseService.delete(clazz, req.uuid());
                        return "{\"status\":\"ok\"}";
                    } catch (ClassNotFoundException e) {
                        return "{\"status\":\"error\",\"message\":\"Type not found: " + req.fqn() + "\"}";
                    }
                })
                .description(
                        "Delete a stored instance of a custom Java type by its UUID. " +
                        "Requires the fully-qualified class name and the instance UUID.")
                .inputType(DeleteTypeInstanceRequest.class)
                .build();
    }

    // -------------------------------------------------------------------------
    // JSON schema helpers
    // -------------------------------------------------------------------------

    private static String buildSchema(Class<?> clazz) {
        if (clazz == String.class) return "{\"type\":\"string\"}";
        if (clazz == int.class || clazz == Integer.class ||
                clazz == long.class || clazz == Long.class) return "{\"type\":\"integer\"}";
        if (clazz == double.class || clazz == Double.class ||
                clazz == float.class || clazz == Float.class ||
                clazz == java.math.BigDecimal.class) return "{\"type\":\"number\"}";
        if (clazz == boolean.class || clazz == Boolean.class) return "{\"type\":\"boolean\"}";
        if (clazz.isRecord()) {
            StringBuilder sb = new StringBuilder("{\"type\":\"object\",\"title\":\"")
                    .append(clazz.getSimpleName()).append("\",\"properties\":{");
            RecordComponent[] comps = clazz.getRecordComponents();
            for (int i = 0; i < comps.length; i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(comps[i].getName()).append("\":");
                sb.append(schemaForType(comps[i].getType(), comps[i].getGenericType()));
            }
            sb.append("}}");
            return sb.toString();
        }
        return "{\"type\":\"object\"}";
    }

    private static String schemaForType(Class<?> type, Type generic) {
        if (type == List.class || type == java.util.Collection.class) {
            String itemSchema = "{\"type\":\"object\"}";
            if (generic instanceof ParameterizedType pt) {
                Type arg = pt.getActualTypeArguments()[0];
                if (arg instanceof Class<?> c) itemSchema = buildSchema(c);
            }
            return "{\"type\":\"array\",\"items\":" + itemSchema + "}";
        }
        return buildSchema(type);
    }
}
