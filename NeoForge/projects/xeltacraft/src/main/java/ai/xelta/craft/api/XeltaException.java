package ai.xelta.craft.api;

/** Base exception for all Xelta API errors. */
public class XeltaException extends RuntimeException {
    public XeltaException(String message) { super(message); }
    public XeltaException(String message, Throwable cause) { super(message, cause); }
}
