package io.github.edlemarcio.audit.oracle.autoconfigure.oracle;

import io.github.edlemarcio.audit.oracle.autoconfigure.AuditOracleProperties;
import io.github.edlemarcio.audit.oracle.core.AuditIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OracleAuditContextTransactionOrderTest {

    @Test
    void clearsContextAfterCommitAndBeforeConnectionRelease() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        CallableStatement callableStatement = mock(CallableStatement.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(anyInt())).thenReturn(null);
        when(connection.prepareCall(anyString())).thenReturn(callableStatement);

        var manager = new OracleAuditContextManager(dataSource, new AuditOracleProperties());
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transaction.execute(status -> {
            try (OracleAuditContextManager.Scope ignored = manager.open(
                    new AuditIdentity("12345678901", "Maria Silva", "req-commit"))) {
                // The service's JPA/JDBC work would execute while the context is active.
            }
            return null;
        });

        var order = inOrder(callableStatement, connection);
        order.verify(callableStatement).execute(); // SET_CONTEXT
        order.verify(connection).commit();
        order.verify(callableStatement).execute(); // CLEAR_CONTEXT in afterCompletion
        order.verify(connection).close();
    }
    @Test
    void keepsOuterAndRequiresNewContextsOnSeparateConnections() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection outerConnection = mockConnection();
        Connection innerConnection = mockConnection();
        when(dataSource.getConnection()).thenReturn(outerConnection, innerConnection);

        CallableStatement outerCalls = mock(CallableStatement.class);
        CallableStatement innerCalls = mock(CallableStatement.class);
        when(outerConnection.prepareCall(anyString())).thenReturn(outerCalls);
        when(innerConnection.prepareCall(anyString())).thenReturn(innerCalls);

        var manager = new OracleAuditContextManager(dataSource, new AuditOracleProperties());
        var outerTransaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var innerTransaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        innerTransaction.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outerTransaction.execute(outerStatus -> {
            try (OracleAuditContextManager.Scope outerScope = manager.open(
                    new AuditIdentity("12345678901", "Outer User", "outer-request"))) {
                innerTransaction.execute(innerStatus -> {
                    try (OracleAuditContextManager.Scope innerScope = manager.open(
                            new AuditIdentity("10987654321", "Inner User", "inner-request"))) {
                        // Inner DML uses the independent REQUIRES_NEW connection.
                    }
                    return null;
                });
            }
            return null;
        });

        verify(outerCalls, times(2)).execute();
        verify(innerCalls, times(2)).execute();
        verify(outerConnection).commit();
        verify(innerConnection).commit();
        verify(outerConnection).close();
        verify(innerConnection).close();
    }


    @Test
    void isolatesTwoConcurrentUserContextsOnDifferentConnections() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection firstConnection = mockConnection();
        Connection secondConnection = mockConnection();
        CallableStatement firstCalls = mock(CallableStatement.class);
        CallableStatement secondCalls = mock(CallableStatement.class);
        when(firstConnection.prepareCall(anyString())).thenReturn(firstCalls);
        when(secondConnection.prepareCall(anyString())).thenReturn(secondCalls);

        var manager = new OracleAuditContextManager(dataSource, new AuditOracleProperties());
        var ready = new CountDownLatch(2);
        var continueWork = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> runConcurrentTransaction(
                    dataSource, manager, firstConnection,
                    new AuditIdentity("12345678901", "First User", "request-first"),
                    ready, continueWork));
            Future<?> second = executor.submit(() -> runConcurrentTransaction(
                    dataSource, manager, secondConnection,
                    new AuditIdentity("10987654321", "Second User", "request-second"),
                    ready, continueWork));

            ready.await();
            continueWork.countDown();
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        verify(firstCalls).setString(1, "12345678901");
        verify(firstCalls).setString(2, "First User");
        verify(firstCalls).setString(3, "request-first");
        verify(secondCalls).setString(1, "10987654321");
        verify(secondCalls).setString(2, "Second User");
        verify(secondCalls).setString(3, "request-second");
        verify(firstCalls, times(2)).execute();
        verify(secondCalls, times(2)).execute();
    }

    private void runConcurrentTransaction(
            DataSource dataSource,
            OracleAuditContextManager manager,
            Connection connection,
            AuditIdentity identity,
            CountDownLatch ready,
            CountDownLatch continueWork
    ) {
        TransactionSynchronizationManager.bindResource(dataSource, new ConnectionHolder(connection));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            try (OracleAuditContextManager.Scope ignored = manager.open(identity)) {
                ready.countDown();
                continueWork.await();
            }
            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
            if (TransactionSynchronizationManager.hasResource(dataSource)) {
                TransactionSynchronizationManager.unbindResource(dataSource);
            }
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private Connection mockConnection() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(anyInt())).thenReturn(null);
        return connection;
    }

}
