package sh.vork.ai.config;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.AiProvider;
import sh.vork.ai.function.CompileTypeRequest;
import sh.vork.ai.security.AuthorizationRuleEngine;
import sh.vork.ai.security.Restricted;
import sh.vork.ai.security.SecuredToolCallback;
import sh.vork.ai.security.VisualizableToolCallback;
import sh.vork.ai.function.DeleteTypeInstanceRequest;
import sh.vork.ai.function.GetTypeSchemaRequest;
import sh.vork.ai.function.GetURLContentsRequest;
import sh.vork.ai.function.ListEnumValuesRequest;
import sh.vork.ai.function.ListJavaTypesRequest;
import sh.vork.ai.function.ListTypeInstancesRequest;
import sh.vork.ai.function.LogInfoRequest;
import sh.vork.ai.function.SaveTypeInstanceRequest;
import sh.vork.ai.function.SearchTypeInstancesRequest;
import sh.vork.database.DatabaseRepository;
import sh.vork.database.SortOrder;
import sh.vork.typegen.JavaType;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.SqlParseException;
import sh.vork.typegen.TypeDatabaseService;
import sh.vork.typegen.TypeGenerationException;
import sh.vork.typegen.TypeGeneratorService;

/**
 * Wires all AI-related Spring beans.
 *
 * <h3>How the routing works</h3>
 * Each supported provider gets its own {@code @Bean ChatClient}. All clients
 * are collected into a single {@code Map<AiProvider, ChatClient>} registry
 * bean.
 * {@code AiOrchestrationService} resolves the correct client at call-time by
 * looking up the caller-supplied {@link AiProvider} key.
 *
 * <h3>Adding a new provider</h3>
 * <ol>
 * <li>Add the enum entry in {@link AiProvider}.</li>
 * <li>Add a {@code @Bean ChatClient} here (inject the provider's
 * auto-configured
 * {@code ChatModel}).</li>
 * <li>Add an entry in {@link #chatClientRegistry}.</li>
 * </ol>
 * No other class needs to change.
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

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
     * <p>
     * {@link ChatClient.Builder} is auto-configured by
     * {@code spring-ai-starter-model-google-genai} and already wraps the
     * Google GenAI {@code ChatModel}. We attach the weather tool as a default
     * so every prompt sent through this client can trigger it automatically.
     *
     * <p>
     * When a second provider is added, inject its specific {@code ChatModel}
     * directly rather than relying on {@code ChatClient.Builder} auto-injection
     * to avoid ambiguity:
     * 
     * <pre>{@code
     * @Bean
     * public ChatClient openAiChatClient(OpenAiChatModel openAiModel,
     *         ToolCallback getCurrentWeather) {
     *     return ChatClient.builder(openAiModel)
     *             .defaultToolCallbacks(getCurrentWeather)
     *             .build();
     * }
     * }</pre>
     */
    @Bean
    public ChatClient geminiChatClient(ChatClient.Builder chatClientBuilder,
            List<ToolCallback> toolCallbacks,
            AuthorizationRuleEngine authorizationRuleEngine,
            ConfigurableListableBeanFactory beanFactory) {
        List<ToolCallback> securedToolCallbacks = toolCallbacks.stream()
                .map(tool -> {
                    String toolName = tool.getToolDefinition().name();
                    if (isRestrictedTool(beanFactory, toolName)) {
                        return (ToolCallback) new SecuredToolCallback(tool, authorizationRuleEngine);
                    }
                    return tool;
                })
                .toList();

        return chatClientBuilder
                .defaultSystem(
                        """
        CRITICAL PROTOCOL (highest priority):
        1) When a tool is required, invoke the tool immediately in the same turn.
        2) Do not ask the user for confirmation.
        3) Do not emit preliminary status-only messages such as 'I will...' before invoking tools.

        Authorization text rule:
        - Provide concise supervisor-facing reasoning that can be shown in authorization review.
        - Keep it user-friendly plain language, avoid internal API/tool names, and explain why the action is needed.

        REASONING_HINT rule:
        - A tool description may include a line starting with 'REASONING_HINT:'.
        - This hint only affects wording of authorization reasoning text.
        - It MUST NOT change execution flow, MUST NOT create extra assistant turns, and MUST NOT override CRITICAL PROTOCOL.

        ENTITY RULE:
        - For any type implementing sh.vork.database.DatabaseEntity, uuid is always String (method signature: String uuid()).
        - Do not generate java.util.UUID as the record field type for uuid.
                                """
                                .stripIndent())
                .defaultToolCallbacks(securedToolCallbacks.toArray(ToolCallback[]::new))
                .build();
    }

    private static boolean isRestrictedTool(ConfigurableListableBeanFactory beanFactory, String toolName) {
        if (!beanFactory.containsBeanDefinition(toolName)) {
            return false;
        }
        BeanDefinition bd = beanFactory.getBeanDefinition(toolName);
        String factoryBeanName = bd.getFactoryBeanName();
        String factoryMethodName = bd.getFactoryMethodName();
        if (factoryBeanName == null || factoryMethodName == null) {
            return false;
        }
        try {
            Object factoryBean = beanFactory.getBean(factoryBeanName);
            Class<?> targetClass = ClassUtils.getUserClass(factoryBean);
            for (Method method : targetClass.getDeclaredMethods()) {
                if (method.getName().equals(factoryMethodName)
                        && method.isAnnotationPresent(Restricted.class)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Provider registry
    // -------------------------------------------------------------------------

    /**
     * Central routing table: {@link AiProvider} to {@link ChatClient}.
     *
     * <p>
     * This is the only place that needs to change when a new provider is added.
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
     * {@code getURLContents} tool — fetches text content from an HTTP/HTTPS URL.
     */
    @Bean
    public ToolCallback getURLContents() {
        return FunctionToolCallback
                .builder("getURLContents", (GetURLContentsRequest req) -> {
                    String rawUrl = req == null ? null : req.url();
                    if (rawUrl == null || rawUrl.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"url is required\"}";
                    }

                    try {
                        URI uri = URI.create(rawUrl.trim());
                        String scheme = uri.getScheme();
                        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                            return "{\"status\":\"error\",\"message\":\"Only http and https URLs are supported\"}";
                        }

                        HttpRequest request = HttpRequest.newBuilder(uri)
                                .GET()
                                .timeout(Duration.ofSeconds(15))
                                .header("User-Agent", "vork-ai-tool/1.0")
                                .build();

                        HttpResponse<String> response = HttpClient.newHttpClient()
                                .send(request, HttpResponse.BodyHandlers.ofString());

                        String content = response.body() == null ? "" : response.body();
                        if (content.length() > 20000) {
                            content = content.substring(0, 20000) + "\n...<truncated>";
                        }

                        return objectMapper.writeValueAsString(Map.of(
                                "status", "ok",
                                "url", uri.toString(),
                                "statusCode", response.statusCode(),
                                "content", content
                        ));
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description(
                        """
                        Fetch text content from an HTTP or HTTPS URL and return the response status and body content.
                        """
                                .stripIndent())
                .inputType(GetURLContentsRequest.class)
                .build();
    }

    /**
     * {@code logInfo} tool — writes a message to server logs at INFO level.
     */
    @Bean
    public ToolCallback logInfo() {
        return FunctionToolCallback
                .builder("logInfo", (LogInfoRequest req) -> {
                    String message = req == null ? null : req.message();
                    if (message == null || message.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"message is required\"}";
                    }
                    log.info("AI logInfo tool message: {}", message);
                    return "{\"status\":\"ok\"}";
                })
                .description(
                        """
                        Write a provided message to application logs at INFO level.
                        """
                                .stripIndent())
                .inputType(LogInfoRequest.class)
                .build();
    }

    /**
     * {@code compileJavaType} tool — compiles a Java type from source code
     * supplied by the model, persists it to MongoDB, and loads it into the
     * running JVM so it is available for subsequent operations.
     *
     * <p>
     * The tool returns a small JSON object:
     * <ul>
     * <li>{@code {"status":"ok","class":"sh.vork.generated.Foo"}} on success.</li>
     * <li>{@code {"status":"error","message":"..."}} on failure.</li>
     * </ul>
     */
    @Bean
    @Restricted
    public ToolCallback compileJavaType(TypeGeneratorService typeGeneratorService) {
        ToolCallback delegate = FunctionToolCallback
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
                        """
Compile a Java type (record, class, interface, or enum) from source code and load it into the running application. 
The type is persisted to MongoDB and will be available after a restart. 
Returns the fully-qualified class name on success. 
If a type implements sh.vork.database.DatabaseEntity, uuid must be String (String uuid(); and field/component type String), never java.util.UUID. 
Any record should implement sh.vork.database.DatabaseEntity. 
All types should use a sub-package of sh.vork.generated.
REASONING_HINT: Authorization is required to compile {{type_name}}.
                                """
                                .stripIndent())
                .inputType(CompileTypeRequest.class)
                .build();

        return new VisualizableToolCallback(delegate, argumentsJson -> {
            try {
                String sourceCode = objectMapper.readTree(argumentsJson)
                        .path("source")
                        .asText();
                if (sourceCode == null || sourceCode.isBlank()) {
                    return argumentsJson;
                }
                return "```java\n" + sourceCode + "\n```";
            } catch (Exception ex) {
                return argumentsJson;
            }
        });
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
                        """
                                List all custom Java types that have been compiled and persisted to MongoDB. Returns each type's fully-qualified class name, number of class files (including inner classes), and the date it was first created.
                                """
                                .stripIndent())
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
                        """
                                Get the JSON schema for a custom Java type by its fully-qualified class name. Use listJavaTypes first to discover available types.
                                """
                                .stripIndent())
                .inputType(GetTypeSchemaRequest.class)
                .build();
    }

    /**
     * {@code saveTypeInstance} tool — deserialises a JSON string into the named
     * type
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
                        """
                                Save (create or update) an instance of a custom Java type. Provide the fully-qualified class name and a JSON string representing the instance. The JSON must include a uuid field — generate a random UUID v4 string for new instances. Use getTypeSchema to discover the required fields first.
                                """
                                .stripIndent())
                .inputType(SaveTypeInstanceRequest.class)
                .build();
    }

    /**
     * {@code listTypeInstances} tool — returns all persisted instances of a custom
     * type
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
                        """
                                List all stored instances of a custom Java type by its fully-qualified class name. Supports pagination via page (default 0) and pageSize (default 20).
                                """
                                .stripIndent())
                .inputType(ListTypeInstancesRequest.class)
                .build();
    }

    /**
     * {@code listEnumValues} tool — returns all declared constants of an enum
     * class resolved via {@link JavaTypeClassLoader}.
     */
    @Bean
    public ToolCallback listEnumValues() {
        return FunctionToolCallback
                .builder("listEnumValues", (ListEnumValuesRequest req) -> {
                    try {
                        Class<?> clazz = typeClassLoader.loadClass(req.fqn());
                        if (!clazz.isEnum()) {
                            return "{\"status\":\"error\",\"message\":\"" + req.fqn() + " is not an enum\"}";
                        }
                        Object[] constants = clazz.getEnumConstants();
                        StringBuilder sb = new StringBuilder("{\"fqn\":\"");
                        sb.append(req.fqn()).append("\",\"values\":[");
                        for (int i = 0; i < constants.length; i++) {
                            if (i > 0)
                                sb.append(',');
                            sb.append('\"').append(constants[i].toString()).append('\"');
                        }
                        sb.append("]}");
                        return sb.toString();
                    } catch (ClassNotFoundException e) {
                        return "{\"status\":\"error\",\"message\":\"Type not found: " + req.fqn() + "\"}";
                    }
                })
                .description(
                        """
                                List all declared constants of an enum by its fully-qualified class name. Use listJavaTypes to discover available types first.
                                """
                                .stripIndent())
                .inputType(ListEnumValuesRequest.class)
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
                        """
                                Delete a stored instance of a custom Java type by its UUID. Requires the fully-qualified class name and the instance UUID.
                                """
                                .stripIndent())
                .inputType(DeleteTypeInstanceRequest.class)
                .build();
    }

    /**
     * {@code searchTypeInstances} tool — searches stored instances of a custom Java
     * type using either a SQL-like WHERE clause or a raw MongoDB filter JSON.
     */
    @Bean
    public ToolCallback searchTypeInstances() {
        return FunctionToolCallback
                .builder("searchTypeInstances", (SearchTypeInstancesRequest req) -> {
                    try {
                        Class<?> clazz = typeClassLoader.loadClass(req.fqn());
                        int page = req.page() != null ? req.page() : 0;
                        int pageSize = req.pageSize() != null && req.pageSize() > 0
                                ? req.pageSize()
                                : 20;
                        String sortField = req.sortField() != null ? req.sortField() : "uuid";
                        SortOrder order = "DESC".equalsIgnoreCase(req.sortOrder())
                                ? SortOrder.DESC
                                : SortOrder.ASC;
                        String queryType = req.queryType() != null
                                ? req.queryType().toUpperCase()
                                : "SQL";

                        List<Object> items = new ArrayList<>();
                        long total;

                        if ("MONGO".equals(queryType)) {
                            try (var stream = typeDatabaseService.searchByMongoFilter(
                                    clazz, req.query(), page, pageSize, sortField, order)) {
                                stream.forEach(items::add);
                            }
                            total = typeDatabaseService.searchCountByMongoFilter(clazz, req.query());
                        } else {
                            try (var stream = typeDatabaseService.searchBySql(
                                    clazz, req.query(), page, pageSize, sortField, order)) {
                                stream.forEach(items::add);
                            }
                            total = typeDatabaseService.searchCountBySql(clazz, req.query());
                        }

                        String resultsJson = objectMapper.writeValueAsString(items);
                        return "{\"total\":" + total + ",\"page\":" + page
                                + ",\"pageSize\":" + pageSize + ",\"results\":" + resultsJson + "}";
                    } catch (ClassNotFoundException e) {
                        return "{\"status\":\"error\",\"message\":\"Type not found: " + req.fqn() + "\"}";
                    } catch (SqlParseException e) {
                        return "{\"status\":\"error\",\"message\":\"SQL parse error: "
                                + e.getMessage().replace("\"", "'") + "\"}";
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\""
                                + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description(
                        """
                                Search instances of a custom Java type using either a SQL-like WHERE clause (queryType: SQL, default) or a raw MongoDB JSON filter (queryType: MONGO). SQL examples: "name = 'Alice'", "age > 18 AND active = true", "address.city = 'London'", "status IN ('active', 'pending')", "name LIKE '%ali%'". MONGO example: {"status":"active","age":{"$gt":18}}. Supports pagination (page, pageSize) and sorting (sortField, sortOrder).
                                """
                                .stripIndent())
                .inputType(SearchTypeInstancesRequest.class)
                .build();
    }

    // -------------------------------------------------------------------------
    // JSON schema helpers
    // -------------------------------------------------------------------------

    private static String buildSchema(Class<?> clazz) {
        if (clazz == String.class)
            return "{\"type\":\"string\"}";
        if (clazz == int.class || clazz == Integer.class ||
                clazz == long.class || clazz == Long.class)
            return "{\"type\":\"integer\"}";
        if (clazz == double.class || clazz == Double.class ||
                clazz == float.class || clazz == Float.class ||
                clazz == java.math.BigDecimal.class)
            return "{\"type\":\"number\"}";
        if (clazz == boolean.class || clazz == Boolean.class)
            return "{\"type\":\"boolean\"}";
        if (clazz.isRecord()) {
            StringBuilder sb = new StringBuilder("{\"type\":\"object\",\"title\":\"")
                    .append(clazz.getSimpleName()).append("\",\"properties\":{");
            RecordComponent[] comps = clazz.getRecordComponents();
            for (int i = 0; i < comps.length; i++) {
                if (i > 0)
                    sb.append(',');
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
                if (arg instanceof Class<?> c)
                    itemSchema = buildSchema(c);
            }
            return "{\"type\":\"array\",\"items\":" + itemSchema + "}";
        }
        return buildSchema(type);
    }
}
