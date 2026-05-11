package sh.vork.typegen.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import sh.vork.typegen.FormConversionException;
import sh.vork.typegen.FormToObjectConverter;
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
import java.util.ArrayList;
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

    public TypeDatabaseController(TypeDatabaseService typeDatabaseService,
                                  FormToObjectConverter formConverter,
                                  JavaTypeClassLoader classLoader,
                                  ObjectMapper objectMapper) {
        this.typeDatabaseService = typeDatabaseService;
        this.formConverter = formConverter;
        this.classLoader = classLoader;
        this.objectMapper = objectMapper;
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
