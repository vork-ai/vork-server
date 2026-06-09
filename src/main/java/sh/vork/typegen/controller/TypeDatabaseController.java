package sh.vork.typegen.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jadaptive.orm.DatabaseRepository;
import sh.vork.typegen.DisplayField;
import sh.vork.typegen.FormConversionException;
import sh.vork.typegen.FormToObjectConverter;
import sh.vork.typegen.JavaType;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.TypeDatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * REST controller that exposes CRUD operations over any persisted Java record
 * type, identified by its fully-qualified class name.
 *
 * <h3>URL patterns</h3>
 * <pre>
 *   GET    /api/types/{fqn}/list?page=0&pageSize=20    – paginated list (JSON)
 *   GET    /api/types/{fqn}/count                      – document count (JSON)
 *   GET    /api/types/{fqn}/{uuid}                     – single entity (JSON)
 *   POST   /api/types/{fqn}                            – save (multipart form)
 *   DELETE /api/types/{fqn}/{uuid}                     – delete
 * </pre>
 *
 * The {@code fqn} path segment uses {@code .} as a separator, e.g.
 * {@code sh.vork.generated.Product}.
 *
 * <h3>Form encoding</h3>
 * Save accepts {@code multipart/form-data} (or {@code application/x-www-form-urlencoded}).
 * {@link FormToObjectConverter} maps the request parameters to record fields using
 * dot-notation for nested records and {@code field[n]} for list elements.
 */
@RestController
@RequestMapping("/api/types")
public class TypeDatabaseController {

    private static final Logger log = LoggerFactory.getLogger(TypeDatabaseController.class);

    private final TypeDatabaseService typeDatabaseService;
    private final FormToObjectConverter formConverter;
    private final JavaTypeClassLoader classLoader;
    private final ObjectMapper objectMapper;
    private final DatabaseRepository<JavaType> javaTypeRepository;

    public TypeDatabaseController(TypeDatabaseService typeDatabaseService,
                                  FormToObjectConverter formConverter,
                                  JavaTypeClassLoader classLoader,
                                  ObjectMapper objectMapper,
                                  DatabaseRepository<JavaType> javaTypeRepository) {
        this.typeDatabaseService   = typeDatabaseService;
        this.formConverter         = formConverter;
        this.classLoader           = classLoader;
        this.objectMapper          = objectMapper;
        this.javaTypeRepository    = javaTypeRepository;
    }

    // -------------------------------------------------------------------------
    // Schema — field metadata for the Data Inspector UI
    // -------------------------------------------------------------------------

    /**
     * Returns an enriched field-descriptor schema for {@code fqn}.
     *
     * <p>If any record component carries {@link DisplayField} annotations those
     * values are used directly. Otherwise every top-level component whose Java
     * type is a {@code String}, primitive, or numeric wrapper is automatically
     * marked as a table column.
     */
    @GetMapping(value = "/{fqn}/schema", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> schema(@PathVariable String fqn) {
        Class<?> entityClass = resolveClass(fqn);
        if (entityClass == null) {
            return notFound("Type not found: " + fqn);
        }
        if (!entityClass.isRecord()) {
            return error("Type is not a record: " + fqn);
        }
        try {
            Map<String, Object> schema = buildInspectorSchema(entityClass);
            return ResponseEntity.ok(objectMapper.writeValueAsString(schema));
        } catch (Exception e) {
            return error("Schema generation failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Java types listing — for the Data Inspector type selector
    // -------------------------------------------------------------------------

    /**
     * Returns a summary of every custom Java type compiled and persisted to
     * MongoDB that is a top-level entity (i.e. implements
     * {@link com.jadaptive.orm.DatabaseEntity}).
     *
     * <p>Embedded value-object types (e.g. {@code Address}, {@code LineItem})
     * intentionally do <em>not</em> implement {@code DatabaseEntity} and are
     * therefore excluded from this listing and from the Data Inspector dropdown.
     */
    @GetMapping(value = "/java-types", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> javaTypes() {
        List<Map<String, Object>> entries = new ArrayList<>();
        try (Stream<JavaType> stream = javaTypeRepository.list(0, Integer.MAX_VALUE)) {
            stream.forEach(jt -> {
                // Only expose top-level entities; embedded records do not implement DatabaseEntity.
                Class<?> clazz;
                try {
                    clazz = classLoader.loadClass(jt.uuid());
                } catch (ClassNotFoundException e) {
                    log.debug("Skipping type not yet loaded in classloader: {}", jt.uuid());
                    return;
                }
                if (!com.jadaptive.orm.DatabaseEntity.class.isAssignableFrom(clazz)) {
                    return;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("fqn", jt.uuid());
                String simpleName = jt.uuid().contains(".")
                        ? jt.uuid().substring(jt.uuid().lastIndexOf('.') + 1)
                        : jt.uuid();
                entry.put("simpleName", simpleName);
                entry.put("createdAt", jt.createdAt());
                entries.add(entry);
            });
        }
        try {
            return ResponseEntity.ok(objectMapper.writeValueAsString(entries));
        } catch (Exception e) {
            return error("Serialisation failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Schema builder
    // -------------------------------------------------------------------------

    private Map<String, Object> buildInspectorSchema(Class<?> clazz) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("fqn", clazz.getName());
        schema.put("title", clazz.getSimpleName());

        RecordComponent[] components = clazz.getRecordComponents();
        boolean hasAnyAnnotation = Arrays.stream(components)
                .anyMatch(c -> c.getAnnotation(DisplayField.class) != null);

        List<Map<String, Object>> fields = new ArrayList<>();
        for (int i = 0; i < components.length; i++) {
            RecordComponent comp = components[i];
            fields.add(buildFieldDescriptor(comp, i, hasAnyAnnotation));
        }

        // Sort by declared order (annotation value first, then declaration index)
        fields.sort(Comparator.comparingInt(f -> (Integer) f.get("order")));
        schema.put("fields", fields);
        return schema;
    }

    private Map<String, Object> buildFieldDescriptor(RecordComponent comp,
                                                      int declarationIndex,
                                                      boolean typeHasAnnotations) {
        Map<String, Object> fd = new LinkedHashMap<>();
        fd.put("name", comp.getName());

        DisplayField ann = comp.getAnnotation(DisplayField.class);

        // Label — annotation value or camelCase → Title Case fallback
        String label = (ann != null && !ann.label().isBlank()) ? ann.label() : camelToTitle(comp.getName());
        fd.put("label", label);

        // Order
        int order = (ann != null && ann.order() != Integer.MAX_VALUE) ? ann.order() : declarationIndex;
        fd.put("order", order);

        Class<?> type    = comp.getType();
        Type     generic = comp.getGenericType();

        if (type == List.class || type == java.util.Collection.class) {
            fd.put("type", "array");
            Class<?> itemClass = resolveListItemType(generic);
            if (itemClass != null && itemClass.isRecord()) {
                fd.put("itemType", "record");
                fd.put("itemSchema", buildInspectorSchema(itemClass));
            } else {
                fd.put("itemType", itemClass != null ? simpleTypeName(itemClass) : "string");
            }
            fd.put("tableColumn", ann != null ? ann.tableColumn() : false);
            fd.put("inputType", ann != null ? ann.inputType() : "auto");
        } else if (type.isRecord()) {
            fd.put("type", "record");
            fd.put("title", type.getSimpleName());
            fd.put("fields", buildInspectorSchema(type).get("fields"));
            fd.put("tableColumn", ann != null ? ann.tableColumn() : false);
            fd.put("inputType", "auto");
        } else if (type.isEnum()) {
            fd.put("type", "enum");
            boolean defaultColumn = !typeHasAnnotations;
            fd.put("tableColumn", ann != null ? ann.tableColumn() : defaultColumn);
            fd.put("inputType", "select");
            fd.put("options", buildEnumOptions(type));
        } else {
            fd.put("type", simpleTypeName(type));
            // tableColumn: if the type has @DisplayField annotations use the annotation;
            // otherwise fall back to showing all primitive/String/numeric fields
            boolean defaultColumn = !typeHasAnnotations && isSimpleColumnType(type);
            fd.put("tableColumn", ann != null ? ann.tableColumn() : defaultColumn);
            String inputType = (ann != null && !"auto".equals(ann.inputType()))
                    ? ann.inputType()
                    : inferInputType(type);
            fd.put("inputType", inputType);
        }

        if (ann != null && !ann.placeholder().isBlank()) fd.put("placeholder", ann.placeholder());
        if (ann != null && ann.required())               fd.put("required", true);

        return fd;
    }

    /** Builds a list of {value, label} option maps for an enum type.
     *  Probes for {@code getDisplayName()}, {@code getLabel()}, or {@code getDescription()}
     *  to obtain a human-readable label; falls back to {@link Enum#name()}.
     */
    @SuppressWarnings({"rawtypes"})
    private static List<Map<String, String>> buildEnumOptions(Class<?> enumType) {
        java.lang.reflect.Method displayMethod = null;
        for (String methodName : new String[]{"getDisplayName", "getLabel", "getDescription"}) {
            try { displayMethod = enumType.getMethod(methodName); break; }
            catch (NoSuchMethodException ignored) {}
        }
        final java.lang.reflect.Method dm = displayMethod;
        List<Map<String, String>> options = new ArrayList<>();
        for (Object c : enumType.getEnumConstants()) {
            Map<String, String> opt = new LinkedHashMap<>();
            opt.put("value", ((Enum) c).name());
            String lbl = ((Enum) c).name();
            if (dm != null) {
                try { lbl = String.valueOf(dm.invoke(c)); } catch (Exception ignored) {}
            }
            opt.put("label", lbl);
            options.add(opt);
        }
        return options;
    }

    /** Resolves the element type of a {@code List<T>} generic type. */
    private static Class<?> resolveListItemType(Type generic) {
        if (generic instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length == 1 && args[0] instanceof Class<?> c) return c;
        }
        return null;
    }

    /** Infers a sensible HTML input type from a Java type. */
    private static String inferInputType(Class<?> type) {
        if (type == boolean.class || type == Boolean.class)               return "checkbox";
        if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class)              return "number";
        if (type == double.class || type == Double.class
                || type == float.class || type == Float.class
                || type == java.math.BigDecimal.class)                     return "number";
        return "text";
    }

    /** Returns {@code true} for types that are safe to show as table columns by default. */
    private static boolean isSimpleColumnType(Class<?> type) {
        return type == String.class
                || type == int.class    || type == Integer.class
                || type == long.class   || type == Long.class
                || type == double.class || type == Double.class
                || type == float.class  || type == Float.class
                || type == boolean.class || type == Boolean.class
                || type == java.math.BigDecimal.class;
    }

    /** Returns a schema type name string for a Java type. */
    private static String simpleTypeName(Class<?> type) {
        if (type == String.class)                                          return "string";
        if (type == boolean.class || type == Boolean.class)               return "boolean";
        if (type == int.class     || type == Integer.class
                || type == long.class   || type == Long.class)            return "integer";
        if (type == double.class  || type == Double.class
                || type == float.class  || type == Float.class
                || type == java.math.BigDecimal.class)                     return "number";
        return "string";
    }

    /** Converts {@code someFieldName} → {@code "Some Field Name"}. */
    private static String camelToTitle(String camel) {
        if (camel == null || camel.isBlank()) return camel;
        StringBuilder sb = new StringBuilder();
        sb.append(Character.toUpperCase(camel.charAt(0)));
        for (int i = 1; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                sb.append(' ');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // List
    // -------------------------------------------------------------------------

    @GetMapping(value = "/{fqn}/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> list(
            @PathVariable String fqn,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        Class<?> entityClass = resolveClass(fqn);
        if (entityClass == null) {
            return notFound("Type not found: " + fqn);
        }

        List<Object> items = new ArrayList<>();
        try (Stream<Object> stream = typeDatabaseService.list(entityClass, page, pageSize)) {
            stream.forEach(items::add);
        }

        try {
            return ResponseEntity.ok(objectMapper.writeValueAsString(items));
        } catch (Exception e) {
            return error("Serialisation failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Count
    // -------------------------------------------------------------------------

    @GetMapping(value = "/{fqn}/count", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> count(@PathVariable String fqn) {
        Class<?> entityClass = resolveClass(fqn);
        if (entityClass == null) {
            return notFound("Type not found: " + fqn);
        }
        long count = typeDatabaseService.count(entityClass);
        return ResponseEntity.ok("{\"count\":" + count + "}");
    }

    // -------------------------------------------------------------------------
    // Get
    // -------------------------------------------------------------------------

    @GetMapping(value = "/{fqn}/{uuid}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> get(@PathVariable String fqn, @PathVariable String uuid) {
        Class<?> entityClass = resolveClass(fqn);
        if (entityClass == null) {
            return notFound("Type not found: " + fqn);
        }

        Object entity = typeDatabaseService.get(entityClass, uuid);
        if (entity == null) {
            return notFound("Entity not found: " + uuid);
        }

        try {
            return ResponseEntity.ok(objectMapper.writeValueAsString(entity));
        } catch (Exception e) {
            return error("Serialisation failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Save (multipart or form-urlencoded)
    // -------------------------------------------------------------------------

    @PostMapping(value = "/{fqn}",
            consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.APPLICATION_FORM_URLENCODED_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> save(@PathVariable String fqn, HttpServletRequest request) {
        Class<?> entityClass = resolveClass(fqn);
        if (entityClass == null) {
            return notFound("Type not found: " + fqn);
        }

        Map<String, String[]> params = request.getParameterMap();

        Object entity;
        try {
            entity = formConverter.convert(params, entityClass);
        } catch (FormConversionException e) {
            log.warn("Form conversion failed for {}: {}", fqn, e.getMessage());
            return ResponseEntity.badRequest()
                    .body("{\"status\":\"error\",\"message\":\"" + escape(e.getMessage()) + "\"}");
        }

        try {
            typeDatabaseService.save(entity);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body("{\"status\":\"error\",\"message\":\"" + escape(e.getMessage()) + "\"}");
        }

        return ResponseEntity.ok("{\"status\":\"ok\",\"uuid\":\"" + escape(uuidOf(entity)) + "\"}");
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    @DeleteMapping(value = "/{fqn}/{uuid}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> delete(@PathVariable String fqn, @PathVariable String uuid) {
        Class<?> entityClass = resolveClass(fqn);
        if (entityClass == null) {
            return notFound("Type not found: " + fqn);
        }
        typeDatabaseService.delete(entityClass, uuid);
        return ResponseEntity.ok("{\"status\":\"ok\"}");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Class<?> resolveClass(String fqn) {
        try {
            return classLoader.loadClass(fqn);
        } catch (ClassNotFoundException e) {
            log.warn("Could not resolve type: {}", fqn);
            return null;
        }
    }

    private String uuidOf(Object entity) {
        try {
            return (String) entity.getClass().getMethod("uuid").invoke(entity);
        } catch (Exception e) {
            return "";
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static ResponseEntity<String> notFound(String message) {
        return ResponseEntity.status(404)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":\"error\",\"message\":\"" + escape(message) + "\"}");
    }

    private static ResponseEntity<String> error(String message) {
        return ResponseEntity.internalServerError()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":\"error\",\"message\":\"" + escape(message) + "\"}");
    }
}
