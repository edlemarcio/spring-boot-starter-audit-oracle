package io.github.edlemarcio.audit.oracle.example.audit;

import io.github.edlemarcio.audit.oracle.autoconfigure.AuditOracleAutoConfiguration;
import io.github.edlemarcio.audit.oracle.core.AuditContext;
import io.github.edlemarcio.audit.oracle.core.AuditContextProvider;
import io.github.edlemarcio.audit.oracle.example.customer.Cliente;
import io.github.edlemarcio.audit.oracle.example.customer.ClienteRepository;
import io.github.edlemarcio.audit.oracle.example.customer.ClienteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Uses an isolated test schema: creates and drops CLIENTE and AUDIT_LOG. */
@EnabledIfEnvironmentVariable(named = "AUDIT_ORACLE_URL", matches = ".+")
class ExampleOracleIntegrationTest {

    private static final Path SCRIPTS = Path.of("../database/oracle-19c");
    private static final AuditContext ANA = new AuditContext("sub-ana", "2001:db8::1", "example-interno");
    private static final AuditContext BRUNO = new AuditContext("sub-bruno", "203.0.113.20", "example-externo");
    private final AtomicReference<AuditContext> identity = new AtomicReference<>(ANA);

    @BeforeEach
    void createExampleSchema() throws Exception {
        dropTables();
        executeScript("02_audit_tables.sql");
        executeTrigger();
    }

    @AfterEach
    void cleanExampleSchema() throws Exception {
        dropTables();
    }

    @Test
    void auditsJpaCrudAndRollsBackBothBusinessDataAndAuditLog() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var service = context.getBean(ClienteService.class);
            var query = context.getBean(AuditLogQueryRepository.class);
            var client = service.salvar(new Cliente("11122233344", "Cliente inicial", "old@example.test"));
            identity.set(BRUNO);
            client.setNome("Cliente alterado");
            service.salvar(client);
            service.excluir(client.getId());

            var events = query.findLatest();
            assertThat(events).extracting(AuditLogEntry::operation).containsExactly("DELETE", "UPDATE", "INSERT");
            assertThat(events).extracting(AuditLogEntry::user).containsExactly("sub-bruno", "sub-bruno", "sub-ana");
            assertThat(events).extracting(AuditLogEntry::ip).containsExactly(BRUNO.ip(), BRUNO.ip(), ANA.ip());
            assertThat(events).extracting(AuditLogEntry::application)
                    .containsExactly(BRUNO.application(), BRUNO.application(), ANA.application());
            assertThat(events.get(1).oldValues()).contains("Cliente inicial");
            assertThat(events.get(1).newValues()).contains("Cliente alterado");

            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            transaction.executeWithoutResult(status -> {
                service.salvar(new Cliente("22233344455", "Revertido", null));
                status.setRollbackOnly();
            });
            assertThat(query.findLatest()).hasSize(3);
            assertThat(context.getBean(ClienteRepository.class).count()).isZero();
        });
    }

    @Test
    void directSqlWithoutApplicationMetadataUsesDatabaseSessionFallbacks() throws Exception {
        try (var connection = directConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO CLIENTE (CPF, NOME) VALUES ('11122233344', 'SQL direto')");
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            var event = new AuditLogQueryRepository(jdbc).findLatest().getFirst();
            assertThat(event.user()).isEqualTo(System.getenv("AUDIT_ORACLE_USER").toUpperCase());
            assertThat(event.ip()).isNotBlank();
            assertThat(event.operation()).isEqualTo("INSERT");
        }
    }

    @Test
    void upgradesThePreviousSchemaWithoutReattributingHistoricalEvents() throws Exception {
        try (var connection = directConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE AUDIT_LOG PURGE");
            statement.execute("""
                    CREATE TABLE AUDIT_LOG (
                        ID NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        EVENT_TIMESTAMP TIMESTAMP(6) DEFAULT SYSTIMESTAMP NOT NULL,
                        USER_CPF VARCHAR2(11 CHAR) NOT NULL,
                        USER_NAME VARCHAR2(200 CHAR) NOT NULL,
                        OPERATION VARCHAR2(10 CHAR) NOT NULL,
                        TABLE_NAME VARCHAR2(128 CHAR) NOT NULL,
                        RECORD_ID VARCHAR2(200 CHAR),
                        REQUEST_ID VARCHAR2(128 CHAR) NOT NULL,
                        OLD_VALUES CLOB, NEW_VALUES CLOB
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO AUDIT_LOG (USER_CPF, USER_NAME, OPERATION, TABLE_NAME, REQUEST_ID)
                    VALUES ('11122233344', 'Autora anterior', 'INSERT', 'CLIENTE', 'old-request')
                    """);
        }
        executeScript("migrations/01_from_application_context.sql");
        executeTrigger();
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(ClienteService.class).salvar(new Cliente("22233344455", "Novo cliente", null));
            var events = context.getBean(AuditLogQueryRepository.class).findLatest();
            assertThat(events).hasSize(2);
            assertThat(events.getFirst().user()).isEqualTo(ANA.user());
            assertThat(events.getLast().user()).isNull();
            try (var connection = directConnection(); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT USER_CPF, USER_NAME, REQUEST_ID FROM AUDIT_LOG WHERE ID = 1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("11122233344");
                assertThat(result.getString(2)).isEqualTo("Autora anterior");
                assertThat(result.getString(3)).isEqualTo("old-request");
            }
        });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AuditOracleAutoConfiguration.class,
                        DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
                        TransactionAutoConfiguration.class, JdbcTemplateAutoConfiguration.class))
                .withUserConfiguration(PersistenceConfiguration.class)
                .withBean(AuditContextProvider.class, () -> identity::get)
                .withPropertyValues("spring.datasource.url=" + System.getenv("AUDIT_ORACLE_URL"),
                        "spring.datasource.username=" + System.getenv("AUDIT_ORACLE_USER"),
                        "spring.datasource.password=" + System.getenv("AUDIT_ORACLE_PASSWORD"),
                        "spring.datasource.driver-class-name=oracle.jdbc.OracleDriver",
                        "spring.datasource.hikari.maximum-pool-size=1", "spring.datasource.hikari.minimum-idle=0",
                        "spring.jpa.hibernate.ddl-auto=validate");
    }

    private void executeScript(String name) throws Exception {
        try (var connection = directConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(SCRIPTS.resolve(name)));
        }
    }

    private void executeTrigger() throws Exception {
        String sql = Files.readString(SCRIPTS.resolve("03_cliente_audit_triggers.sql"))
                .replaceAll("(?m)^/\\s*$", "");
        try (var connection = directConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
            try (var errors = statement.executeQuery(
                    "SELECT LINE, TEXT FROM USER_ERRORS WHERE NAME = 'TRG_CLIENTE_AUDIT' ORDER BY SEQUENCE")) {
                var details = new StringBuilder();
                while (errors.next()) {
                    details.append(errors.getInt(1)).append(": ").append(errors.getString(2)).append('\n');
                }
                assertThat(details.toString()).as("Oracle trigger compilation errors").isEmpty();
            }
        }
    }

    private void dropTables() throws SQLException {
        try (var connection = directConnection(); var statement = connection.createStatement()) {
            for (String table : new String[]{"CLIENTE", "AUDIT_LOG"}) {
                statement.execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE " + table + " PURGE'; "
                        + "EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;");
            }
        }
    }

    private Connection directConnection() throws SQLException {
        return DriverManager.getConnection(System.getenv("AUDIT_ORACLE_URL"),
                System.getenv("AUDIT_ORACLE_USER"), System.getenv("AUDIT_ORACLE_PASSWORD"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EntityScan(basePackageClasses = Cliente.class)
    @EnableJpaRepositories(basePackageClasses = ClienteRepository.class)
    @Import({ClienteService.class, AuditLogQueryRepository.class})
    static class PersistenceConfiguration {
    }
}
