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

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.edlemarcio.audit.oracle.testsupport.OracleScenario.*;
import static org.assertj.core.api.Assertions.*;

@Tag("oracle-concurrency")
@ExtendWith(ScenarioReport.class)
class OracleConcurrencyTest {
    @Test
    void cleansStaleSessionOnCheckoutBeforeApplyingTheNextIdentity() throws Exception {
        try (var scenario = new OracleScenario(1)) {
            oracle.jdbc.OracleConnection physical;
            long generation;
            try (var first = scenario.borrow(ANA)) {
                physical = first.unwrap(oracle.jdbc.OracleConnection.class);
                generation = scenario.tracking.generation(first);
            }
            // Seed stale metadata only while the sole physical session is idle and no workers exist.
            try (var statement = physical.prepareCall(AuditConnection.SET_CONTEXT)) {
                statement.setString(1, ANA.user());
                statement.setString(2, ANA.ip());
                statement.setString(3, ANA.application());
                statement.execute();
            }
            var stale = scenario.tracking.idleSnapshot(generation);
            assertThat(stale).containsEntry("user", ANA.user()).containsEntry("ip", ANA.ip())
                    .containsEntry("application", ANA.application());
            scenario.report.detail("seededCheckoutState", stale);
            long cleanupsBeforeCheckout = scenario.tracking.cleanups();
            try (var next = scenario.borrow(BRUNO)) {
                assertThat(scenario.tracking.generation(next)).isEqualTo(generation);
                assertThat(scenario.tracking.cleanups()).as("native cleanup observed before the next owner uses the session")
                        .isEqualTo(cleanupsBeforeCheckout + 1);
                scenario.operation(next, BRUNO, false, false);
            }
            assertThat(scenario.tracking.cleanups()).isEqualTo(cleanupsBeforeCheckout + 2);
            scenario.assertIdleClean(generation);
            scenario.reconcile();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void forcesManyOwnersOntoOnePhysicalSession(boolean autocommit) throws Exception {
        try (var scenario = new OracleScenario(1)) {
            long generation = -1;
            var sameUserOtherMetadata = new AuditContext(ANA.user(), "192.0.2.33", "other-app");
            for (AuditContext identity : List.of(ANA, BRUNO, ANA, SYSTEM, sameUserOtherMetadata, BRUNO)) {
                try (var connection = scenario.borrow(identity)) {
                    long current = scenario.tracking.generation(connection);
                    if (generation != -1) assertThat(current).isEqualTo(generation);
                    generation = current;
                    scenario.operation(connection, identity, autocommit, false);
                }
                scenario.assertIdleClean(generation);
            }
            scenario.reconcile();
        }
    }

    @ParameterizedTest
    @CsvSource({"1,1", "1,8", "2,2", "2,16", "5,5", "5,32", "10,10", "10,32"})
    void oversubscribesThePoolAndReusesExecutorThreads(int poolSize, int workers) throws Exception {
        try (var scenario = new OracleScenario(poolSize); var work = new ConcurrentWork(workers)) {
            scenario.pool.setConnectionTimeout(10_000);
            var start = new CountDownLatch(1);
            for (int i = 0; i < workers * 4; i++) {
                int operation = i;
                work.submit(() -> {
                    ConcurrentWork.await(start);
                    AuditContext identity = operation % 3 == 0 ? ANA : operation % 3 == 1 ? BRUNO : SYSTEM;
                    try (var connection = scenario.borrow(identity)) {
                        scenario.operation(connection, identity, false, operation % 4 == 0);
                    }
                    return null;
                });
            }
            start.countDown();
            work.join(Duration.ofSeconds(60));
            scenario.reconcile();
            assertThat(scenario.tracking.created()).isLessThanOrEqualTo(poolSize);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cleansAfterExplicitOrImplicitRollback(boolean explicit) throws Exception {
        try (var scenario = new OracleScenario(1)) {
            long generation;
            try (var connection = scenario.borrow(ANA)) {
                generation = scenario.tracking.generation(connection);
                connection.setAutoCommit(false);
                String id = UUID.randomUUID().toString();
                scenario.expectRollback(id);
                scenario.insert(connection, id, id, ANA);
                scenario.marker(connection, id);
                if (explicit) connection.rollback();
            }
            scenario.assertIdleClean(generation);
            try (var connection = scenario.borrow(BRUNO)) { scenario.operation(connection, BRUNO, false, false); }
            scenario.reconcile();
        }
    }

    @Test
    void acquisitionTimeoutAndInterruptedWaiterDoNotLeakIdentity() throws Exception {
        try (var scenario = new OracleScenario(1); var work = new ConcurrentWork(1)) {
            scenario.pool.setConnectionTimeout(500);
            try (var held = scenario.borrow(ANA)) {
                var timedOut = work.submit(() -> {
                    assertThatThrownBy(() -> scenario.borrow(BRUNO)).isInstanceOf(SQLException.class);
                    return null;
                });
                timedOut.get(5, TimeUnit.SECONDS);
                var interrupted = work.submit(() -> {
                    Thread.currentThread().interrupt();
                    try { assertThatThrownBy(() -> scenario.borrow(BRUNO)).isInstanceOf(SQLException.class); }
                    finally { Thread.interrupted(); }
                    return null;
                });
                interrupted.get(5, TimeUnit.SECONDS);
                scenario.operation(held, ANA, false, false);
            }
            work.submit(() -> {
                try (var connection = scenario.borrow(BRUNO)) { scenario.operation(connection, BRUNO, false, false); }
                return null;
            });
            work.join(Duration.ofSeconds(10));
            scenario.reconcile();
        }
    }

    @Test
    void closesIdempotentlyEvenWhenTwoThreadsCallClose() throws Exception {
        try (var scenario = new OracleScenario(1); var work = new ConcurrentWork(2)) {
            Connection connection = scenario.borrow(ANA);
            long generation = scenario.tracking.generation(connection);
            var start = new CountDownLatch(1);
            for (int i = 0; i < 2; i++) work.submit(() -> { ConcurrentWork.await(start); connection.close(); return null; });
            start.countDown(); work.join(Duration.ofSeconds(10));
            assertThat(scenario.tracking.cleanups()).as("one checkout cleanup and one idempotent return cleanup").isEqualTo(2);
            scenario.assertIdleClean(generation);
            assertThatThrownBy(connection::createStatement).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void softEvictionRetiresIdleAndActiveConnectionsWithoutReusingTheirState() throws Exception {
        try (var scenario = new OracleScenario(1)) {
            long first;
            try (var connection = scenario.borrow(ANA)) {
                first = scenario.tracking.generation(connection);
                scenario.pool.getHikariPoolMXBean().softEvictConnections();
                scenario.operation(connection, ANA, false, false);
            }
            try (var connection = scenario.borrow(BRUNO)) {
                assertThat(scenario.tracking.generation(connection)).isNotEqualTo(first);
                scenario.operation(connection, BRUNO, false, false);
            }
            scenario.pool.getHikariPoolMXBean().softEvictConnections();
            try (var connection = scenario.borrow(SYSTEM)) { scenario.operation(connection, SYSTEM, false, false); }
            scenario.reconcile();
        }
    }

    @Test
    void closesRetainedStatementsBeforeAnotherUserBorrows() throws Exception {
        try (var scenario = new OracleScenario(1)) {
            Connection connection = scenario.borrow(ANA);
            var retained = connection.createStatement();
            connection.close();
            try (var next = scenario.borrow(BRUNO)) {
                assertThatThrownBy(() -> retained.execute("SELECT 1 FROM DUAL")).isInstanceOf(SQLException.class);
                scenario.operation(next, BRUNO, false, false);
            }
            scenario.reconcile();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"statement", "metadata"})
    void jdbcConnectionAliasesMustNotReturnDirtySessionsToThePool(String route) throws Exception {
        try (var scenario = new OracleScenario(1)) {
            Connection audited = scenario.borrow(ANA);
            long generation = scenario.tracking.generation(audited);
            try {
                Connection alias;
                if (route.equals("statement")) {
                    try (var statement = audited.createStatement()) { alias = statement.getConnection(); }
                } else { alias = audited.getMetaData().getConnection(); }
                ScenarioReport.current().detail("classification", "jdbc-alias-bypasses-audit-close");
                alias.close();
                // Assert BEFORE checkout B overwrites the old metadata. Known defects remain red.
                scenario.assertIdleClean(generation);
            } finally {
                try { audited.close(); } catch (SQLException ignored) { /* alias already closed; fixture forcibly tears down physical sessions */ }
            }
        }
    }

    @Test
    void unwrapOfConnectionPreservesTheAuditedInterface() throws Exception {
        try (var scenario = new OracleScenario(1)) {
            try (var connection = scenario.borrow(ANA)) {
                assertThat(connection.unwrap(Connection.class)).isSameAs(connection);
                scenario.operation(connection.unwrap(Connection.class), ANA, false, false);
            }
            scenario.reconcile();
        }
    }

    @Test
    void vendorUnwrapIsAnExplicitDiagnosticOutsideTheLogicalLeaseContract() throws Exception {
        try (var scenario = new OracleScenario(1)) {
            var audited = scenario.borrow(ANA);
            var escaped = audited.unwrap(oracle.jdbc.OracleConnection.class);
            long generation = scenario.tracking.generation(audited);
            audited.close();
            scenario.assertIdleClean(generation);
            try (var next = scenario.borrow(BRUNO)) {
                assertThat(scenario.tracking.generation(next)).isEqualTo(generation);
                scenario.report.detail("classification", "outside-contract-vendor-reference-retained-after-close");
                scenario.report.detail("escapedPhysicalReferenceStillUsable", !escaped.isClosed());
                // Read-only diagnostic under a barrier: no simultaneous commands on the same session.
                assertThat(io.github.edlemarcio.audit.oracle.testsupport.TrackingOracleDataSource.snapshot(escaped).get("user"))
                        .isEqualTo(BRUNO.user());
                scenario.operation(next, BRUNO, false, false);
            }
            scenario.reconcile();
        }
    }
}
