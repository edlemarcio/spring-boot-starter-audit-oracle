package io.github.edlemarcio.audit.oracle.testsupport;

import io.github.edlemarcio.audit.oracle.core.AuditContext;
import jakarta.persistence.*;
import org.hibernate.SessionFactory;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Real ORM work; table naming is adapted only in this test fixture. */
public final class JpaWork implements AutoCloseable {
    private final OracleScenario scenario;
    public final SessionFactory factory;
    public final JpaTransactionManager manager;

    public JpaWork(OracleScenario scenario) {
        this.scenario = scenario;
        var configuration = new Configuration().addAnnotatedClass(Target.class);
        configuration.getProperties().put("hibernate.connection.datasource", scenario.pool);
        configuration.setProperty("hibernate.hbm2ddl.auto", "none");
        configuration.setProperty("hibernate.jdbc.batch_size", "10");
        configuration.setProperty("hibernate.connection.handling_mode", "DELAYED_ACQUISITION_AND_HOLD");
        configuration.setPhysicalNamingStrategy(new PhysicalNamingStrategyStandardImpl() {
            @Override public Identifier toPhysicalTableName(Identifier name, JdbcEnvironment environment) {
                return Identifier.toIdentifier(scenario.target);
            }
        });
        factory = configuration.buildSessionFactory();
        manager = new JpaTransactionManager(factory);
        manager.setJpaDialect(new HibernateJpaDialect());
        manager.setDataSource(scenario.pool);
    }

    public void operation(AuditContext identity, boolean explicitFlush, boolean rollback) throws Exception {
        scenario.as(identity, () -> new TransactionTemplate(manager).execute(status -> {
            var entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(factory);
            String transaction = UUID.randomUUID().toString();
            if (rollback) scenario.expectRollback(transaction);
            var parent = new Target(transaction);
            var child = new Target(UUID.randomUUID().toString());
            child.parent = parent; parent.children.add(child);
            scenario.expect(parent.id, 1, "I", transaction, identity);
            scenario.expect(child.id, 1, "I", transaction, identity);
            entityManager.persist(parent);
            entityManager.flush();
            parent.children.clear(); parent.version = 2; parent.payload = "updated";
            scenario.expect(parent.id, 2, "U", transaction, identity);
            scenario.expect(child.id, 1, "D", transaction, identity);
            if (explicitFlush) entityManager.flush();
            entityManager.unwrap(org.hibernate.Session.class).doWork(connection -> scenario.marker(connection, transaction));
            if (rollback) status.setRollbackOnly();
            return null;
        }));
        scenario.report.count("jpaOperations");
    }

    @Override public void close() { factory.close(); }

    @Entity(name = "ConcurrencyTarget")
    @Table(name = "AC_TARGET")
    public static class Target {
        @Id @Column(name = "ID") public String id;
        @Column(name = "VERSION") public int version = 1;
        @Column(name = "PAYLOAD") public String payload = "initial";
        @Column(name = "DELETED") public String deleted = "N";
        @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "PARENT_ID") public Target parent;
        @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
        public List<Target> children = new ArrayList<>();
        protected Target() { }
        public Target(String id) { this.id = id; }
    }
}
