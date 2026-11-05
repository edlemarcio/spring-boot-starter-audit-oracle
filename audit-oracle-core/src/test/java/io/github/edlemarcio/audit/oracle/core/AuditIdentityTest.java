package io.github.edlemarcio.audit.oracle.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditIdentityTest {

    @Test
    void trimsIdentityFields() {
        var identity = new AuditIdentity(" 12345678901 ", " Maria Silva ", " request-123 ");

        assertEquals("12345678901", identity.cpf());
        assertEquals("Maria Silva", identity.name());
        assertEquals("request-123", identity.correlationId());
    }

    @Test
    void rejectsBlankCpf() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuditIdentity(" ", "Maria Silva", "request-123"));
    }

    @Test
    void rejectsNullName() {
        assertThrows(NullPointerException.class,
                () -> new AuditIdentity("12345678901", null, "request-123"));
    }
}
