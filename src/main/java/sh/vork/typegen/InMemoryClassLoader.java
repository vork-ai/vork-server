package sh.vork.typegen;

import java.util.Map;

/**
 * Loads classes from in-memory bytecode produced by {@link InMemoryJavaFileManager}.
 * <p>
 * Delegates all standard class lookups to the parent (the current thread's
 * context class loader), so compiled types can reference Spring, project, and
 * JDK classes freely.  Only types whose bytecode is held in {@code bytecodeMap}
 * are defined by this loader — all other classes are resolved through the parent.
 */
class InMemoryClassLoader extends ClassLoader {

    private final Map<String, byte[]> bytecodeMap;

    InMemoryClassLoader(ClassLoader parent, Map<String, byte[]> bytecodeMap) {
        super(parent);
        this.bytecodeMap = Map.copyOf(bytecodeMap);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = bytecodeMap.get(name);
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        return defineClass(name, bytes, 0, bytes.length);
    }
}
