package io.github.edlemarcio.audit.oracle.core;

import java.util.Objects;

/**
 * Identity to be recorded by database audit triggers.
 *
 * <p>The values must be obtained from an authenticated and trusted application
 * security context. This type does not authenticate or validate JWT claims.</p>
 */
public record AuditIdentity(String cpf, String name, String correlationId) {

    public AuditIdentity {
        cpf = requireText(cpf, "cpf");
        name = requireText(name, "name");
        correlationId = requireText(correlationId, "correlationId");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
