package io.github.edlemarcio.audit.oracle.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Identity settings used by application-provided AuditContextProvider adapters. */
@ConfigurationProperties(prefix = "audit.oracle")
public class AuditOracleProperties {

    private boolean enabled = true;
    private String application = "application";
    private String userClaim = "sub";
    private String systemUser = "SISTEMA:ROTINA";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApplication() {
        return application;
    }

    public void setApplication(String application) {
        this.application = application;
    }

    public String getUserClaim() {
        return userClaim;
    }

    public void setUserClaim(String userClaim) {
        this.userClaim = userClaim;
    }

    public String getSystemUser() {
        return systemUser;
    }

    public void setSystemUser(String systemUser) {
        this.systemUser = systemUser;
    }
}
