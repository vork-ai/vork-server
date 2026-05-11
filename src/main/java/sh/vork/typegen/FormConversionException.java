package sh.vork.typegen;

/**
 * Thrown when {@link FormToObjectConverter} cannot convert form parameters
 * into the target record type.
 */
public class FormConversionException extends RuntimeException {

    public FormConversionException(String message) {
        super(message);
    }

    public FormConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
