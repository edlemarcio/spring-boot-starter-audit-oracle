package io.github.edlemarcio.audit.oracle.core;

import java.util.Optional;

/**
 * Supplies the authenticated identity associated with the current application
 * execution. Implementations belong to the consuming application or an
 * optional integration module.
 */
@FunctionalInterface
public interface AuditIdentityProvider {

    /**
     * @return the authenticated audit identity, or empty when no user identity
     *         is available (for example, an anonymous request)
     */
    Optional<AuditIdentity> getCurrentIdentity();
}
