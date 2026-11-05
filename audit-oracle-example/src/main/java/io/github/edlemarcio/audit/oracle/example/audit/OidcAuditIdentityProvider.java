package io.github.edlemarcio.audit.oracle.example.audit;

import io.github.edlemarcio.audit.oracle.core.AuditIdentity;
import io.github.edlemarcio.audit.oracle.core.AuditIdentityProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

@Component
public class OidcAuditIdentityProvider implements AuditIdentityProvider {

    @Override
    public Optional<AuditIdentity> getCurrentIdentity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof OidcUser oidcUser)) {
            return Optional.empty();
        }

        String cpf = oidcUser.getClaimAsString("cpf");
        String name = oidcUser.getClaimAsString("name");
        if (name == null || name.isBlank()) {
            name = oidcUser.getFullName();
        }

        if (cpf == null || cpf.isBlank() || name == null || name.isBlank()) {
            return Optional.empty();
        }

        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Optional.empty();
        }

        Object correlationId = attributes.getRequest().getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        if (!(correlationId instanceof String requestId) || requestId.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new AuditIdentity(cpf, name, requestId));
    }
}
