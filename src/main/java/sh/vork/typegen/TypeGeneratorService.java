package sh.vork.typegen;

import sh.vork.database.DatabaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URLClassLoader;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Compiles Java source code supplied at runtime and loads the resulting type
 * into the JVM.  Supports {@code record}, {@code class}, {@code interface},
 * {@code enum}, and {@code @interface} declarations.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * String source = """
 *     package com.example;
 *     public record Point(int x, int y) {}
 *     """;
 *
 * Class<?> clazz = typeGeneratorService.compile(source);
 * Object instance = clazz.getDeclaredConstructors()[0].newInstance(1, 2);
 * }</pre>
 *
 * <h3>Requirements</h3>
 * <ul>
 *   <li>The application must run on a JDK (not a bare JRE).  The JDK compiler
 *       ({@code javax.tools.JavaCompiler}) must be available.</li>
 *   <li>A {@code package} declaration is required in the source.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * Each {@link #compile} call creates its own compiler task and class loader.
 * The compiled-class cache ({@link #get}) is safe for concurrent access.
 */
@Service
public class TypeGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(TypeGeneratorService.class);

    // Matches:  package com.example;
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    // Matches the first type-declaration keyword after any leading modifiers.
    // Handles: class, record, interface, enum, @interface
    private static final Pattern TYPE_NAME_PATTERN =
            Pattern.compile(
                    "(?:(?:public|protected|private|abstract|final|sealed|non-sealed)\\s+)*" +
                    "(?:@interface|interface|class|record|enum)\\s+(\\w+)");

    /** Cache: fully-qualified name → loaded Class. */
    private final ConcurrentHashMap<String, Class<?>> loadedClasses = new ConcurrentHashMap<>();

    /** Cache: fully-qualified name → compiled bytecode (includes inner classes). */
    private final ConcurrentHashMap<String, byte[]> compiledBytecode = new ConcurrentHashMap<>();

    /**
     * Optional — wired by Spring when the full persistence stack is available.
     * {@code null} when the service is instantiated directly in unit tests.
     */
    @Nullable
    private JavaTypeClassLoader javaTypeClassLoader;

    @Nullable
    private DatabaseRepository<JavaType> javaTypeRepository;

    @Autowired(required = false)
    public void setJavaTypeClassLoader(JavaTypeClassLoader javaTypeClassLoader) {
        this.javaTypeClassLoader = javaTypeClassLoader;
    }

    @Autowired(required = false)
    public void setJavaTypeRepository(DatabaseRepository<JavaType> javaTypeRepository) {
        this.javaTypeRepository = javaTypeRepository;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Compiles {@code source} and loads the primary declared type into the JVM.
     *
     * @param source complete Java source, including a {@code package} declaration
     * @return the loaded {@link Class}
     * @throws TypeGenerationException if the compiler is unavailable, the source
     *         fails to compile, or the class cannot be loaded
     */
    public Class<?> compile(String source) {
        return compile(source, Map.of());
    }

    /**
     * Compiles {@code source} with access to {@code dependencies} — bytecode
     * produced by earlier {@link #compile} calls — so the new type can import
     * and reference previously generated types.
     *
     * <pre>{@code
     * Class<?> money  = svc.compile(moneySource);
     * Class<?> invoice = svc.compile(invoiceSource, svc.getBytecode("com.example.Money"));
     * }</pre>
     *
     * @param source       complete Java source, including a {@code package} declaration
     * @param dependencies map of fully-qualified name → bytecode of types the source
     *                     references (may be empty)
     * @return the loaded {@link Class}
     * @throws TypeGenerationException compilation or load failure
     */
    public Class<?> compile(String source, Map<String, byte[]> dependencies) {
        String fqn = extractFullyQualifiedName(source);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new TypeGenerationException(
                    "Java compiler not available — ensure the application is running on a JDK, not a JRE.");
        }

        // Augment caller-supplied dependencies with any types persisted to the DB.
        // This allows compiling types that reference previously saved types without
        // the caller having to supply explicit dependency bytecode.
        // Caller-supplied deps take priority over DB entries (same key).
        Map<String, byte[]> enrichedDeps;
        if (javaTypeRepository != null) {
            enrichedDeps = new HashMap<>();
            try (var stream = javaTypeRepository.list(0, Integer.MAX_VALUE)) {
                stream.forEach(jt -> jt.bytecode().forEach((k, b64) -> {
                    if (!dependencies.containsKey(k)) {
                        enrichedDeps.put(k, Base64.getDecoder().decode(b64));
                    }
                }));
            }
            enrichedDeps.putAll(dependencies);
        } else {
            enrichedDeps = dependencies;
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        try (StandardJavaFileManager standardFm = compiler.getStandardFileManager(diagnostics, null, null);
             InMemoryJavaFileManager fileManager  = new InMemoryJavaFileManager(standardFm, enrichedDeps)) {

            List<JavaFileObject> sources = List.of(InMemoryJavaFileManager.sourceFileObject(fqn, source));
            List<String> options = List.of("--release", "21", "-parameters", "-classpath", buildClasspath());

            boolean success = compiler.getTask(null, fileManager, diagnostics, options, null, sources).call();

            if (!success) {
                String errors = diagnostics.getDiagnostics().stream()
                        .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                        .map(Object::toString)
                        .collect(Collectors.joining("\n"));
                throw new TypeGenerationException("Compilation failed for " + fqn + ":\n" + errors);
            }

            Map<String, byte[]> bytecodeMap = fileManager.getBytecodeMap();

            // Merge dependency bytecode into the class loader so the JVM can
            // resolve referenced types that live in a sibling class loader.
            Map<String, byte[]> loaderBytecode = new HashMap<>(enrichedDeps);
            loaderBytecode.putAll(bytecodeMap);   // newly compiled output takes precedence

            // If the DB-backed class loader is available, use it as the parent so
            // that previously persisted types are automatically resolvable without
            // the caller supplying explicit dependency bytecode.
            ClassLoader parent = javaTypeClassLoader != null
                    ? javaTypeClassLoader
                    : Thread.currentThread().getContextClassLoader();

            InMemoryClassLoader classLoader = new InMemoryClassLoader(parent, loaderBytecode);

            Class<?> clazz = classLoader.loadClass(fqn);
            loadedClasses.put(fqn, clazz);
            compiledBytecode.putAll(bytecodeMap);

            log.info("Compiled and loaded: {} ({} inner type(s))", fqn, bytecodeMap.size() - 1);
            return clazz;

        } catch (TypeGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new TypeGenerationException("Unexpected error compiling " + fqn, e);
        }
    }

    /**
     * Returns a previously compiled class by its fully-qualified name, or
     * {@code null} if it has not been compiled in this session.
     */
    public Class<?> get(String fullyQualifiedName) {
        return loadedClasses.get(fullyQualifiedName);
    }

    /**
     * Returns the raw bytecode for a previously compiled type (including inner
     * classes whose names follow the {@code OuterClass$InnerClass} convention),
     * or {@code null} if not found.
     * <p>
     * Use the returned map as the {@code dependencies} argument when compiling
     * a type that imports the previously generated type.
     */
    public byte[] getBytecode(String fullyQualifiedName) {
        return compiledBytecode.get(fullyQualifiedName);
    }

    /**
     * Returns an unmodifiable view of all bytecode compiled so far in this
     * service instance.  Useful for passing a batch of previously generated
     * types as dependencies.
     */
    public Map<String, byte[]> getAllBytecode() {
        return Map.copyOf(compiledBytecode);
    }

    /**
     * Compiles {@code source}, persists the result to MongoDB as a
     * {@link JavaType} record, and registers the bytecode in
     * {@link JavaTypeClassLoader} so it is immediately resolvable app-wide.
     *
     * <p>Re-saving the same fully-qualified name updates the existing record
     * (upsert by FQN).  The {@link JavaTypeClassLoader} cache is evicted for
     * the affected FQN before re-registration so callers always get the latest
     * version.
     *
     * <p>Requires the optional {@link JavaTypeClassLoader} and
     * {@link DatabaseRepository} dependencies to be wired (they are absent when
     * the service is constructed directly in unit tests — use
     * {@link #compile(String)} in that case).
     *
     * @param source complete Java source, including a {@code package} declaration
     * @return the loaded {@link Class}
     * @throws IllegalStateException   if the persistence dependencies are not wired
     * @throws TypeGenerationException compilation or load failure
     */
    public Class<?> compileAndSave(String source) {
        if (javaTypeClassLoader == null || javaTypeRepository == null) {
            throw new IllegalStateException(
                    "compileAndSave() requires JavaTypeClassLoader and DatabaseRepository<JavaType> " +
                    "to be wired. Use compile() for persistence-free operation.");
        }

        String fqn = extractFullyQualifiedName(source);

        Class<?> clazz = compile(source);
        Map<String, byte[]> bytecodeMap = Map.copyOf(
                compiledBytecode.entrySet().stream()
                        .filter(e -> e.getKey().equals(fqn) || e.getKey().startsWith(fqn + "$"))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));

        // Encode bytecode as Base64 for Jackson-friendly MongoDB storage.
        Map<String, String> base64Bytecode = new HashMap<>();
        bytecodeMap.forEach((k, v) -> base64Bytecode.put(k, Base64.getEncoder().encodeToString(v)));

        long now = System.currentTimeMillis();
        JavaType existing = javaTypeRepository.get(fqn);
        long createdAt = existing != null ? existing.createdAt() : now;

        javaTypeRepository.save(new JavaType(fqn, source, base64Bytecode, createdAt, now));
        javaTypeClassLoader.register(fqn, bytecodeMap);

        log.info("Compiled, persisted, and registered: {}", fqn);
        return clazz;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String extractFullyQualifiedName(String source) {
        Matcher pkgMatcher = PACKAGE_PATTERN.matcher(source);
        String packageName = pkgMatcher.find() ? pkgMatcher.group(1) : null;
        if (packageName == null) {
            throw new TypeGenerationException(
                    "Source must include a 'package' declaration so the class name can be determined.");
        }

        Matcher typeMatcher = TYPE_NAME_PATTERN.matcher(source);
        if (!typeMatcher.find()) {
            throw new TypeGenerationException(
                    "Could not locate a type declaration (class / record / interface / enum) in the source.");
        }
        return packageName + "." + typeMatcher.group(1);
    }

    /**
     * Builds a classpath string from {@code java.class.path} and any
     * {@link URLClassLoader} ancestors of the current context class loader.
     * This lets the compiled source import Spring, project, and JDK types.
     */
    private static String buildClasspath() {
        StringBuilder cp = new StringBuilder(System.getProperty("java.class.path", ""));

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        while (cl != null) {
            if (cl instanceof URLClassLoader ucl) {
                for (var url : ucl.getURLs()) {
                    if (!cp.isEmpty()) {
                        cp.append(File.pathSeparator);
                    }
                    cp.append(url.getFile());
                }
            }
            cl = cl.getParent();
        }
        return cp.toString();
    }
}
