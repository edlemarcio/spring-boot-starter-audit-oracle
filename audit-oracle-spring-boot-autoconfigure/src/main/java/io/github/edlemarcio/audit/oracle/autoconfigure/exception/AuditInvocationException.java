package io.github.edlemarcio.audit.oracle.autoconfigure.exception;

public class AuditInvocationException extends RuntimeException {

    public AuditInvocationException(Throwable cause) {
        super("Audited invocation failed", cause);
    }
}
