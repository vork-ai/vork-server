package sh.vork.ssh.command;

import sh.vork.database.DatabaseEntity;
import sh.vork.database.DatabaseRepository;
import sh.vork.database.DatabaseRepositoryFactory;
import sh.vork.database.mock.MapDatabaseRepository;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.TypeDatabaseService;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test helper — provides a {@link TypeDatabaseService} backed by in-memory
 * {@link MapDatabaseRepository} instances and a no-op {@link JavaTypeClassLoader}
 * that resolves pre-registered classes from a fixed map.
 *
 * <p>Usage:
 * <pre>
 *   TestServiceFixture fix = new TestServiceFixture();
 *   fix.register(PersonRow.class);
 *   // ... use fix.svc and fix.loader
 * </pre>
 */
class TestServiceFixture {

    final TypeDatabaseService svc;
    final JavaTypeClassLoader loader;

    private final Map<String, Class<?>>                     classMap = new HashMap<>();
    @SuppressWarnings("rawtypes")
    private final ConcurrentHashMap<Class<?>, MapDatabaseRepository> repos = new ConcurrentHashMap<>();

    TestServiceFixture() {
        svc    = new TypeDatabaseService(new MapBackedFactory());
        loader = new StubClassLoader(classMap);
    }

    /** Register a class so that {@link #loader} can resolve it by FQN. */
    void register(Class<?> cls) {
        classMap.put(cls.getName(), cls);
    }

    /** Return the underlying repo for a registered entity class (for direct assertions). */
    @SuppressWarnings("unchecked")
    <T extends DatabaseEntity> MapDatabaseRepository<T> repoFor(Class<T> cls) {
        return (MapDatabaseRepository<T>) repos.computeIfAbsent(
                cls, k -> new MapDatabaseRepository<>((Class<DatabaseEntity>) k));
    }

    // ── MapBackedFactory ──────────────────────────────────────────────────────

    private class MapBackedFactory extends DatabaseRepositoryFactory {
        MapBackedFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T extends DatabaseEntity> DatabaseRepository<T> create(Class<T> entityClass) {
            return (DatabaseRepository<T>) repos.computeIfAbsent(
                    entityClass, k -> new MapDatabaseRepository<>((Class<DatabaseEntity>) k));
        }
    }

    // ── Stub ClassLoader ──────────────────────────────────────────────────────

    private static class StubClassLoader extends JavaTypeClassLoader {

        private final Map<String, Class<?>> map;

        StubClassLoader(Map<String, Class<?>> map) {
            // Use the package-private test constructor (parent CL, null repo)
            super(Thread.currentThread().getContextClassLoader(), null);
            this.map = map;
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            if (map.containsKey(name)) return map.get(name);
            return super.loadClass(name);
        }
    }
}
