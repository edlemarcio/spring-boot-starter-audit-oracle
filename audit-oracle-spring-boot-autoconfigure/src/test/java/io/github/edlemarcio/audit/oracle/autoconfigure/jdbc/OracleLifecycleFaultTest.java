package io.github.edlemarcio.audit.oracle.autoconfigure.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import io.github.edlemarcio.audit.oracle.testsupport.ConcurrentWork;
import io.github.edlemarcio.audit.oracle.testsupport.OracleScenario;
import io.github.edlemarcio.audit.oracle.testsupport.ScenarioReport;
import io.github.edlemarcio.audit.oracle.testsupport.TrackingOracleDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.github.edlemarcio.audit.oracle.testsupport.OracleScenario.*;
import static io.github.edlemarcio.audit.oracle.testsupport.TrackingOracleDataSource.Point.*;
import static org.assertj.core.api.Assertions.*;

@Tag("oracle-concurrency")
@ExtendWith(ScenarioReport.class)
class OracleLifecycleFaultTest {
    @Test
    void failedRollbackMustNotCommitWhenPhysicalEvictionWinsTheCleanupRace() throws Exception {
        try (var scenario = new OracleScenario(1); var base = new HikariDataSource()) {
            var physicalClosed = new CountDownLatch(1);
            base.setDataSource(new org.springframework.jdbc.datasource.DelegatingDataSource(scenario.tracking) {
                @Override public Connection getConnection() throws SQLException {
                    Connection physical = super.getConnection();
                    return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                                try {
                                    Object result = method.invoke(physical, args);
                                    if (method.getName().equals("close")) physicalClosed.countDown();
                                    return result;
                                } catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                            });
                }
            });
            base.setMinimumIdle(1); base.setMaximumPoolSize(1);
            Connection original = base.getConnection();
            Connection audited = AuditConnection.configure(original, ANA, connection -> {
                base.evictConnection(connection);
                // Model preemption after eviction: the asynchronous physical close completes first.
                try { ConcurrentWork.await(physicalClosed); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            });
            audited.setAutoCommit(false);
            String id = UUID.randomUUID().toString();
            scenario.expectRollback(id);
            scenario.insert(audited, id, id, ANA); scenario.marker(audited, id);
            scenario.tracking.arm(ROLLBACK);
            assertThat(catchThrowable(audited::close)).isInstanceOfAny(SQLException.class, RuntimeException.class);
            scenario.report.detail("classification", "rollback-failure-physical-close-commit-race");
            scenario.reconcile();
        }
    }

    @Test
    void aPhysicalCloseFailureDuringEvictionDoesNotRelendTheAbandonedSession() throws Exception {
        try (var scenario = new OracleScenario(1)) {
            var connection = scenario.borrow(ANA);
            long generation = scenario.tracking.generation(connection);
            scenario.operation(connection, ANA, false, false);
            var attempted = new CountDownLatch(1);
            scenario.tracking.arm(new TrackingOracleDataSource.Fault(PHYSICAL_CLOSE, false, attempted, null));
            scenario.pool.getHikariPoolMXBean().softEvictConnections();
            connection.close();
            ConcurrentWork.await(attempted);
            try (var next = scenario.borrow(BRUNO)) {
                assertThat(scenario.tracking.generation(next)).isNotEqualTo(generation);
                scenario.operation(next, BRUNO, false, false);
            }
            scenario.reconcile();
        }
    }

    @ParameterizedTest
    @EnumSource(value = TrackingOracleDataSource.Point.class,
            names = {"SET_BEFORE", "SET_USER", "SET_IP", "SET_AFTER", "SET_STATEMENT_CLOSE",
                    "CLEAR_BEFORE", "CLEAR_USER", "CLEAR_IP", "CLEAR_AFTER", "CLEAR_STATEMENT_CLOSE"})
    void partialInitializationNeverReturnsTheFailedPhysicalConnection(TrackingOracleDataSource.Point point) throws Exception {
        try (var scenario = new OracleScenario(1)) {
            long previous;
            try (var connection = scenario.borrow(ANA)) { previous = scenario.tracking.generation(connection); }
            scenario.tracking.arm(point);
            assertThatThrownBy(() -> scenario.borrow(BRUNO)).isInstanceOf(SQLException.class);
            try (var connection = scenario.borrow(ANA)) {
                assertThat(scenario.tracking.generation(connection)).isNotEqualTo(previous);
                scenario.operation(connection, ANA, false, false);
            }
            scenario.reconcile();
        }
    }

    @ParameterizedTest
    @EnumSource(value = TrackingOracleDataSource.Point.class,
            names = {"CLEAR_BEFORE", "CLEAR_USER", "CLEAR_IP", "CLEAR_AFTER", "CLEAR_STATEMENT_CLOSE", "ROLLBACK", "AUTOCOMMIT_RESET"})
    void finalizationFailuresEvictBeforeTheWaitingUserReceivesAConnection(TrackingOracleDataSource.Point point) throws Exception {
        try (var scenario = new OracleScenario(1); var work = new ConcurrentWork(2)) {
            Connection first = scenario.borrow(ANA);
            long previous = scenario.tracking.generation(first);
            first.setAutoCommit(false);
            String operation = UUID.randomUUID().toString();
            scenario.expectRollback(operation);
            scenario.insert(first, operation, operation, ANA);
            scenario.marker(first, operation);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            scenario.tracking.arm(new TrackingOracleDataSource.Fault(point, false, entered, release));
            try {
                work.submit(() -> {
                    Throwable failure = catchThrowable(first::close);
                    assertThat(failure).isInstanceOfAny(SQLException.class, RuntimeException.class);
                    scenario.report.trace(java.util.Map.of("phase", point.name(), "reportedException", failure.getClass().getName()));
                    return null;
                });
                ConcurrentWork.await(entered);
                var waiting = work.submit(() -> {
                    try (var second = scenario.borrow(BRUNO)) {
                        long current = scenario.tracking.generation(second);
                        scenario.operation(second, BRUNO, false, false);
                        return current;
                    }
                });
                assertThat(waiting.isDone()).as("A is still cleaning/rolling back and owns the only pool slot").isFalse();
                release.countDown();
                assertThat(waiting.get(10, TimeUnit.SECONDS)).isNotEqualTo(previous);
                work.join(Duration.ofSeconds(15));
                scenario.reconcile();
            } finally { release.countDown(); }
        }
    }

    @ParameterizedTest
    @EnumSource(value = TrackingOracleDataSource.Point.class, names = {"SET_USER", "CLEAR_IP", "ROLLBACK", "AUTOCOMMIT_RESET"})
    void uncheckedLifecycleFailuresAlsoRemoveThePhysicalConnection(TrackingOracleDataSource.Point point) throws Exception {
        try (var scenario = new OracleScenario(1)) {
            Connection first = scenario.borrow(ANA);
            long generation = scenario.tracking.generation(first);
            if (point == SET_USER) {
                first.close();
                scenario.tracking.arm(new TrackingOracleDataSource.Fault(point, true, null, null));
                assertThatThrownBy(() -> scenario.borrow(BRUNO)).isInstanceOf(IllegalStateException.class);
            } else {
                first.setAutoCommit(false);
                scenario.tracking.arm(new TrackingOracleDataSource.Fault(point, true, null, null));
                assertThatThrownBy(first::close).isInstanceOf(RuntimeException.class);
            }
            try (var second = scenario.borrow(BRUNO)) {
                assertThat(scenario.tracking.generation(second)).isNotEqualTo(generation);
                scenario.operation(second, BRUNO, false, false);
            }
            scenario.reconcile();
        }
    }

    @ParameterizedTest
    @EnumSource(value = TrackingOracleDataSource.Point.class, names = {"ROLLBACK", "AUTOCOMMIT_RESET"})
    void lifecycleFailuresMustNotBeReplacedByAnUnrelatedNullPointer(TrackingOracleDataSource.Point point) throws Exception {
        try (var scenario = new OracleScenario(1)) {
            var connection = scenario.borrow(ANA);
            connection.setAutoCommit(false);
            scenario.tracking.arm(point);
            scenario.report.detail("classification", "lifecycle-error-preservation");
            assertThatThrownBy(connection::close).isInstanceOf(SQLException.class).hasMessageContaining("Injected");
        }
    }

    @ParameterizedTest
    @EnumSource(value = TrackingOracleDataSource.Point.class, names = {"COMMIT_BEFORE", "COMMIT_AFTER"})
    void ambiguousCommitIsReconciledUsingTheTransactionalMarker(TrackingOracleDataSource.Point point) throws Exception {
        try (var scenario = new OracleScenario(1)) {
            try (var connection = scenario.borrow(ANA)) {
                connection.setAutoCommit(false);
                String id = UUID.randomUUID().toString();
                scenario.allowAmbiguousCommit(id);
                scenario.insert(connection, id, id, ANA);
                scenario.marker(connection, id);
                scenario.tracking.arm(point);
                assertThatThrownBy(connection::commit).isInstanceOf(SQLException.class);
            }
            try (var connection = scenario.borrow(BRUNO)) { scenario.operation(connection, BRUNO, false, false); }
            scenario.reconcile();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aBrokenPhysicalConnectionCannotBecomeAnotherUsersSession(boolean committed) throws Exception {
        try (var scenario = new OracleScenario(1)) {
            Connection first = scenario.borrow(ANA);
            long generation = scenario.tracking.generation(first);
            first.setAutoCommit(false);
            String id = UUID.randomUUID().toString();
            if (!committed) scenario.expectRollback(id);
            scenario.insert(first, id, id, ANA);
            scenario.marker(first, id);
            if (committed) first.commit();
            scenario.tracking.breakConnection(generation);
            assertThatThrownBy(first::close).isInstanceOf(SQLException.class);
            try (var second = scenario.borrow(BRUNO)) {
                assertThat(scenario.tracking.generation(second)).isNotEqualTo(generation);
                scenario.operation(second, BRUNO, false, false);
            }
            scenario.reconcile();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void discardFailureEitherAbortsOrKeepsThePoolSlotQuarantined(boolean abortFails) throws Exception {
        try (var scenario = new OracleScenario(1); var base = new HikariDataSource()) {
            base.setDataSource(scenario.tracking);
            base.setMinimumIdle(1); base.setMaximumPoolSize(1); base.setConnectionTimeout(500);
            Connection original = base.getConnection();
            Connection audited = AuditConnection.configure(original, ANA, ignored -> {
                if (abortFails) scenario.tracking.arm(ABORT);
                throw new IllegalStateException("Injected eviction failure");
            });
            scenario.tracking.arm(CLEAR_BEFORE);
            assertThatThrownBy(audited::close).isInstanceOf(SQLException.class);
            if (abortFails) {
                assertThat(base.getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
                assertThatThrownBy(base::getConnection).isInstanceOf(SQLException.class);
            } else {
                assertThat(base.getHikariPoolMXBean().getActiveConnections()).isZero();
            }
            scenario.report.detail("evictionFailureMode", abortFails ? "quarantined-unavailable" : "abort-fallback");
        }
    }

    @Test
    void poolShutdownWithPendingWorkDoesNotPersistOrRecycleIt() throws Exception {
        try (var scenario = new OracleScenario(1)) {
            Connection connection = scenario.borrow(ANA);
            connection.setAutoCommit(false);
            String id = UUID.randomUUID().toString();
            scenario.expectRollback(id);
            scenario.insert(connection, id, id, ANA);
            scenario.marker(connection, id);
            scenario.pool.close();
            assertThatThrownBy(() -> scenario.borrow(BRUNO)).isInstanceOf(SQLException.class);
            try { connection.close(); } catch (SQLException expectedClosedSession) {
                scenario.report.detail("shutdownException", expectedClosedSession.getClass().getName());
            }
            scenario.reconcile();
        }
    }
}
