package io.github.edlemarcio.audit.oracle.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Oracle audit context integration.
 *
 * <p>These settings describe the database-side contract. They do not configure
 * an identity provider or an authentication protocol.</p>
 */
@ConfigurationProperties(prefix = "audit.oracle")
public class AuditOracleProperties {

    /**
     * Enables the Oracle audit integration.
     */
    private boolean enabled = true;

    /**
     * Oracle application context namespace created by the database scripts.
     */
    private String contextNamespace = "CTX_AUDITORIA";

    /**
     * Stored package procedure used to set the context.
     */
    private String setContextProcedure = "PKG_AUDITORIA_CONTEXT.SET_CONTEXT";

    /**
     * Stored package procedure used to clear the context.
     */
    private String clearContextProcedure = "PKG_AUDITORIA_CONTEXT.CLEAR_CONTEXT";

    /**
     * Whether missing identity must prevent an audited operation.
     */
    private boolean failOnMissingIdentity = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getContextNamespace() {
        return contextNamespace;
    }

    public void setContextNamespace(String contextNamespace) {
        this.contextNamespace = contextNamespace;
    }

    public String getSetContextProcedure() {
        return setContextProcedure;
    }

    public void setSetContextProcedure(String setContextProcedure) {
        this.setContextProcedure = setContextProcedure;
    }

    public String getClearContextProcedure() {
        return clearContextProcedure;
    }

    public void setClearContextProcedure(String clearContextProcedure) {
        this.clearContextProcedure = clearContextProcedure;
    }

    public boolean isFailOnMissingIdentity() {
        return failOnMissingIdentity;
    }

    public void setFailOnMissingIdentity(boolean failOnMissingIdentity) {
        this.failOnMissingIdentity = failOnMissingIdentity;
    }
}
