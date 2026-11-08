package io.github.edlemarcio.audit.oracle.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditContextTest {

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "192.0.2.25", "::1", "2001:db8:abcd:1234:5678:abcd:1234:5678", "::ffff:192.0.2.25"})
    void devePreservarIpCompleto(final String ip) {
        assertEquals(ip, new AuditContext("user", ip, "condenacoes-interno").ip());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "example.org", "999.0.0.1", "192.0.2.1:8080", "192.0.2.1, 192.0.2.2", "abc:def", "::1%eth0"})
    void deveRejeitarIpInvalidoSemResolverNomes(final String ip) {
        assertThrows(IllegalArgumentException.class, () -> new AuditContext("user", ip, "application"));
    }

    @Test
    void deveValidarLimitesEmBytesSemTruncarIdentidade() {
        assertEquals(32, new AuditContext("á".repeat(32), "::1", "a".repeat(48)).user().length());
        assertThrows(IllegalArgumentException.class, () -> new AuditContext("á".repeat(33), "::1", "app"));
        assertThrows(IllegalArgumentException.class, () -> new AuditContext("u", "::1", "á".repeat(25)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "user\nforjado"})
    void deveRejeitarIdentidadeAusenteOuComControle(final String user) {
        assertThrows(IllegalArgumentException.class, () -> new AuditContext(user, "::1", "app"));
    }
}
