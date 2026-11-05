package io.github.edlemarcio.audit.oracle.autoconfigure.exception;

public class AuditContextException extends RuntimeException {

    public AuditContextException(String message) {
        super(message);
    }

    public AuditContextException(String message, Throwable cause) {
        super(message, cause);
    }
}
