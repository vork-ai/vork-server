package sh.vork.database;

/**
 * Base interface for all Vork database entities.
 *
 * <p>Every entity record must implement this interface and expose a stable,
 * globally-unique identifier via {@link #uuid()}. This value is used as the
 * MongoDB {@code _id} and as the key in the in-memory mock store.
 *
 * <p>Implementing records <strong>must</strong> declare a field named
 * {@code uuid} of type {@code String} so that Jackson can round-trip the
 * value through JSON serialisation:
 *
 * <pre>{@code
 * public record Product(String uuid, String name) implements DatabaseEntity {}
 * }</pre>
 */
public interface DatabaseEntity {
    String uuid();
}
