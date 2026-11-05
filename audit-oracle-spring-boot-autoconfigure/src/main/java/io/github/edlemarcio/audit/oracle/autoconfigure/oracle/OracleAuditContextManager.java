package io.github.edlemarcio.audit.oracle.autoconfigure.oracle;

import com.zaxxer.hikari.HikariDataSource;
import io.github.edlemarcio.audit.oracle.autoconfigure.AuditOracleProperties;
import io.github.edlemarcio.audit.oracle.autoconfigure.exception.AuditContextException;
import io.github.edlemarcio.audit.oracle.core.AuditIdentity;
import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Manages Oracle application context on the transaction-bound JDBC connection.
 *
 * <p>State is keyed by the actual JDBC Connection object, not by a thread. This
 * allows a suspended outer transaction and a REQUIRES_NEW transaction to keep
 * independent context state.</p>
 */
public class OracleAuditContextManager {

    private final DataSource dataSource;
    private final AuditOracleProperties properties;
    private final Map<Connection, ContextState> activeContexts = new IdentityHashMap<>();

    public OracleAuditContextManager(DataSource dataSource, AuditOracleProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
        validateSqlIdentifiers(properties);
    }

    public Scope open(AuditIdentity identity) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            synchronized (activeContexts) {
                ContextState existing = activeContexts.get(connection);
                if (existing != null) {
                    if (!existing.identity().equals(identity)) {
                        throw new AuditContextException(
                                "A different audit identity is already associated with this transaction connection");
                    }
                    existing.increment();
                    return new Scope(this, connection);
                }

                assertDatabaseContextIsEmpty(connection);
                setContext(connection, identity);
                activeContexts.put(connection, new ContextState(identity));
                return new Scope(this, connection);
            }
        } catch (SQLException exception) {
            invalidate(connection);
            DataSourceUtils.releaseConnection(connection, dataSource);
            throw new AuditContextException(
                    "Could not inspect or initialize Oracle audit context; connection was invalidated", exception);
        } catch (RuntimeException exception) {
            DataSourceUtils.releaseConnection(connection, dataSource);
            throw exception;
        }
    }

    private void close(Connection connection) {
        try {
            synchronized (activeContexts) {
                ContextState state = activeContexts.get(connection);
                if (state == null) {
                    invalidate(connection);
                    throw new AuditContextException(
                            "Audit context scope is not registered; connection was invalidated");
                }
                if (state.decrement() > 0) {
                    return;
                }

                try {
                    clearContext(connection);
                    activeContexts.remove(connection);
                } catch (SQLException clearFailure) {
                    activeContexts.remove(connection);
                    try {
                        invalidate(connection);
                    } catch (AuditContextException invalidationFailure) {
                        clearFailure.addSuppressed(invalidationFailure);
                    }
                    throw new AuditContextException(
                            "Could not clear Oracle audit context; connection invalidation was attempted", clearFailure);
                }
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private void assertDatabaseContextIsEmpty(Connection connection) throws SQLException {
        String namespace = properties.getContextNamespace();
        String sql = "SELECT SYS_CONTEXT('" + namespace + "','CPF'), "
                + "SYS_CONTEXT('" + namespace + "','NOME'), "
                + "SYS_CONTEXT('" + namespace + "','REQUEST_ID') FROM DUAL";
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new SQLException("Could not inspect Oracle audit context");
            }
            if (resultSet.getString(1) != null || resultSet.getString(2) != null
                    || resultSet.getString(3) != null) {
                invalidate(connection);
                throw new AuditContextException(
                        "Oracle session contains an unexpected audit context; connection was invalidated");
            }
        }
    }

    private void setContext(Connection connection, AuditIdentity identity) throws SQLException {
        String sql = "{ call " + properties.getSetContextProcedure() + "(?, ?, ?) }";
        try (CallableStatement statement = connection.prepareCall(sql)) {
            statement.setString(1, identity.cpf());
            statement.setString(2, identity.name());
            statement.setString(3, identity.correlationId());
            statement.execute();
        } catch (SQLException failure) {
            try {
                clearContext(connection);
            } catch (SQLException clearFailure) {
                failure.addSuppressed(clearFailure);
                try {
                    invalidate(connection);
                } catch (AuditContextException invalidationFailure) {
                    failure.addSuppressed(invalidationFailure);
                }
            }
            throw failure;
        }
    }

    private void clearContext(Connection connection) throws SQLException {
        String sql = "{ call " + properties.getClearContextProcedure() + " }";
        try (CallableStatement statement = connection.prepareCall(sql)) {
            statement.execute();
        }
    }

    private void invalidate(Connection connection) {
        try {
            if (dataSource instanceof HikariDataSource hikariDataSource) {
                hikariDataSource.evictConnection(connection);
            } else {
                connection.abort(Runnable::run);
            }
        } catch (Exception firstFailure) {
            try {
                connection.abort(Runnable::run);
            } catch (Exception abortFailure) {
                firstFailure.addSuppressed(abortFailure);
                throw new AuditContextException(
                        "Could not invalidate a JDBC connection with an unknown Oracle audit context",
                        firstFailure);
            }
        }
    }

    private static void validateSqlIdentifiers(AuditOracleProperties properties) {
        if (!properties.getContextNamespace().matches("[A-Za-z][A-Za-z0-9_$#]{0,127}")) {
            throw new IllegalArgumentException("Invalid Oracle context namespace");
        }
        String procedurePattern = "[A-Za-z][A-Za-z0-9_$#]{0,127}(\\.[A-Za-z][A-Za-z0-9_$#]{0,127})?";
        if (!properties.getSetContextProcedure().matches(procedurePattern)
                || !properties.getClearContextProcedure().matches(procedurePattern)) {
            throw new IllegalArgumentException("Invalid Oracle package procedure identifier");
        }
    }

    private static final class ContextState {
        private final AuditIdentity identity;
        private int scopes = 1;

        private ContextState(AuditIdentity identity) {
            this.identity = identity;
        }

        private AuditIdentity identity() {
            return identity;
        }

        private void increment() {
            scopes++;
        }

        private int decrement() {
            return --scopes;
        }
    }

    public static final class Scope implements AutoCloseable {
        private final OracleAuditContextManager manager;
        private final Connection connection;
        private boolean closed;

        private Scope(OracleAuditContextManager manager, Connection connection) {
            this.manager = manager;
            this.connection = connection;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                manager.close(connection);
            }
        }
    }
}
