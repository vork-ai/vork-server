package sh.vork.typegen;

import sh.vork.database.mock.MapDatabaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TypeGeneratorService}.
 *
 * <p>No Spring context is required — the service is wired manually with
 * {@link MapDatabaseRepository} and {@link JavaTypeClassLoader}.
 *
 * <p>Test groups:
 * <ul>
 *   <li>{@link SimpleTypeTests}   — each fundamental declaration kind compiles and loads</li>
 *   <li>{@link NestedTypeTests}   — records with inner / nested types in the same source</li>
 *   <li>{@link DependentTypeTests}— types that reference a previously compiled type</li>
 *   <li>{@link CacheTests}        — {@code get()} and {@code getBytecode()} behaviour</li>
 *   <li>{@link FailureTests}      — expected {@link TypeGenerationException} cases</li>
 *   <li>{@link PersistenceTests}  — {@code compileAndSave()} persistence behaviour</li>
 *   <li>{@link ClassLoaderTests}  — {@link JavaTypeClassLoader} cache and DB-load behaviour</li>
 * </ul>
 */
class TypeGeneratorServiceTest {

    private MapDatabaseRepository<JavaType> javaTypeRepo;
    private JavaTypeClassLoader javaTypeCL;
    private TypeGeneratorService svc;

    @BeforeEach
    void setup() {
        javaTypeRepo = new MapDatabaseRepository<>(JavaType.class);
        javaTypeCL = new JavaTypeClassLoader(ClassLoader.getSystemClassLoader(), javaTypeRepo);
        svc = new TypeGeneratorService();
        svc.setJavaTypeClassLoader(javaTypeCL);
        svc.setJavaTypeRepository(javaTypeRepo);
    }

    // =========================================================================
    // Simple type declarations
    // =========================================================================

    @Nested
    class SimpleTypeTests {

        @Test
        void compilesSimpleRecord() throws Exception {
            Class<?> clazz = svc.compile("""
                    package sh.vork.gen.test;
                    public record Point(int x, int y) {}
                    """);

            assertEquals("sh.vork.gen.test.Point", clazz.getName());
            assertTrue(clazz.isRecord());

            Constructor<?> ctor = clazz.getDeclaredConstructor(int.class, int.class);
            Object instance = ctor.newInstance(3, 7);
            assertEquals(3, clazz.getMethod("x").invoke(instance));
            assertEquals(7, clazz.getMethod("y").invoke(instance));
        }

        @Test
        void compilesSimpleClass() throws Exception {
            Class<?> clazz = svc.compile("""
                    package sh.vork.gen.test;
                    public class Greeter {
                        private final String name;
                        public Greeter(String name) { this.name = name; }
                        public String greet() { return "Hello, " + name + "!"; }
                    }
                    """);

            assertFalse(clazz.isRecord());
            assertFalse(clazz.isInterface());
            assertFalse(clazz.isEnum());

            Object instance = clazz.getDeclaredConstructor(String.class).newInstance("World");
            assertEquals("Hello, World!", clazz.getMethod("greet").invoke(instance));
        }

        @Test
        void compilesInterface() {
            Class<?> clazz = svc.compile("""
                    package sh.vork.gen.test;
                    public interface Printable {
                        String render();
                    }
                    """);

            assertTrue(clazz.isInterface());
            assertEquals("sh.vork.gen.test.Printable", clazz.getName());
        }

        @Test
        void compilesEnum() throws Exception {
            Class<?> clazz = svc.compile("""
                    package sh.vork.gen.test;
                    public enum Status { PENDING, ACTIVE, CLOSED }
                    """);

            assertTrue(clazz.isEnum());
            Object[] constants = (Object[]) clazz.getMethod("values").invoke(null);
            assertEquals(3, constants.length);
            assertEquals("PENDING", constants[0].toString());
            assertEquals("ACTIVE",  constants[1].toString());
            assertEquals("CLOSED",  constants[2].toString());
        }

        @Test
        void compilesAnnotationType() {
            Class<?> clazz = svc.compile("""
                    package sh.vork.gen.test;
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.TYPE)
                    public @interface Generated {
                        String by() default "vork";
                    }
                    """);

            assertTrue(clazz.isAnnotation());
        }

        @Test
        void compilesAbstractClass() {
            Class<?> clazz = svc.compile("""
                    package sh.vork.gen.test;
                    public abstract class Shape {
                        public abstract double area();
                        public String describe() { return "shape"; }
                    }
                    """);

            assertTrue(java.lang.reflect.Modifier.isAbstract(clazz.getModifiers()));
        }

        @Test
        void compilesRecordWithListField() throws Exception {
            Class<?> clazz = svc.compile("""
                    package sh.vork.gen.test;
                    import java.util.List;
                    public record TaggedItem(String name, List<String> tags) {}
                    """);

            assertTrue(clazz.isRecord());
            Constructor<?> ctor = clazz.getDeclaredConstructor(String.class, List.class);
            Object instance = ctor.newInstance("widget", List.of("sale", "new"));
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) clazz.getMethod("tags").invoke(instance);
            assertEquals(List.of("sale", "new"), tags);
        }
    }

    // =========================================================================
    // Nested / inner types within a single source unit
    // =========================================================================

    @Nested
    class NestedTypeTests {

        @Test
        void compilesRecordWithStaticInnerRecord() throws Exception {
            Class<?> outer = svc.compile("""
                    package sh.vork.gen.test;
                    public record Order(String id, Order.Address deliverTo) {
                        public record Address(String street, String city) {}
                    }
                    """);

            assertTrue(outer.isRecord());

            Class<?> inner = outer.getClassLoader().loadClass("sh.vork.gen.test.Order$Address");
            assertTrue(inner.isRecord());

            Constructor<?> addrCtor = inner.getDeclaredConstructor(String.class, String.class);
            Object addr = addrCtor.newInstance("1 Main St", "Springfield");

            Constructor<?> orderCtor = outer.getDeclaredConstructor(String.class, inner);
            Object order = orderCtor.newInstance("ORD-001", addr);

            Object deliverTo = outer.getMethod("deliverTo").invoke(order);
            assertEquals("Springfield", inner.getMethod("city").invoke(deliverTo));
        }

        @Test
        void compilesClassWithStaticInnerEnum() throws Exception {
            Class<?> outer = svc.compile("""
                    package sh.vork.gen.test;
                    public class Shipment {
                        public enum Carrier { FEDEX, UPS, DHL }
                        private final Carrier carrier;
                        public Shipment(Carrier carrier) { this.carrier = carrier; }
                        public Carrier carrier() { return carrier; }
                    }
                    """);

            Class<?> carrierEnum = outer.getClassLoader().loadClass("sh.vork.gen.test.Shipment$Carrier");
            assertTrue(carrierEnum.isEnum());

            Object[] constants = (Object[]) carrierEnum.getMethod("values").invoke(null);
            assertEquals("FEDEX", constants[0].toString());
        }

        @Test
        void compilesRecordWithMultipleNestedRecords() throws Exception {
            Class<?> product = svc.compile("""
                    package sh.vork.gen.test;
                    import java.util.List;
                    public record Product(
                            String sku,
                            Product.Dimensions dimensions,
                            List<Product.Tag> tags
                    ) {
                        public record Dimensions(double width, double height, double depth) {}
                        public record Tag(String key, String value) {}
                    }
                    """);

            assertTrue(product.isRecord());

            Class<?> dimsClass = product.getClassLoader().loadClass("sh.vork.gen.test.Product$Dimensions");
            Class<?> tagClass  = product.getClassLoader().loadClass("sh.vork.gen.test.Product$Tag");

            Object dims = dimsClass.getDeclaredConstructor(double.class, double.class, double.class)
                                   .newInstance(10.0, 5.0, 3.0);
            Object tag  = tagClass.getDeclaredConstructor(String.class, String.class)
                                  .newInstance("colour", "red");

            Object instance = product.getDeclaredConstructor(String.class, dimsClass, List.class)
                                     .newInstance("SKU-42", dims, List.of(tag));

            assertEquals("SKU-42", product.getMethod("sku").invoke(instance));
            assertEquals(10.0, dimsClass.getMethod("width").invoke(
                    product.getMethod("dimensions").invoke(instance)));
        }
    }

    // =========================================================================
    // Cross-compilation: types that reference a recently generated type
    // =========================================================================

    @Nested
    class DependentTypeTests {

        @Test
        void compilesTypeReferencingPreviouslyGeneratedRecord() throws Exception {
            // Step 1: compile Money
            svc.compile("""
                    package sh.vork.gen.dep;
                    import java.math.BigDecimal;
                    public record Money(BigDecimal amount, String currency) {}
                    """);

            // Step 2: compile Invoice referencing Money — pass all compiled bytecode as deps
            Class<?> invoice = svc.compile("""
                    package sh.vork.gen.dep;
                    import java.util.List;
                    public record Invoice(String ref, Money total, List<Money> lineAmounts) {}
                    """, svc.getAllBytecode());

            assertTrue(invoice.isRecord());
            assertEquals("sh.vork.gen.dep.Invoice", invoice.getName());

            // Verify the 'total' accessor returns the Money type
            Method totalMethod = invoice.getMethod("total");
            assertEquals("sh.vork.gen.dep.Money", totalMethod.getReturnType().getName());
        }

        @Test
        void compilesThreeGenerationChain() throws Exception {
            // Level 1 — Coordinate
            svc.compile("""
                    package sh.vork.gen.chain;
                    public record Coordinate(double lat, double lon) {}
                    """);

            // Level 2 — Location references Coordinate
            svc.compile("""
                    package sh.vork.gen.chain;
                    public record Location(String name, Coordinate position) {}
                    """, svc.getAllBytecode());

            // Level 3 — Route references Location
            Class<?> route = svc.compile("""
                    package sh.vork.gen.chain;
                    import java.util.List;
                    public record Route(String id, List<Location> stops) {}
                    """, svc.getAllBytecode());

            assertTrue(route.isRecord());

            Method stopsMethod = route.getMethod("stops");
            assertEquals("java.util.List", stopsMethod.getReturnType().getName());
        }

        @Test
        void compilesClassImplementingPreviouslyGeneratedInterface() throws Exception {
            // Step 1: compile the interface
            svc.compile("""
                    package sh.vork.gen.iface;
                    public interface Describable {
                        String describe();
                    }
                    """);

            // Step 2: compile a class implementing it
            Class<?> implClass = svc.compile("""
                    package sh.vork.gen.iface;
                    public class Widget implements Describable {
                        private final String name;
                        public Widget(String name) { this.name = name; }
                        @Override public String describe() { return "Widget: " + name; }
                    }
                    """, svc.getAllBytecode());

            Object instance = implClass.getDeclaredConstructor(String.class).newInstance("Cog");
            assertEquals("Widget: Cog", implClass.getMethod("describe").invoke(instance));
        }

        @Test
        void compilesEnumReferencingPreviouslyGeneratedRecord() throws Exception {
            svc.compile("""
                    package sh.vork.gen.enumdep;
                    public record Config(String key, String value) {}
                    """);

            Class<?> environment = svc.compile("""
                    package sh.vork.gen.enumdep;
                    public enum Environment {
                        DEV(new Config("env", "development")),
                        PROD(new Config("env", "production"));

                        private final Config config;
                        Environment(Config config) { this.config = config; }
                        public Config config() { return config; }
                    }
                    """, svc.getAllBytecode());

            assertTrue(environment.isEnum());
            Object[] constants = (Object[]) environment.getMethod("values").invoke(null);
            assertEquals("DEV",  constants[0].toString());
            assertEquals("PROD", constants[1].toString());
        }
    }

    // =========================================================================
    // Cache behaviour
    // =========================================================================

    @Nested
    class CacheTests {

        @Test
        void getReturnsCompiledClassByFqn() {
            svc.compile("""
                    package sh.vork.gen.cache;
                    public record Cached(String value) {}
                    """);

            Class<?> retrieved = svc.get("sh.vork.gen.cache.Cached");
            assertNotNull(retrieved);
            assertTrue(retrieved.isRecord());
        }

        @Test
        void getReturnsNullForUnknownFqn() {
            assertNull(svc.get("sh.vork.gen.cache.DoesNotExist"));
        }

        @Test
        void getBytecodeReturnsBytesForCompiledType() {
            svc.compile("""
                    package sh.vork.gen.cache;
                    public record Bytes(int n) {}
                    """);

            byte[] bytes = svc.getBytecode("sh.vork.gen.cache.Bytes");
            assertNotNull(bytes);
            assertTrue(bytes.length > 0);
        }

        @Test
        void getBytecodeReturnsNullForUnknownFqn() {
            assertNull(svc.getBytecode("sh.vork.gen.cache.NeverCompiled"));
        }

        @Test
        void getAllBytecodeIncludesInnerClasses() {
            svc.compile("""
                    package sh.vork.gen.cache;
                    public record Outer(String x) {
                        public record Inner(int n) {}
                    }
                    """);

            Map<String, byte[]> all = svc.getAllBytecode();
            assertTrue(all.containsKey("sh.vork.gen.cache.Outer"));
            assertTrue(all.containsKey("sh.vork.gen.cache.Outer$Inner"));
        }
    }

    // =========================================================================
    // Expected failure cases
    // =========================================================================

    @Nested
    class FailureTests {

        @Test
        void throwsOnSyntaxError() {
            TypeGenerationException ex = assertThrows(TypeGenerationException.class, () ->
                    svc.compile("""
                            package sh.vork.gen.fail;
                            public record Broken(String name {  // missing closing paren
                            }
                            """));

            assertTrue(ex.getMessage().contains("Compilation failed"),
                    "Message should indicate compilation failure; was: " + ex.getMessage());
        }

        @Test
        void throwsOnUndefinedTypeReference() {
            TypeGenerationException ex = assertThrows(TypeGenerationException.class, () ->
                    svc.compile("""
                            package sh.vork.gen.fail;
                            public record Invoice(NonExistentType total) {}
                            """));

            assertTrue(ex.getMessage().contains("Compilation failed"),
                    "Message should indicate compilation failure; was: " + ex.getMessage());
        }

        @Test
        void throwsWhenPackageDeclarationIsMissing() {
            TypeGenerationException ex = assertThrows(TypeGenerationException.class, () ->
                    svc.compile("public record NoPkg(String x) {}"));

            assertTrue(ex.getMessage().contains("package"),
                    "Message should mention missing package; was: " + ex.getMessage());
        }

        @Test
        void throwsWhenNoTypeDeclarationIsPresent() {
            TypeGenerationException ex = assertThrows(TypeGenerationException.class, () ->
                    svc.compile("""
                            package sh.vork.gen.fail;
                            // just a comment, no type declaration
                            """));

            assertTrue(ex.getMessage().toLowerCase().contains("type declaration")
                    || ex.getMessage().toLowerCase().contains("class")
                    || ex.getMessage().toLowerCase().contains("record"),
                    "Message should indicate missing type; was: " + ex.getMessage());
        }

        @Test
        void throwsWhenReferencingPreviouslyGeneratedTypeWithoutPassingDependency() {
            // Compile the dependency first
            svc.compile("""
                    package sh.vork.gen.fail;
                    public record Token(String value) {}
                    """);

            // Compile a type that references Token WITHOUT passing the dependency bytecode
            TypeGenerationException ex = assertThrows(TypeGenerationException.class, () ->
                    svc.compile("""
                            package sh.vork.gen.fail;
                            public record Request(String id, Token auth) {}
                            """));

            assertTrue(ex.getMessage().contains("Compilation failed"),
                    "Should fail because Token is not on the compile classpath; was: " + ex.getMessage());
        }

        @Test
        void throwsOnInvalidModifierCombination() {
            TypeGenerationException ex = assertThrows(TypeGenerationException.class, () ->
                    svc.compile("""
                            package sh.vork.gen.fail;
                            public abstract final class Impossible {}
                            """));

            assertTrue(ex.getMessage().contains("Compilation failed"),
                    "Message should indicate compilation failure; was: " + ex.getMessage());
        }
    }

    // =========================================================================
    // Persistence — compileAndSave()
    // =========================================================================

    @Nested
    class PersistenceTests {

        @Test
        void compileAndSaveStoresJavaTypeInRepo() {
            svc.compileAndSave("""
                    package sh.vork.gen.persist;
                    public record Ticket(String id, String title) {}
                    """);

            JavaType saved = javaTypeRepo.get("sh.vork.gen.persist.Ticket");
            assertNotNull(saved);
            assertEquals("sh.vork.gen.persist.Ticket", saved.uuid());
            assertTrue(saved.source().contains("record Ticket"));
        }

        @Test
        void compileAndSaveUsesBase64Encoding() {
            svc.compileAndSave("""
                    package sh.vork.gen.persist;
                    public record Token(String value) {}
                    """);

            JavaType saved = javaTypeRepo.get("sh.vork.gen.persist.Token");
            assertNotNull(saved);
            assertTrue(saved.bytecode().containsKey("sh.vork.gen.persist.Token"));

            // Verify the value is valid Base64 that decodes to non-empty bytes
            String b64 = saved.bytecode().get("sh.vork.gen.persist.Token");
            byte[] decoded = Base64.getDecoder().decode(b64);
            assertTrue(decoded.length > 0, "Decoded bytecode must be non-empty");
        }

        @Test
        void innerClassesArePersistedAndLoadable() {
            svc.compileAndSave("""
                    package sh.vork.gen.persist;
                    public record Container(String id) {
                        public record Item(String name) {}
                    }
                    """);

            JavaType saved = javaTypeRepo.get("sh.vork.gen.persist.Container");
            assertNotNull(saved);
            assertTrue(saved.bytecode().containsKey("sh.vork.gen.persist.Container"));
            assertTrue(saved.bytecode().containsKey("sh.vork.gen.persist.Container$Item"));
        }

        @Test
        void recompilingSameFqnUpdatesRecord() {
            svc.compileAndSave("""
                    package sh.vork.gen.persist;
                    public record Mutable(String v1) {}
                    """);

            JavaType first = javaTypeRepo.get("sh.vork.gen.persist.Mutable");
            assertNotNull(first);
            long createdAt = first.createdAt();

            svc.compileAndSave("""
                    package sh.vork.gen.persist;
                    public record Mutable(String v1, String v2) {}
                    """);

            JavaType second = javaTypeRepo.get("sh.vork.gen.persist.Mutable");
            assertNotNull(second);
            // Source updated
            assertTrue(second.source().contains("v2"));
            // createdAt preserved
            assertEquals(createdAt, second.createdAt());
            // updatedAt >= createdAt
            assertTrue(second.updatedAt() >= createdAt);
        }

        @Test
        void compiledTypeSurvivesSimulatedRestart() throws Exception {
            svc.compileAndSave("""
                    package sh.vork.gen.persist;
                    public record Durable(String label) {}
                    """);

            // Simulate restart: fresh class loader backed by same DB repo
            JavaTypeClassLoader freshCL =
                    new JavaTypeClassLoader(ClassLoader.getSystemClassLoader(), javaTypeRepo);

            // The class should be loadable via the DB-backed loader
            Class<?> reloaded = freshCL.loadClass("sh.vork.gen.persist.Durable");
            assertNotNull(reloaded);
            assertEquals("sh.vork.gen.persist.Durable", reloaded.getName());
        }
    }

    // =========================================================================
    // JavaTypeClassLoader behaviour
    // =========================================================================

    @Nested
    class ClassLoaderTests {

        @Test
        void registerMakesTypeResolvable() throws Exception {
            svc.compileAndSave("""
                    package sh.vork.gen.cl;
                    public record Registered(int x) {}
                    """);

            // javaTypeCL should have the class in cache after compileAndSave
            Class<?> clazz = javaTypeCL.loadClass("sh.vork.gen.cl.Registered");
            assertNotNull(clazz);
            assertEquals("sh.vork.gen.cl.Registered", clazz.getName());
            assertEquals(1, javaTypeCL.cacheSize());
        }

        @Test
        void evictForcesReloadFromDb() throws Exception {
            svc.compileAndSave("""
                    package sh.vork.gen.cl;
                    public record Evictable(String s) {}
                    """);

            assertEquals(1, javaTypeCL.cacheSize());

            javaTypeCL.evict("sh.vork.gen.cl.Evictable");
            assertEquals(0, javaTypeCL.cacheSize());

            // Reload from DB — should succeed because record was persisted
            Class<?> reloaded = javaTypeCL.loadClass("sh.vork.gen.cl.Evictable");
            assertNotNull(reloaded);
            assertEquals(1, javaTypeCL.cacheSize());
        }

        @Test
        void evictAllClearsCache() throws Exception {
            svc.compileAndSave("""
                    package sh.vork.gen.cl;
                    public record Alpha(int n) {}
                    """);
            svc.compileAndSave("""
                    package sh.vork.gen.cl;
                    public record Beta(int n) {}
                    """);

            assertTrue(javaTypeCL.cacheSize() >= 2);

            javaTypeCL.evictAll();
            assertEquals(0, javaTypeCL.cacheSize());
        }

        @Test
        void dbLoadedTypeSatisfiesDependentCompilation() throws Exception {
            // Persist a base type
            svc.compileAndSave("""
                    package sh.vork.gen.cl;
                    public record Base(String value) {}
                    """);

            // Build a fresh service backed by the same DB repo (simulating restart)
            // The new service has an empty in-process bytecode cache.
            MapDatabaseRepository<JavaType> sameRepo = javaTypeRepo;
            JavaTypeClassLoader freshCL =
                    new JavaTypeClassLoader(ClassLoader.getSystemClassLoader(), sameRepo);
            TypeGeneratorService freshSvc = new TypeGeneratorService();
            freshSvc.setJavaTypeClassLoader(freshCL);
            freshSvc.setJavaTypeRepository(sameRepo);

            // Compile a type referencing Base WITHOUT passing explicit dependencies.
            // The DB-backed parent CL should resolve Base automatically.
            Class<?> derived = freshSvc.compile("""
                    package sh.vork.gen.cl;
                    public record Derived(Base base, String extra) {}
                    """);

            assertNotNull(derived);
            assertEquals("sh.vork.gen.cl.Derived", derived.getName());
        }
    }
}
