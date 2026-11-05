package io.github.edlemarcio.audit.oracle.autoconfigure.oracle;

import io.github.edlemarcio.audit.oracle.autoconfigure.AuditOracleProperties;
import io.github.edlemarcio.audit.oracle.autoconfigure.exception.AuditContextException;
import io.github.edlemarcio.audit.oracle.core.AuditIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class OracleAuditContextManagerTest {

    private DataSource dataSource;
    private Connection connection;
    private CallableStatement callableStatement;
    private OracleAuditContextManager manager;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        callableStatement = mock(CallableStatement.class);

        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(anyInt())).thenReturn(null);
        when(connection.prepareCall(anyString())).thenReturn(callableStatement);

        AuditOracleProperties properties = new AuditOracleProperties();
        manager = new OracleAuditContextManager(dataSource, properties);

        TransactionSynchronizationManager.bindResource(dataSource, new ConnectionHolder(connection));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        if (TransactionSynchronizationManager.hasResource(dataSource)) {
            TransactionSynchronizationManager.unbindResource(dataSource);
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void nestedScopesOnSameConnectionSetAndClearOnlyOnce() {
        AuditIdentity identity = new AuditIdentity("12345678901", "Maria Silva", "req-1");

        OracleAuditContextManager.Scope outer = manager.open(identity);
        OracleAuditContextManager.Scope inner = manager.open(identity);

        inner.close();
        verify(callableStatement, times(1)).execute();

        outer.close();
        verify(callableStatement, times(1)).execute();

        completeTransaction();
        verify(callableStatement, times(2)).execute();
    }

    private void completeTransaction() {
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(
                        org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED));
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void invalidatesConnectionWhenDatabaseSessionContainsUnexpectedContext() throws Exception {
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("12345678901");
        when(resultSet.getString(2)).thenReturn("Previous User");
        when(resultSet.getString(3)).thenReturn("previous-request");

        assertThrows(AuditContextException.class, () -> manager.open(
                new AuditIdentity("12345678901", "Maria Silva", "req-1")));

        verify(connection).abort(any(java.util.concurrent.Executor.class));
    }

    @Test
    void rejectsDifferentIdentityInsideSameTransactionConnection() {
        OracleAuditContextManager.Scope outer = manager.open(
                new AuditIdentity("12345678901", "Maria Silva", "req-1"));

        assertThrows(AuditContextException.class, () -> manager.open(
                new AuditIdentity("10987654321", "Joao Souza", "req-2")));

        outer.close();
        completeTransaction();
    }
}
