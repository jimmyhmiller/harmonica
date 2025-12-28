package com.jsparser.json;

/**
 * Exception thrown when JSON serialization or deserialization fails.
 */
public class AstJsonException extends RuntimeException {

    public AstJsonException(String message) {
        super(message);
    }

    public AstJsonException(String message, Throwable cause) {
        super(message, cause);
    }

    public AstJsonException(Throwable cause) {
        super(cause);
    }
}
