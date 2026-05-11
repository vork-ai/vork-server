package sh.vork.database;

/**
 * Thrown when serialisation to / deserialisation from the database fails,
 * or when a low-level database operation encounters an unrecoverable error.
 */
public class DatabaseException extends RuntimeException {

    public DatabaseException(String message) {
        super(message);
    }

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
