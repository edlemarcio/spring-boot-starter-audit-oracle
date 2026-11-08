package io.github.edlemarcio.audit.oracle.example.audit;

import io.github.edlemarcio.audit.oracle.autoconfigure.AuditOracleProperties;
import io.github.edlemarcio.audit.oracle.core.AuditContext;
import io.github.edlemarcio.audit.oracle.core.AuditContextProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Adapts the application's validated Spring Security principal on every connection checkout. */
@Component
@ConditionalOnProperty(prefix = "audit.oracle", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SecurityAuditContextProvider implements AuditContextProvider {

    private final String application;
    private final String userClaim;
    private final AuditContext systemContext;

    public SecurityAuditContextProvider(AuditOracleProperties properties) {
        if (properties.getUserClaim() == null || properties.getUserClaim().isBlank()) {
            throw new IllegalArgumentException("Audit user claim is required");
        }
        this.application = properties.getApplication();
        this.userClaim = properties.getUserClaim();
        this.systemContext = new AuditContext(properties.getSystemUser(), AuditContext.UNAVAILABLE_IP, application);
    }

    @Override
    public AuditContext getContext() {
        var attributes = RequestContextHolder.getRequestAttributes();
        var request = attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            if (request != null) {
                throw new IllegalStateException("Database access from an HTTP request requires an authenticated user");
            }
            return systemContext;
        }

        String user;
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            user = jwt.getClaimAsString(userClaim);
        } else if (authentication.getPrincipal() instanceof OAuth2AuthenticatedPrincipal principal) {
            user = principal.getAttribute(userClaim);
        } else {
            user = authentication.getName();
        }
        String ip = request == null ? AuditContext.UNAVAILABLE_IP : request.getRemoteAddr();
        return new AuditContext(user, ip, application);
    }
}
