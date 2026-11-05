package io.github.edlemarcio.audit.oracle.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for Audit Oracle auto-configuration.
 *
 * <p>Infrastructure beans will be added only after their connection and
 * transaction lifecycle guarantees are covered by integration tests.</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "audit.oracle", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AuditOracleProperties.class)
public class AuditOracleAutoConfiguration {
}
