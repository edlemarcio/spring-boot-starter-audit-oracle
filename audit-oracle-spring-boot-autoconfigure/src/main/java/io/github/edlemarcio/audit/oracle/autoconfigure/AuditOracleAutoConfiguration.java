package io.github.edlemarcio.audit.oracle.autoconfigure;

import io.github.edlemarcio.audit.oracle.autoconfigure.oracle.OracleAuditContextManager;
import io.github.edlemarcio.audit.oracle.autoconfigure.transaction.AuditedTransactionAspect;
import io.github.edlemarcio.audit.oracle.core.AuditIdentityProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@AutoConfiguration
@ConditionalOnProperty(prefix = "audit.oracle", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AuditOracleProperties.class)
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class AuditOracleAutoConfiguration {

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnMissingBean
    OracleAuditContextManager oracleAuditContextManager(
            DataSource dataSource,
            AuditOracleProperties properties
    ) {
        return new OracleAuditContextManager(dataSource, properties);
    }

    @Bean
    @ConditionalOnBean({AuditIdentityProvider.class, OracleAuditContextManager.class})
    @ConditionalOnSingleCandidate(PlatformTransactionManager.class)
    @ConditionalOnMissingBean
    AuditedTransactionAspect auditedTransactionAspect(
            AuditIdentityProvider identityProvider,
            OracleAuditContextManager contextManager,
            PlatformTransactionManager transactionManager
    ) {
        return new AuditedTransactionAspect(identityProvider, contextManager, transactionManager);
    }
}
