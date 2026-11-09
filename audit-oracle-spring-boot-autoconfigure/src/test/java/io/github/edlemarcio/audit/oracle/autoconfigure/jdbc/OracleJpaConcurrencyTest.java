package io.github.edlemarcio.audit.oracle.autoconfigure.jdbc;

import io.github.edlemarcio.audit.oracle.core.AuditContext;
import io.github.edlemarcio.audit.oracle.testsupport.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

import static io.github.edlemarcio.audit.oracle.testsupport.OracleScenario.*;
import static org.assertj.core.api.Assertions.*;

@Tag("oracle-concurrency")
@ExtendWith(ScenarioReport.class)
class OracleJpaConcurrencyTest {
    @ParameterizedTest
    @CsvSource({"0,false", "0,true", "1,false", "1,true", "2,false", "2,true", "3,false", "3,true",
            "4,false", "4,true", "5,false", "5,true", "6,false", "6,true"})
    void jpaPropagationModesPreserveOwnershipOrRejectUnsupportedWrites(int propagation, boolean outer) throws Exception {
        try (var scenario = new OracleScenario(2); var jpa = new JpaWork(scenario)) {
            var inner = new TransactionTemplate(jpa.manager); inner.setPropagationBehavior(propagation);
            boolean propagationRejected = (!outer && propagation == TransactionDefinition.PROPAGATION_MANDATORY)
                    || (outer && propagation == TransactionDefinition.PROPAGATION_NEVER);
            boolean nestedRejected = outer && propagation == TransactionDefinition.PROPAGATION_NESTED;
            boolean joined = outer && (propagation == TransactionDefinition.PROPAGATION_REQUIRED
                    || propagation == TransactionDefinition.PROPAGATION_SUPPORTS
                    || propagation == TransactionDefinition.PROPAGATION_MANDATORY);
            boolean hasTransaction = joined || propagation == TransactionDefinition.PROPAGATION_REQUIRED
                    || propagation == TransactionDefinition.PROPAGATION_REQUIRES_NEW
                    || propagation == TransactionDefinition.PROPAGATION_NESTED;
            Runnable invoke = () -> {
                try {
                    scenario.as(BRUNO, () -> inner.execute(status -> {
                        if (hasTransaction) persist(scenario, jpa, joined ? ANA : BRUNO);
                        else {
                            var entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(jpa.factory);
                            entityManager.unwrap(org.hibernate.Session.class).doWork(connection -> scenario.observe(connection, BRUNO));
                            assertThatThrownBy(() -> {
                                entityManager.persist(new JpaWork.Target(UUID.randomUUID().toString()));
                                entityManager.flush();
                            }).isInstanceOf(jakarta.persistence.TransactionRequiredException.class);
                        }
                        return null;
                    }));
                } catch (Exception failure) { throw new RuntimeException(failure); }
            };
            Runnable callOrReject = () -> {
                if (propagationRejected) assertThatThrownBy(invoke::run)
                        .hasRootCauseInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
                else if (nestedRejected) assertThatThrownBy(invoke::run).hasRootCauseInstanceOf(NestedTransactionNotSupportedException.class);
                else invoke.run();
            };
            scenario.as(ANA, () -> {
                if (outer) new TransactionTemplate(jpa.manager).executeWithoutResult(status -> {
                    persist(scenario, jpa, ANA); callOrReject.run(); persist(scenario, jpa, ANA);
                });
                else callOrReject.run();
                return null;
            });
            scenario.reconcile();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10})
    void concurrentJpaTransactionsKeepFlushCascadeAndOrphanRemovalAuthors(int poolSize) throws Exception {
        try (var scenario = new OracleScenario(poolSize); var jpa = new JpaWork(scenario); var work = new ConcurrentWork(16)) {
            scenario.pool.setConnectionTimeout(10_000);
            for (int i = 0; i < 48; i++) {
                int operation = i;
                work.submit(() -> {
                    jpa.operation(operation % 2 == 0 ? ANA : BRUNO, operation % 3 == 0, operation % 4 == 0);
                    return null;
                });
            }
            work.join(Duration.ofSeconds(90)); scenario.reconcile();
        }
    }

    @Test
    void jpaRequiresNewSuspendsAndRestoresTheOuterIdentity() throws Exception {
        try (var scenario = new OracleScenario(2); var jpa = new JpaWork(scenario)) {
            var inner = new TransactionTemplate(jpa.manager);
            inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            scenario.as(ANA, () -> new TransactionTemplate(jpa.manager).execute(status -> {
                persist(scenario, jpa, ANA);
                try {
                    scenario.as(BRUNO, () -> inner.execute(innerStatus -> { persist(scenario, jpa, BRUNO); return null; }));
                } catch (Exception failure) { throw new RuntimeException(failure); }
                persist(scenario, jpa, ANA);
                return null;
            }));
            scenario.reconcile();
        }
    }

    @Test
    void unsupportedJpaSavepointsAreRejectedWithoutAnAuditSideEffect() throws Exception {
        try (var scenario = new OracleScenario(1); var jpa = new JpaWork(scenario)) {
            var nested = new TransactionTemplate(jpa.manager); nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
            scenario.as(ANA, () -> new TransactionTemplate(jpa.manager).execute(status -> {
                persist(scenario, jpa, ANA);
                assertThatThrownBy(() -> nested.execute(inner -> null)).isInstanceOf(NestedTransactionNotSupportedException.class);
                return null;
            }));
            scenario.reconcile();
        }
    }

    @Test
    void aFlushFailureRollsBackAndLetsAnotherUserBorrowSafely() throws Exception {
        try (var scenario = new OracleScenario(1); var jpa = new JpaWork(scenario)) {
            assertThatThrownBy(() -> scenario.as(ANA, () -> new TransactionTemplate(jpa.manager).execute(status -> {
                var manager = EntityManagerFactoryUtils.getTransactionalEntityManager(jpa.factory);
                var entity = new JpaWork.Target(UUID.randomUUID().toString());
                entity.payload = "x".repeat(200); // Actual Oracle column limit, not a mocked ORM failure.
                manager.persist(entity); manager.flush();
                return null;
            }))).isInstanceOf(RuntimeException.class);
            jpa.operation(BRUNO, false, false);
            scenario.reconcile();
        }
    }

    private static void persist(OracleScenario scenario, JpaWork jpa, AuditContext identity) {
        var entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(jpa.factory);
        String id = UUID.randomUUID().toString();
        scenario.expect(id, 1, "I", id, identity);
        entityManager.persist(new JpaWork.Target(id));
        entityManager.unwrap(org.hibernate.Session.class).doWork(connection -> scenario.marker(connection, id));
    }
}
