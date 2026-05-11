package sh.vork.typegen;

import sh.vork.database.DatabaseEntity;

import java.util.Map;

/**
 * MongoDB-persisted record representing a compiled Java type.
 *
 * <p>{@code uuid} is the fully-qualified name of the primary type (e.g.
 * {@code sh.vork.generated.Ticket}).  Using the FQN as the natural key means
 * re-saving the same class name performs an in-place update rather than
 * creating a duplicate.
 *
 * <p>The {@code bytecode} map holds every class file produced by a single
 * compilation unit — outer class AND inner / nested classes — keyed by their
 * FQN (inner classes use the {@code OuterClass$InnerClass} naming convention).
 * Values are Base64-encoded bytes, making the map directly Jackson-serialisable
 * with no custom serialiser.
 *
 * <p>MongoDB collection: {@code java_type}
 */
public record JavaType(
        String uuid,
        String source,
        Map<String, String> bytecode,
        long createdAt,
        long updatedAt
) implements DatabaseEntity {}
