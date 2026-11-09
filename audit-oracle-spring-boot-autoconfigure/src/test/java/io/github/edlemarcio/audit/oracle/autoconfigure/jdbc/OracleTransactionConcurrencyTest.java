package io.github.edlemarcio.audit.oracle.autoconfigure.jdbc;

import io.github.edlemarcio.audit.oracle.core.AuditContext;
import io.github.edlemarcio.audit.oracle.testsupport.ConcurrentWork;
import io.github.edlemarcio.audit.oracle.testsupport.OracleScenario;
import io.github.edlemarcio.audit.oracle.testsupport.ScenarioReport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.github.edlemarcio.audit.oracle.testsupport.OracleScenario.*;
import static org.assertj.core.api.Assertions.*;

@Tag("oracle-concurrency")
@ExtendWith(ScenarioReport.class)
class OracleTransactionConcurrencyTest {
    @ParameterizedTest
    @CsvSource({"0,false", "0,true", "1,false", "1,true", "2,false", "2,true", "3,false", "3,true",
            "4,false", "4,true", "5,false", "5,true", "6,false", "6,true"})
    void exercisesAllSpringPropagationModesWithAndWithoutAnOuterTransaction(int propagation, boolean outer) throws Exception {
        try (var scenario = new OracleScenario(2)) {
            var manager = new DataSourceTransactionManager(scenario.pool);
            var inner = new TransactionTemplate(manager); inner.setPropagationBehavior(propagation);
            boolean rejected = (!outer && propagation == TransactionDefinition.PROPAGATION_MANDATORY)
                    || (outer && propagation == TransactionDefinition.PROPAGATION_NEVER);
            boolean joined = outer && (propagation == TransactionDefinition.PROPAGATION_REQUIRED
                    || propagation == TransactionDefinition.PROPAGATION_SUPPORTS
                    || propagation == TransactionDefinition.PROPAGATION_MANDATORY
                    || propagation == TransactionDefinition.PROPAGATION_NESTED);
            Runnable innerWork = () -> {
                try {
                    scenario.as(BRUNO, () -> inner.execute(status -> { write(scenario, joined ? ANA : BRUNO); return null; }));
                } catch (Exception failure) { throw new RuntimeException(failure); }
            };
            scenario.as(ANA, () -> {
                if (outer) {
                    new TransactionTemplate(manager).executeWithoutResult(status -> {
                        write(scenario, ANA);
                        if (rejected) assertThatThrownBy(innerWork::run).hasRootCauseInstanceOf(IllegalTransactionStateException.class);
                        else innerWork.run();
                        write(scenario, ANA);
                    });
                } else if (rejected) assertThatThrownBy(innerWork::run).hasRootCauseInstanceOf(IllegalTransactionStateException.class);
                else innerWork.run();
                return null;
            });
            scenario.reconcile();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void requiresNewHasIndependentCommitRollbackAndAuthor(boolean rollbackOuter, boolean rollbackInner) throws Exception {
        try (var scenario = new OracleScenario(2)) {
            var manager = new DataSourceTransactionManager(scenario.pool);
            var inner = new TransactionTemplate(manager);
            inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            scenario.as(ANA, () -> new TransactionTemplate(manager).execute(outerStatus -> {
                write(scenario, ANA, rollbackOuter);
                try {
                    scenario.as(BRUNO, () -> inner.execute(innerStatus -> {
                        write(scenario, BRUNO, rollbackInner);
                        if (rollbackInner) innerStatus.setRollbackOnly();
                        return null;
                    }));
                } catch (Exception error) { throw new RuntimeException(error); }
                write(scenario, ANA, rollbackOuter);
                if (rollbackOuter) outerStatus.setRollbackOnly();
                return null;
            }));
            scenario.reconcile();
        }
    }

    @Test
    void aNestedSavepointRollbackPreservesTheOuterOwnerAndWork() throws Exception {
        try (var scenario = new OracleScenario(1)) {
            var manager = new DataSourceTransactionManager(scenario.pool);
            var nested = new TransactionTemplate(manager); nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
            scenario.as(ANA, () -> new TransactionTemplate(manager).execute(status -> {
                write(scenario, ANA);
                nested.executeWithoutResult(inner -> { write(scenario, ANA, true); inner.setRollbackOnly(); });
                write(scenario, ANA);
                return null;
            }));
            scenario.reconcile();
        }
    }

    @Test
    void aRequiredInnerRollbackDoesNotLeaveAnOuterAuditRecord() throws Exception {
        try (var scenario = new OracleScenario(1)) {
            var manager = new DataSourceTransactionManager(scenario.pool);
            assertThatThrownBy(() -> scenario.as(ANA, () -> new TransactionTemplate(manager).execute(status -> {
                write(scenario, ANA, true);
                new TransactionTemplate(manager).executeWithoutResult(inner -> { write(scenario, ANA, true); inner.setRollbackOnly(); });
                return null;
            }))).isInstanceOf(UnexpectedRollbackException.class);
            scenario.reconcile();
        }
    }

    @Test
    void requiresNewCapacityTimeoutDoesNotOverwriteTheSuspendedConnection() throws Exception {
        try (var scenario = new OracleScenario(1)) {
            var manager = new DataSourceTransactionManager(scenario.pool);
            var inner = new TransactionTemplate(manager); inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            scenario.as(ANA, () -> new TransactionTemplate(manager).execute(status -> {
                write(scenario, ANA);
                assertThatThrownBy(() -> inner.execute(innerStatus -> null)).isInstanceOf(org.springframework.transaction.CannotCreateTransactionException.class);
                write(scenario, ANA);
                return null;
            }));
            scenario.reconcile();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_SERIALIZABLE})
    void bothOracleIsolationLevelsKeepConcurrentAuthorsIndependent(int isolation) throws Exception {
        try (var scenario = new OracleScenario(2); var work = new ConcurrentWork(8)) {
            scenario.pool.setConnectionTimeout(10_000);
            var manager = new DataSourceTransactionManager(scenario.pool);
            for (int i = 0; i < 24; i++) {
                AuditContext identity = i % 2 == 0 ? ANA : BRUNO;
                work.submit(() -> {
                    try {
                        return scenario.as(identity, () -> {
                            var template = new TransactionTemplate(manager); template.setIsolationLevel(isolation);
                            return template.execute(status -> { write(scenario, identity); return null; });
                        });
                    } catch (RuntimeException failure) {
                        Throwable cause = failure;
                        boolean serializationRejection = false;
                        while (cause != null) {
                            if (cause instanceof SQLException sql && sql.getErrorCode() == 8177) serializationRejection = true;
                            cause = cause.getCause();
                        }
                        if (isolation != Connection.TRANSACTION_SERIALIZABLE || !serializationRejection) throw failure;
                        scenario.report.count("expectedSerializationRejections");
                        return null;
                    }
                });
            }
            work.join(Duration.ofSeconds(60)); scenario.reconcile();
        }
    }

    @Test
    void constraintFailureRollsBackPartialWorkBeforeTheNextBorrower() throws Exception {
        try (var scenario = new OracleScenario(1)) {
            String existing = UUID.randomUUID().toString();
            try (var connection = scenario.borrow(ANA)) { scenario.insert(connection, existing, existing, ANA); scenario.marker(connection, existing); }
            try (var connection = scenario.borrow(BRUNO)) {
                connection.setAutoCommit(false);
                String partial = UUID.randomUUID().toString();
                scenario.expectRollback(partial);
                scenario.insert(connection, partial, partial, BRUNO); scenario.marker(connection, partial);
                try (var statement = connection.prepareStatement("INSERT INTO " + scenario.target + " (ID,VERSION) VALUES (?,1)")) {
                    statement.setString(1, existing);
                    assertThatThrownBy(statement::executeUpdate).isInstanceOf(SQLException.class);
                }
                // Pending partial work is deliberately left for audited close to roll back.
            }
            try (var connection = scenario.borrow(ANA)) { scenario.operation(connection, ANA, false, false); }
            scenario.reconcile();
        }
    }

    @Test
    void commandTimeoutUnderRowContentionIsCleanedBeforeReuse() throws Exception {
        try (var scenario = new OracleScenario(2); var work = new ConcurrentWork(1)) {
            String existing = UUID.randomUUID().toString();
            try (var initial = scenario.borrow(ANA)) { scenario.insert(initial, existing, existing, ANA); scenario.marker(initial, existing); }
            try (var held = scenario.borrow(ANA)) {
                held.setAutoCommit(false);
                try (var statement = held.prepareStatement("UPDATE " + scenario.target + " SET PAYLOAD='locked' WHERE ID=?")) {
                    statement.setString(1, existing); statement.executeUpdate();
                }
                work.submit(() -> {
                    try (var waiting = scenario.borrow(BRUNO)) {
                        waiting.setAutoCommit(false);
                        try (var statement = waiting.prepareStatement("SELECT ID FROM " + scenario.target + " WHERE ID=? FOR UPDATE WAIT 1")) {
                            statement.setString(1, existing); statement.setQueryTimeout(5);
                            assertThatThrownBy(statement::executeQuery).isInstanceOf(SQLException.class)
                                    .satisfies(error -> assertThat(((SQLException) error).getErrorCode()).isIn(30006, 1013, 54));
                        }
                    }
                    return null;
                }).get(10, TimeUnit.SECONDS);
                held.rollback();
            }
            try (var next = scenario.borrow(BRUNO)) { scenario.operation(next, BRUNO, false, false); }
            work.join(Duration.ofSeconds(10)); scenario.reconcile();
        }
    }

    private static void write(OracleScenario scenario, AuditContext expectedIdentity) {
        write(scenario, expectedIdentity, false);
    }

    private static void write(OracleScenario scenario, AuditContext expectedIdentity, boolean rollback) {
        Connection connection = DataSourceUtils.getConnection(scenario.pool);
        String id = UUID.randomUUID().toString();
        if (rollback) scenario.expectRollback(id);
        try {
            scenario.observe(connection, expectedIdentity);
            scenario.insert(connection, id, id, expectedIdentity); scenario.marker(connection, id);
        } catch (SQLException failure) { scenario.expectRollback(id); throw new RuntimeException(failure); }
        finally { DataSourceUtils.releaseConnection(connection, scenario.pool); }
    }
}
