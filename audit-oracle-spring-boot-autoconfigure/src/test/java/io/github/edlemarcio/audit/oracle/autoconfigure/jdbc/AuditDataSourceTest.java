package io.github.edlemarcio.audit.oracle.autoconfigure.jdbc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditDataSourceTest {

    @Test
    void rejectsMissingIdentityBeforeBorrowingAnyConnection() {
        try (var dataSource = new AuditDataSource(() -> {
            throw new IllegalStateException("Unauthenticated request");
        })) {
            assertThatThrownBy(dataSource::getConnection).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> dataSource.getConnection("user", "password"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(dataSource.getHikariPoolMXBean()).isNull();
        }
    }

    @Test
    void rejectsNullContextBeforeBorrowingAnyConnection() {
        try (var dataSource = new AuditDataSource(() -> null)) {
            assertThatThrownBy(dataSource::getConnection).isInstanceOf(NullPointerException.class);
            assertThat(dataSource.getHikariPoolMXBean()).isNull();
        }
    }
}
