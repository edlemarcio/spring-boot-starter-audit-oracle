package io.github.edlemarcio.audit.oracle.autoconfigure.jdbc;

import io.github.edlemarcio.audit.oracle.core.AuditContext;
import io.github.edlemarcio.audit.oracle.core.AuditContextProvider;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

public final class AuditDataSource extends HikariDataSource {

    private final AuditContextProvider contextProvider;

    public AuditDataSource(final AuditContextProvider contextProvider) {
        this.contextProvider = Objects.requireNonNull(contextProvider);
    }

    @Override
    public Connection getConnection() throws SQLException {
        final AuditContext context = Objects.requireNonNull(contextProvider.getContext());
        return AuditConnection.configure(super.getConnection(), context, this::evictConnection);
    }

    @Override
    public Connection getConnection(final String username, final String password) throws SQLException {
        final AuditContext context = Objects.requireNonNull(contextProvider.getContext());
        return AuditConnection.configure(super.getConnection(username, password), context, this::evictConnection);
    }
}
