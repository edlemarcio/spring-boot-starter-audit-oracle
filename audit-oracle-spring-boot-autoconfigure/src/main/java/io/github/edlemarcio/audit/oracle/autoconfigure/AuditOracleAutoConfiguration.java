package io.github.edlemarcio.audit.oracle.autoconfigure;

import com.zaxxer.hikari.HikariDataSource;
import io.github.edlemarcio.audit.oracle.autoconfigure.jdbc.AuditDataSource;
import io.github.edlemarcio.audit.oracle.core.AuditContextProvider;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

@AutoConfiguration(before = DataSourceAutoConfiguration.class)
@ConditionalOnClass(HikariDataSource.class)
@ConditionalOnProperty(prefix = "audit.oracle", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({AuditOracleProperties.class, DataSourceProperties.class})
public class AuditOracleAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DataSource.class)
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(DataSourceProperties properties, AuditContextProvider provider) {
        var dataSource = new AuditDataSource(provider);
        dataSource.setDriverClassName(properties.determineDriverClassName());
        dataSource.setJdbcUrl(properties.determineUrl());
        dataSource.setUsername(properties.determineUsername());
        dataSource.setPassword(properties.determinePassword());
        return dataSource;
    }

    @Bean
    static BeanPostProcessor auditDataSourceVerifier() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource && !(bean instanceof AuditDataSource)) {
                    throw new BeanCreationException(beanName,
                            "Oracle auditing requires AuditDataSource. Use the auto-configured pool, "
                                    + "provide an AuditDataSource, or explicitly disable audit.oracle.enabled.");
                }
                return bean;
            }
        };
    }
}
