package sh.vork.typegen;

/**
 * Thrown when a SQL-like WHERE clause string cannot be parsed by
 * {@link SqlQueryParser}.
 */
public class SqlParseException extends RuntimeException {

    public SqlParseException(String message) {
        super(message);
    }
}
