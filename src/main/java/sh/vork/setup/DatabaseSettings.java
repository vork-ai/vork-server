package sh.vork.setup;

/**
 * Configuration settings for the database backend chosen during first-run setup.
 *
 * <p>{@code database} and {@code username} are backend-specific fields:
 * <ul>
 *   <li>MongoDB: {@code database} maps to the Mongo database name.</li>
 *   <li>Couchbase: {@code database} maps to the bucket name.</li>
 *   <li>Nitrite: {@code database} maps to the embedded file path.</li>
 *   <li>Redis: {@code database} and {@code username} are unused.</li>
 * </ul>
 */
public record DatabaseSettings(
        String backend,   // "nitrite", "mongo", "redis", or "couchbase"
        String host,
        int    port,
        String database,  // Mongo database name / Couchbase bucket / Nitrite file path
        String username,  // MongoDB / Couchbase username
        String password
) {}
