package io.github.edlemarcio.audit.oracle.autoconfigure.jdbc;

import io.github.edlemarcio.audit.oracle.core.AuditContext;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Executar apenas contra um schema de testes isolado: cria e remove AUDIT_TEST_*. */
@EnabledIfEnvironmentVariable(named = "AUDIT_ORACLE_URL", matches = ".+")
class OracleAuditIntegrationTest {

    private static final AuditContext ANA = new AuditContext("sub-ana", "2001:db8:abcd:1234:5678:abcd:1234:5678", "condenacoes-interno");
    private static final AuditContext BRUNO = new AuditContext("sub-bruno", "203.0.113.20", "condenacoes-externo");
    private static final AuditContext SISTEMA = new AuditContext("SISTEMA:ROTINA", "0.0.0.0", "condenacoes-interno");

    private final ThreadLocal<AuditContext> context = ThreadLocal.withInitial(() -> SISTEMA);
    private AuditDataSource dataSource;

    @BeforeEach
    void prepareSchema() throws SQLException {
        dropTables();
        execute("CREATE TABLE AUDIT_TEST_PARENT (SQ_PARENT NUMBER PRIMARY KEY, VALOR VARCHAR2(100), ST_EXCLUIDO CHAR(1) DEFAULT 'N')");
        execute("CREATE TABLE AUDIT_TEST_CHILD (SQ_CHILD NUMBER PRIMARY KEY, SQ_PARENT NUMBER REFERENCES AUDIT_TEST_PARENT, VALOR VARCHAR2(100))");
        execute("CREATE TABLE AUDIT_TEST_LOG (ENTIDADE VARCHAR2(10), ID_ORIGEM NUMBER, TP_OPERACAO CHAR(1), "
                + "NM_USUARIO_APLICACAO VARCHAR2(100), NR_IP_APLICACAO VARCHAR2(45), NM_APLICATIVO VARCHAR2(50))");
        createTrigger("PARENT", "SQ_PARENT", true);
        createTrigger("CHILD", "SQ_CHILD", false);
        dataSource = new AuditDataSource(context::get);
        dataSource.setJdbcUrl(System.getenv("AUDIT_ORACLE_URL"));
        dataSource.setUsername(System.getenv("AUDIT_ORACLE_USER"));
        dataSource.setPassword(System.getenv("AUDIT_ORACLE_PASSWORD"));
        dataSource.setMinimumIdle(0);
        dataSource.setMaximumPoolSize(2);
        dataSource.setPoolName("AuditIntegrationPool");
    }

    @AfterEach
    void close() throws SQLException {
        if (dataSource != null) {
            dataSource.close();
        }
        context.remove();
        dropTables();
    }

    @Test
    void deveReutilizarMesmaSessaoSemVazarUsuarioIpOuAplicativo() throws SQLException {
        dataSource.setMaximumPoolSize(1);
        context.set(ANA);
        final String firstSession;
        final Connection physical;
        try (Connection connection = dataSource.getConnection()) {
            firstSession = sessionId(connection);
            physical = connection.unwrap(oracle.jdbc.OracleConnection.class);
            connection.createStatement().executeUpdate("INSERT INTO AUDIT_TEST_PARENT (SQ_PARENT, VALOR) VALUES (1, 'ana')");
        }
        assertContextCleared(physical);

        context.set(BRUNO);
        try (Connection connection = dataSource.getConnection()) {
            assertThat(sessionId(connection)).isEqualTo(firstSession);
            connection.setAutoCommit(false);
            connection.createStatement().executeUpdate("UPDATE AUDIT_TEST_PARENT SET VALOR = 'bruno' WHERE SQ_PARENT = 1");
            connection.commit();
        }
        assertContextCleared(physical);
        assertThat(logs()).containsExactly(log("PARENT", 1, "I", ANA), log("PARENT", 1, "A", BRUNO));

        context.remove();
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("INSERT INTO AUDIT_TEST_PARENT (SQ_PARENT, VALOR) VALUES (2, 'job')");
        }
        assertThat(logs()).contains(log("PARENT", 2, "I", SISTEMA));
    }

    @Test
    void deveManterAutorAteFlushDoHibernateInclusiveRemocaoFisicaDeFilho() {
        final var configuration = new Configuration().addAnnotatedClass(Parent.class).addAnnotatedClass(Child.class);
        configuration.getProperties().put("hibernate.connection.datasource", dataSource);
        configuration.setProperty("hibernate.hbm2ddl.auto", "none");
        try (SessionFactory factory = configuration.buildSessionFactory()) {
            context.set(ANA);
            try (var session = factory.openSession()) {
                final var transaction = session.beginTransaction();
                final var parent = new Parent(1L, "ana");
                parent.children.add(new Child(2L, parent));
                session.persist(parent);
                transaction.commit();
            }
            context.set(BRUNO);
            try (var session = factory.openSession()) {
                final var transaction = session.beginTransaction();
                final var parent = session.find(Parent.class, 1L);
                parent.children.clear();
                parent.value = "bruno";
                transaction.commit();
            }
            try (var session = factory.openSession()) {
                final var transaction = session.beginTransaction();
                session.find(Parent.class, 1L).deleted = "S";
                transaction.commit();
            }
        }
        assertThat(logs()).contains(log("CHILD", 2, "I", ANA), log("CHILD", 2, "D", BRUNO),
                log("PARENT", 1, "A", BRUNO), log("PARENT", 1, "E", BRUNO));
    }

    @Test
    void deveDesfazerAlteracaoELogAntesDeLimparConexaoFechadaSemCommit() throws SQLException {
        context.set(ANA);
        final Connection physical;
        try (Connection connection = dataSource.getConnection()) {
            physical = connection.unwrap(oracle.jdbc.OracleConnection.class);
            connection.setAutoCommit(false);
            connection.createStatement().executeUpdate("INSERT INTO AUDIT_TEST_PARENT (SQ_PARENT) VALUES (1)");
        }
        assertContextCleared(physical);
        assertThat(logs()).isEmpty();
        try (Connection connection = directConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM AUDIT_TEST_PARENT")) {
            result.next();
            assertThat(result.getInt(1)).isZero();
        }
    }

    @Test
    void deveIsolarTransacaoRequiresNewEPreservarContextoDaExterna() {
        final var manager = new DataSourceTransactionManager(dataSource);
        final var outer = new TransactionTemplate(manager);
        final var inner = new TransactionTemplate(manager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        final var jdbc = new JdbcTemplate(dataSource);
        final List<String> sessions = new ArrayList<>();
        context.set(ANA);
        outer.executeWithoutResult(status -> {
            sessions.add(jdbc.queryForObject("SELECT SYS_CONTEXT('USERENV','SID') FROM DUAL", String.class));
            jdbc.update("INSERT INTO AUDIT_TEST_PARENT (SQ_PARENT) VALUES (10)");
            context.set(BRUNO);
            try {
                inner.executeWithoutResult(innerStatus -> {
                    sessions.add(jdbc.queryForObject("SELECT SYS_CONTEXT('USERENV','SID') FROM DUAL", String.class));
                    jdbc.update("INSERT INTO AUDIT_TEST_PARENT (SQ_PARENT) VALUES (20)");
                });
            } finally {
                context.set(ANA);
            }
            sessions.add(jdbc.queryForObject("SELECT SYS_CONTEXT('USERENV','SID') FROM DUAL", String.class));
            jdbc.update("INSERT INTO AUDIT_TEST_PARENT (SQ_PARENT) VALUES (30)");
        });
        assertThat(sessions.get(0)).isEqualTo(sessions.get(2)).isNotEqualTo(sessions.get(1));
        assertThat(logs()).containsExactly(log("PARENT", 10, "I", ANA), log("PARENT", 20, "I", BRUNO), log("PARENT", 30, "I", ANA));
    }

    @Test
    void deveIsolarAutoresEmConexoesConcorrentes() throws Exception {
        final var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            final var ana = executor.submit(() -> insertConcurrently(1, ANA, barrier));
            final var bruno = executor.submit(() -> insertConcurrently(2, BRUNO, barrier));
            assertThat(ana.get(20, TimeUnit.SECONDS)).isNotEqualTo(bruno.get(20, TimeUnit.SECONDS));
        }
        assertThat(logs()).containsExactly(log("PARENT", 1, "I", ANA), log("PARENT", 2, "I", BRUNO));
    }

    private String insertConcurrently(final int id, final AuditContext author, final CyclicBarrier barrier) throws Exception {
        context.set(author);
        try (Connection connection = dataSource.getConnection()) {
            barrier.await(15, TimeUnit.SECONDS);
            try (var statement = connection.prepareStatement("INSERT INTO AUDIT_TEST_PARENT (SQ_PARENT) VALUES (?)")) {
                statement.setInt(1, id);
                statement.executeUpdate();
            }
            return sessionId(connection);
        } finally {
            context.remove();
        }
    }

    private List<String> logs() {
        try (Connection connection = directConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT ENTIDADE, ID_ORIGEM, TP_OPERACAO, NM_USUARIO_APLICACAO, "
                     + "NR_IP_APLICACAO, NM_APLICATIVO FROM AUDIT_TEST_LOG ORDER BY ID_ORIGEM, TP_OPERACAO DESC")) {
            final List<String> rows = new ArrayList<>();
            while (result.next()) {
                rows.add(String.join("|", result.getString(1), result.getString(2), result.getString(3),
                        result.getString(4), result.getString(5), result.getString(6)));
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String log(final String entity, final int id, final String operation, final AuditContext context) {
        return entity + "|" + id + "|" + operation + "|" + context.user() + "|" + context.ip() + "|" + context.application();
    }

    private static String sessionId(final Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT SYS_CONTEXT('USERENV','SID') FROM DUAL")) {
            result.next();
            return result.getString(1);
        }
    }

    private static void assertContextCleared(final Connection physical) throws SQLException {
        try (var statement = physical.createStatement(); var result = statement.executeQuery("SELECT "
                + "SYS_CONTEXT('USERENV','CLIENT_IDENTIFIER'), SYS_CONTEXT('USERENV','CLIENT_INFO'), SYS_CONTEXT('USERENV','MODULE') FROM DUAL")) {
            result.next();
            assertThat(result.getString(1)).isNull();
            assertThat(result.getString(2)).isNull();
            assertThat(result.getString(3)).isNull();
        }
    }

    private void createTrigger(final String entity, final String key, final boolean softDelete) throws SQLException {
        final String update = softDelete ? "CASE WHEN :OLD.ST_EXCLUIDO = 'N' AND :NEW.ST_EXCLUIDO = 'S' THEN 'E' ELSE 'A' END" : "'A'";
        execute("CREATE OR REPLACE TRIGGER AUDIT_TEST_" + entity + "_TG AFTER INSERT OR UPDATE OR DELETE ON AUDIT_TEST_" + entity
                + " FOR EACH ROW DECLARE v_id NUMBER; v_op CHAR(1); BEGIN "
                + "IF DELETING THEN v_id := :OLD." + key + "; v_op := 'D'; "
                + "ELSE v_id := :NEW." + key + "; IF INSERTING THEN v_op := 'I'; ELSE v_op := " + update + "; END IF; END IF; "
                + "INSERT INTO AUDIT_TEST_LOG VALUES ('" + entity + "', v_id, v_op, "
                + "NVL(SYS_CONTEXT('USERENV','CLIENT_IDENTIFIER'), SYS_CONTEXT('USERENV','SESSION_USER')), "
                + "SUBSTR(NVL(SYS_CONTEXT('USERENV','CLIENT_INFO'), NVL(SYS_CONTEXT('USERENV','IP_ADDRESS'),'0.0.0.0')), 1, 45), "
                + "SYS_CONTEXT('USERENV','MODULE')); END;");
    }

    private void dropTables() throws SQLException {
        for (final String tabela : List.of("AUDIT_TEST_CHILD", "AUDIT_TEST_PARENT", "AUDIT_TEST_LOG")) {
            execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE " + tabela + " PURGE'; EXCEPTION WHEN OTHERS THEN "
                    + "IF SQLCODE != -942 THEN RAISE; END IF; END;");
        }
    }

    private void execute(final String sql) throws SQLException {
        try (Connection connection = directConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Connection directConnection() throws SQLException {
        return DriverManager.getConnection(System.getenv("AUDIT_ORACLE_URL"), System.getenv("AUDIT_ORACLE_USER"),
                System.getenv("AUDIT_ORACLE_PASSWORD"));
    }

    @Entity(name = "AuditParent")
    @Table(name = "AUDIT_TEST_PARENT")
    public static class Parent {
        @Id
        @Column(name = "SQ_PARENT")
        private Long id;
        @Column(name = "VALOR")
        private String value;
        @Column(name = "ST_EXCLUIDO")
        private String deleted = "N";
        @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
        private List<Child> children = new ArrayList<>();

        protected Parent() { }

        Parent(final Long id, final String value) {
            this.id = id;
            this.value = value;
        }
    }

    @Entity(name = "AuditChild")
    @Table(name = "AUDIT_TEST_CHILD")
    public static class Child {
        @Id
        @Column(name = "SQ_CHILD")
        private Long id;
        @ManyToOne
        @JoinColumn(name = "SQ_PARENT")
        private Parent parent;

        protected Child() { }

        Child(final Long id, final Parent parent) {
            this.id = id;
            this.parent = parent;
        }
    }
}
