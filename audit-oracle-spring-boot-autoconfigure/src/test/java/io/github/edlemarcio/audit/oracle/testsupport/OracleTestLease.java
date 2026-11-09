package io.github.edlemarcio.audit.oracle.testsupport;

import java.sql.Connection;
import java.sql.SQLException;

/** Session-held row lock protects shared example tables across processes/hosts. */
public final class OracleTestLease implements AutoCloseable {
    private final Connection connection;

    public OracleTestLease(OracleEnvironment environment) throws SQLException {
        connection = environment.connect();
        try {
            try (var statement = connection.createStatement()) {
                try { statement.execute("CREATE TABLE AC_TEST_LEASE (ID NUMBER PRIMARY KEY, PURPOSE VARCHAR2(100))"); }
                catch (SQLException existing) { if (existing.getErrorCode() != 955) throw existing; }
                statement.executeUpdate("MERGE INTO AC_TEST_LEASE t USING (SELECT 1 ID FROM DUAL) s ON (t.ID=s.ID) "
                        + "WHEN NOT MATCHED THEN INSERT (ID,PURPOSE) VALUES (1,'audit-concurrency-test-schema')");
                connection.setAutoCommit(false);
                try (var result = statement.executeQuery("SELECT PURPOSE FROM AC_TEST_LEASE WHERE ID=1 FOR UPDATE NOWAIT")) {
                    if (!result.next() || !"audit-concurrency-test-schema".equals(result.getString(1)))
                        throw new SQLException("Schema lease marker is invalid");
                }
            }
        } catch (Throwable failure) { connection.close(); throw failure; }
    }

    @Override public void close() throws SQLException {
        try { connection.rollback(); } finally { connection.close(); }
    }
}
