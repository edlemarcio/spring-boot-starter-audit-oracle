package io.github.edlemarcio.audit.oracle.autoconfigure;

import com.zaxxer.hikari.HikariDataSource;
import io.github.edlemarcio.audit.oracle.autoconfigure.jdbc.AuditDataSource;
import io.github.edlemarcio.audit.oracle.core.AuditContext;
import io.github.edlemarcio.audit.oracle.core.AuditContextProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AuditOracleAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class, AuditOracleAutoConfiguration.class))
            .withPropertyValues("spring.datasource.url=jdbc:h2:mem:auditing", "spring.datasource.username=sa",
                    "spring.datasource.password=test", "spring.datasource.driver-class-name=org.h2.Driver",
                    "spring.datasource.hikari.pool-name=AuditPool", "spring.datasource.hikari.maximum-pool-size=3",
                    "spring.datasource.hikari.minimum-idle=0", "spring.datasource.hikari.auto-commit=false",
                    "spring.datasource.hikari.connection-init-sql=SELECT 1");

    @Test
    void registersOnePoolWithConfiguredHikariSettingsAndClosesItOnShutdown() {
        var reference = new AtomicReference<HikariDataSource>();
        runner.withBean(AuditContextProvider.class, () -> () -> new AuditContext("system", "0.0.0.0", "example"))
                .withPropertyValues("audit.oracle.application=example", "audit.oracle.user-claim=preferred_username")
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSource.class);
                    var dataSource = context.getBean(HikariDataSource.class);
                    reference.set(dataSource);
                    assertThat(dataSource).isInstanceOf(AuditDataSource.class);
                    assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:h2:mem:auditing");
                    assertThat(dataSource.getUsername()).isEqualTo("sa");
                    assertThat(dataSource.getPassword()).isEqualTo("test");
                    assertThat(dataSource.getPoolName()).isEqualTo("AuditPool");
                    assertThat(dataSource.getMaximumPoolSize()).isEqualTo(3);
                    assertThat(dataSource.isAutoCommit()).isFalse();
                    assertThat(dataSource.getConnectionInitSql()).isEqualTo("SELECT 1");
                    assertThat(context.getBean(AuditOracleProperties.class).getUserClaim()).isEqualTo("preferred_username");
                });
        assertThat(reference.get().isClosed()).isTrue();
    }

    @Test
    void disablesAuditingForH2WithoutRequiringAProvider() {
        runner.withPropertyValues("audit.oracle.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(DataSource.class);
            assertThat(context.getBean(DataSource.class)).isNotInstanceOf(AuditDataSource.class);
        });
    }

    @Test
    void failsStartupWhenNoTrustedContextProviderIsAvailable() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsACustomPoolThatWouldSilentlyBypassAuditing() {
        runner.withBean(DataSource.class, HikariDataSource::new).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("Oracle auditing requires AuditDataSource");
        });
    }

    @Test
    void acceptsAnExplicitAuditDataSourceWithoutCreatingASecondPool() {
        runner.withBean(DataSource.class, () -> new AuditDataSource(
                () -> new AuditContext("system", "0.0.0.0", "example"))).run(context -> {
            assertThat(context).hasSingleBean(DataSource.class);
            assertThat(context.getBean(DataSource.class)).isInstanceOf(AuditDataSource.class);
        });
    }
}
