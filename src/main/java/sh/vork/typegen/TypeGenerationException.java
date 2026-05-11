package sh.vork.typegen;

public class TypeGenerationException extends RuntimeException {

    public TypeGenerationException(String message) {
        super(message);
    }

    public TypeGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
