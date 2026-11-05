package io.github.edlemarcio.audit.oracle.autoconfigure.oracle;

import io.github.edlemarcio.audit.oracle.autoconfigure.AuditOracleProperties;
import io.github.edlemarcio.audit.oracle.core.AuditIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

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
        verify(connection).close();
    }
}
