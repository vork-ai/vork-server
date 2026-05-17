package sh.vork.typegen;

import sh.vork.database.DatabaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application-scoped class loader that resolves Java types persisted in
 * MongoDB by {@link TypeGeneratorService#compileAndSave}.
 *
 * <h3>Resolution order</h3>
 * <ol>
 *   <li>In-process cache ({@code classCache}) — fastest path.</li>
 *   <li>MongoDB ({@code javaTypeRepository}) — fetches and defines the class
 *       on first access after startup or after an explicit {@link #evict}.</li>
 *   <li>Parent class loader (the Spring application class loader) — for all
 *       normal JDK / framework / project classes.</li>
 * </ol>
 *
 * <h3>Usage as a parent class loader</h3>
 * {@link TypeGeneratorService} sets this instance as the parent of every
 * short-lived {@link InMemoryClassLoader} it creates.  This means code compiled
 * at runtime can transparently reference any type previously persisted to the
 * database without the caller having to supply explicit dependency bytecode.
 *
 * <h3>Cache management</h3>
 * <ul>
 *   <li>{@link #register} — called immediately after a successful compile + save
 *       so the new class is available without a DB round-trip.</li>
 *   <li>{@link #evict} — removes one FQN (plus its {@code $Inner} variants) to
 *       force a reload from the database on the next access.</li>
 *   <li>{@link #evictAll} — clears the entire in-process cache.</li>
 * </ul>
 */
@Component
public class JavaTypeClassLoader extends ClassLoader {

    private static final Logger log = LoggerFactory.getLogger(JavaTypeClassLoader.class);

    private final DatabaseRepository<JavaType> repository;
    private final ConcurrentHashMap<String, Class<?>> classCache = new ConcurrentHashMap<>();

    /**
     * Bytes staged by {@link #register} but not yet loaded via {@code defineClass}.
     * Keeping them separate prevents {@code LinkageError: duplicate class definition}
     * when a type is re-compiled in the same JVM session, because {@code defineClass}
     * is only called the first time {@link #findClass} is actually invoked.
     */
    private final ConcurrentHashMap<String, byte[]> registeredBytes = new ConcurrentHashMap<>();

    @Autowired
    public JavaTypeClassLoader(DatabaseRepository<JavaType> javaTypeRepository) {
        // Parent = the class loader that loaded this class (the Spring app CL).
        super(JavaTypeClassLoader.class.getClassLoader());
        this.repository = javaTypeRepository;
    }

    /**
     * Protected constructor for unit tests — allows supplying an explicit
     * parent class loader without Spring wiring.
     */
    protected JavaTypeClassLoader(ClassLoader parent, DatabaseRepository<JavaType> javaTypeRepository) {
        super(parent);
        this.repository = javaTypeRepository;
    }

    // ── ClassLoader contract ──────────────────────────────────────────────────

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        Class<?> cached = classCache.get(name);
        if (cached != null) {
            return cached;
        }

        // Check bytes that were staged by register() but not yet defined
        byte[] staged = registeredBytes.remove(name);
        if (staged != null) {
            Class<?> defined = defineClass(name, staged, 0, staged.length);
            classCache.put(name, defined);
            return defined;
        }

        // Fall back to the DB
        JavaType javaType = repository.get(name);
        if (javaType == null) {
            throw new ClassNotFoundException(name);
        }

        // Load all bytecode entries from the persisted record so that inner
        // classes referenced during defineClass are also available.
        Class<?> primary = null;
        for (Map.Entry<String, String> entry : javaType.bytecode().entrySet()) {
            // Skip any class already defined in this loader
            if (classCache.containsKey(entry.getKey())) {
                if (entry.getKey().equals(name)) {
                    primary = classCache.get(name);
                }
                continue;
            }
            byte[] bytes = Base64.getDecoder().decode(entry.getValue());
            Class<?> defined = defineClass(entry.getKey(), bytes, 0, bytes.length);
            classCache.put(entry.getKey(), defined);
            if (entry.getKey().equals(name)) {
                primary = defined;
            }
        }

        if (primary == null) {
            throw new ClassNotFoundException(
                    "Bytecode entry for '" + name + "' not found in JavaType record (uuid=" + javaType.uuid() + ")");
        }

        log.debug("Loaded '{}' from database", name);
        return primary;
    }

    // ── Cache management ──────────────────────────────────────────────────────

    /**
     * Stages freshly compiled bytecode for lazy loading into this class loader.
     * The bytes are stored in an internal map and only promoted to
     * {@code defineClass} the first time {@link #findClass} is actually invoked
     * for the type.  This avoids {@code LinkageError: duplicate class definition}
     * when a type is re-compiled in the same JVM session — the old definition
     * stays in the JVM's internal table while the cache and staged bytes reflect
     * the latest version.
     *
     * @param primaryFqn  the fully-qualified name of the primary (outermost) type
     * @param bytecodeMap map of fqn → raw bytes (includes inner class entries)
     */
    public void register(String primaryFqn, Map<String, byte[]> bytecodeMap) {
        // Evict any cached (already-defined) version so subsequent loads re-check
        // the staged bytes (or fall through to the DB for true persistence).
        evict(primaryFqn);
        // Stage the new bytes for lazy defineClass on first load.
        registeredBytes.putAll(bytecodeMap);
        log.debug("Staged '{}' ({} class file(s)) for lazy load", primaryFqn, bytecodeMap.size());
    }

    /**
     * Removes the named class and any of its inner classes (those whose FQN
     * starts with {@code fqn + "$"}) from both the class cache and the staged
     * bytes map.  The next call to {@code loadClass(fqn)} will reload the bytes
     * from MongoDB (or re-stage them via another {@link #register} call).
     */
    public void evict(String fqn) {
        classCache.keySet().removeIf(key -> key.equals(fqn) || key.startsWith(fqn + "$"));
        registeredBytes.keySet().removeIf(key -> key.equals(fqn) || key.startsWith(fqn + "$"));
        log.debug("Evicted '{}' from cache and staged bytes", fqn);
    }

    /**
     * Clears both the class cache and any staged bytes.  All subsequent type
     * lookups will reload their bytes from MongoDB.
     */
    public void evictAll() {
        classCache.clear();
        registeredBytes.clear();
        log.debug("Evicted all entries from JavaTypeClassLoader cache");
    }

    /**
     * Returns the number of types currently tracked: both classes that have been
     * defined via {@code defineClass} and bytes staged for lazy loading.
     * Useful for tests and monitoring.
     */
    public int cacheSize() {
        return classCache.size() + registeredBytes.size();
    }
}
