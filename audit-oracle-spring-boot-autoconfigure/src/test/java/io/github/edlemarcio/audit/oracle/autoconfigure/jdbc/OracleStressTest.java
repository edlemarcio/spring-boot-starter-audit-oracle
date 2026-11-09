package io.github.edlemarcio.audit.oracle.autoconfigure.jdbc;

import io.github.edlemarcio.audit.oracle.core.AuditContext;
import io.github.edlemarcio.audit.oracle.testsupport.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("oracle-stress")
@ExtendWith(ScenarioReport.class)
class OracleStressTest {
    @Test
    void seededStressReconcilesEveryBatchAndProvesCrossUserPhysicalReuse() throws Exception {
        int seconds = Integer.getInteger("audit.stress.seconds", 900);
        assertThat(seconds).as("stress must have a nonzero exploration budget").isGreaterThanOrEqualTo(12);
        String[] seeds = System.getProperty("audit.stress.seeds", "1,42,20261108").split(",");
        ScenarioReport report = ScenarioReport.current();
        report.detail("seeds", java.util.List.of(seeds)); report.detail("stressSeconds", seconds);
        int workers = 32;
        for (String seedText : seeds) {
            long seed = Long.parseLong(seedText.trim());
            for (int poolSize : new int[]{1, 2, 5, 10}) {
                var random = new Random(seed ^ poolSize);
                report.detail("currentSeed", seed); report.detail("currentPoolSize", poolSize);
                long deadline = System.nanoTime() + Duration.ofMillis(seconds * 800L / seeds.length / 4).toNanos();
                try (var scenario = new OracleScenario(poolSize); var jpa = new JpaWork(scenario);
                     var work = new ConcurrentWork(workers)) {
                    scenario.pool.setConnectionTimeout(20_000);
                    var manager = new DataSourceTransactionManager(scenario.pool);
                    var nestedCapacity = new Semaphore(Math.max(1, poolSize / 2));
                    int batch = 0;
                    do {
                        report.detail("currentBatch", batch);
                        report.trace(Map.of("seed", seed, "poolSize", poolSize, "batch", batch++));
                        var replay = new java.util.ArrayList<Map<String, Object>>();
                        for (int i = 0; i < workers; i++) {
                            int user = random.nextInt(50);
                            int mode = random.nextInt(6);
                            boolean rollback = random.nextInt(4) == 0;
                            replay.add(Map.of("index", i, "user", user, "mode", mode, "rollback", rollback));
                            var identity = new AuditContext("subject-" + user, user % 2 == 0 ? "2001:db8::" + (user + 1)
                                    : "192.0.2." + (user + 1), user % 3 == 0 ? "external" : "internal");
                            work.submit(() -> {
                                if (mode == 0) jpa.operation(identity, false, rollback);
                                else if (mode == 1) {
                                    scenario.as(identity, () -> new TransactionTemplate(manager).execute(status -> {
                                        var connection = DataSourceUtils.getConnection(scenario.pool);
                                        try {
                                            scenario.observe(connection, identity);
                                            String id = UUID.randomUUID().toString();
                                            if (rollback) scenario.expectRollback(id);
                                            scenario.insert(connection, id, id, identity); scenario.marker(connection, id);
                                            if (rollback) status.setRollbackOnly();
                                        } catch (java.sql.SQLException error) { throw new RuntimeException(error); }
                                        finally { DataSourceUtils.releaseConnection(connection, scenario.pool); }
                                        return null;
                                    }));
                                } else if (mode == 5 && poolSize > 1) {
                                    nestedCapacity.acquire();
                                    try {
                                        scenario.as(identity, () -> new TransactionTemplate(manager).execute(outer -> {
                                            write(scenario, identity);
                                            var inner = new TransactionTemplate(manager);
                                            inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                                            var innerIdentity = new AuditContext("subject-" + ((user + 1) % 50), "192.0.2.250", "nested");
                                            try {
                                                scenario.as(innerIdentity, () -> inner.execute(status -> {
                                                    write(scenario, innerIdentity, rollback);
                                                    if (rollback) status.setRollbackOnly();
                                                    return null;
                                                }));
                                            } catch (Exception failure) { throw new RuntimeException(failure); }
                                            write(scenario, identity);
                                            return null;
                                        }));
                                    } finally { nestedCapacity.release(); }
                                } else {
                                    try (var connection = scenario.borrow(identity)) {
                                        if (mode == 4) {
                                            connection.setAutoCommit(false);
                                            var savepoint = connection.setSavepoint();
                                            String discarded = UUID.randomUUID().toString();
                                            scenario.expectRollback(discarded);
                                            scenario.insert(connection, discarded, discarded, identity);
                                            scenario.marker(connection, discarded);
                                            connection.rollback(savepoint);
                                        }
                                        scenario.operation(connection, identity, mode == 2, mode == 2 ? false : rollback);
                                    }
                                }
                                return null;
                            });
                        }
                        report.detail("currentBatchOperations", replay);
                        work.join(Duration.ofSeconds(60));
                        scenario.resetBatch();
                        if (batch % 8 == 0) {
                            var failed = scenario.borrow(OracleScenario.ANA);
                            long retired = scenario.tracking.generation(failed);
                            scenario.tracking.arm(TrackingOracleDataSource.Point.CLEAR_USER);
                            org.assertj.core.api.Assertions.assertThatThrownBy(failed::close).isInstanceOf(java.sql.SQLException.class);
                            try (var replacement = scenario.borrow(OracleScenario.BRUNO)) {
                                assertThat(scenario.tracking.generation(replacement)).isNotEqualTo(retired);
                                scenario.operation(replacement, OracleScenario.BRUNO, false, false);
                            }
                            scenario.resetBatch(); scenario.report.count("stressInjectedCleanupFailures");
                        }
                    } while (System.nanoTime() < deadline);
                    assertThat(scenario.pool.getHikariPoolMXBean().getTotalConnections()).isLessThanOrEqualTo(poolSize);
                }
            }
        }
    }

    private static void write(OracleScenario scenario, AuditContext identity) {
        write(scenario, identity, false);
    }

    private static void write(OracleScenario scenario, AuditContext identity, boolean rollback) {
        var connection = DataSourceUtils.getConnection(scenario.pool);
        try {
            scenario.observe(connection, identity);
            String id = UUID.randomUUID().toString();
            if (rollback) scenario.expectRollback(id);
            scenario.insert(connection, id, id, identity); scenario.marker(connection, id);
        } catch (java.sql.SQLException failure) { throw new RuntimeException(failure); }
        finally { DataSourceUtils.releaseConnection(connection, scenario.pool); }
    }
}
