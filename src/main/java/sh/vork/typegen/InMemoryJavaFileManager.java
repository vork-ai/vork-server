package sh.vork.typegen;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory {@link javax.tools.JavaFileManager} that accumulates compiled
 * bytecode in a {@code Map<className, bytes>} rather than writing to disk.
 * <p>
 * Optionally accepts previously-compiled dependency bytecode so that the
 * compiler can resolve types that were generated in an earlier
 * {@code TypeGeneratorService.compile()} call.
 * <p>
 * Also exposes a factory for in-memory source file objects so the compiler
 * never touches the filesystem at all.
 */
class InMemoryJavaFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

    private final Map<String, byte[]> outputBytecodeMap = new HashMap<>();
    private final Map<String, JavaFileObject> dependencyClassFiles;

    InMemoryJavaFileManager(StandardJavaFileManager delegate) {
        this(delegate, Map.of());
    }

    InMemoryJavaFileManager(StandardJavaFileManager delegate, Map<String, byte[]> dependencyBytecode) {
        super(delegate);
        this.dependencyClassFiles = new HashMap<>();
        dependencyBytecode.forEach((fqn, bytes) ->
                dependencyClassFiles.put(fqn, new InputClassFileObject(fqn, bytes)));
    }

    // ── Output: compiled .class bytes ────────────────────────────────────────

    @Override
    public JavaFileObject getJavaFileForOutput(Location location,
                                               String className,
                                               JavaFileObject.Kind kind,
                                               FileObject sibling) {
        return new OutputClassFileObject(className);
    }

    Map<String, byte[]> getBytecodeMap() {
        return Map.copyOf(outputBytecodeMap);
    }

    // ── Input: serve dependency bytecode to the compiler ─────────────────────

    @Override
    public Iterable<JavaFileObject> list(Location location, String packageName,
                                        Set<JavaFileObject.Kind> kinds, boolean recurse)
            throws IOException {

        Iterable<JavaFileObject> standard = super.list(location, packageName, kinds, recurse);

        if (location != StandardLocation.CLASS_PATH || !kinds.contains(JavaFileObject.Kind.CLASS)
                || dependencyClassFiles.isEmpty()) {
            return standard;
        }

        List<JavaFileObject> combined = new ArrayList<>();
        standard.forEach(combined::add);

        for (Map.Entry<String, JavaFileObject> entry : dependencyClassFiles.entrySet()) {
            String fqn = entry.getKey();
            String pkg  = fqn.contains(".") ? fqn.substring(0, fqn.lastIndexOf('.')) : "";
            if (pkg.equals(packageName) || (recurse && fqn.startsWith(packageName + "."))) {
                combined.add(entry.getValue());
            }
        }
        return combined;
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        if (file instanceof InputClassFileObject icfo) {
            return icfo.binaryName;
        }
        return super.inferBinaryName(location, file);
    }

    // ── Inner: accumulates .class bytes for one output type ──────────────────

    private class OutputClassFileObject extends SimpleJavaFileObject {

        private final String className;

        OutputClassFileObject(String className) {
            super(URI.create("mem:///" + className.replace('.', '/') + Kind.CLASS.extension),
                  Kind.CLASS);
            this.className = className;
        }

        @Override
        public OutputStream openOutputStream() {
            return new ByteArrayOutputStream() {
                @Override
                public void close() {
                    outputBytecodeMap.put(className, toByteArray());
                }
            };
        }
    }

    // ── Inner: serves previously-compiled class bytes to the compiler ─────────

    private static class InputClassFileObject extends SimpleJavaFileObject {

        final String binaryName;
        final byte[] bytes;

        InputClassFileObject(String binaryName, byte[] bytes) {
            super(URI.create("mem:///" + binaryName.replace('.', '/') + Kind.CLASS.extension),
                  Kind.CLASS);
            this.binaryName = binaryName;
            this.bytes      = bytes;
        }

        @Override
        public InputStream openInputStream() {
            return new ByteArrayInputStream(bytes);
        }
    }

    // ── Static factory: in-memory source file ─────────────────────────────────

    static JavaFileObject sourceFileObject(String fullyQualifiedName, String source) {
        return new SimpleJavaFileObject(
                URI.create("mem:///" + fullyQualifiedName.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
    }
}
