package io.github.edlemarcio.audit.oracle.example.audit;

import io.github.edlemarcio.audit.oracle.autoconfigure.AuditOracleProperties;
import io.github.edlemarcio.audit.oracle.core.AuditContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class SecurityAuditContextProviderTest {

    private final AuditOracleProperties properties = properties();
    private final SecurityAuditContextProvider provider = new SecurityAuditContextProvider(properties);

    @AfterEach
    void resetSpringContexts() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void usesOidcSubjectAndFullIpv6InsteadOfDisplayName() {
        oidc("sub-ana");
        request("2001:db8:abcd:1234:5678:abcd:1234:5678");
        assertThat(provider.getContext()).isEqualTo(new AuditContext("sub-ana",
                "2001:db8:abcd:1234:5678:abcd:1234:5678", "example"));
    }

    @Test
    void usesConfiguredClaimForOidcAndJwtPrincipals() {
        properties.setUserClaim("preferred_username");
        var configured = new SecurityAuditContextProvider(properties);
        request("192.0.2.10");
        oidc("sub-ana");
        assertThat(configured.getContext().user()).isEqualTo("ana.login");
        jwt();
        assertThat(configured.getContext().user()).isEqualTo("ana.jwt");
        assertThat(provider.getContext().user()).isEqualTo("sub-jwt");
    }

    @Test
    void doesNotTrustIpHeadersDirectlyFromTheClient() {
        oidc("ana");
        var request = request("192.0.2.10");
        request.addHeader("X-Forwarded-For", "203.0.113.99");
        request.addHeader("X-Real-IP", "203.0.113.99");
        assertThat(provider.getContext().ip()).isEqualTo("192.0.2.10");
    }

    @Test
    void onlyUsesSystemIdentityOutsideHttpWithoutAuthentication() {
        assertThat(provider.getContext()).isEqualTo(new AuditContext("SISTEMA:ROTINA", "0.0.0.0", "example"));
        request("192.0.2.10");
        assertThatIllegalStateException().isThrownBy(provider::getContext);
    }

    @Test
    void rejectsAnonymousAndUnauthenticatedRequests() {
        request("192.0.2.10");
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThatIllegalStateException().isThrownBy(provider::getContext);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("ana", "password"));
        assertThatIllegalStateException().isThrownBy(provider::getContext);
    }

    @Test
    void rejectsMissingClaimInsteadOfUsingDisplayNameOrSystemIdentity() {
        properties.setUserClaim("missing_claim");
        var configured = new SecurityAuditContextProvider(properties);
        oidc("ana");
        request("192.0.2.10");
        assertThatIllegalArgumentException().isThrownBy(configured::getContext);
        jwt();
        assertThatIllegalArgumentException().isThrownBy(configured::getContext);
    }

    @Test
    void resolvesEachCurrentUserWithoutRememberingThePreviousRequest() {
        oidc("ana");
        request("192.0.2.10");
        assertThat(provider.getContext().user()).isEqualTo("ana");
        oidc("bruno");
        request("192.0.2.20");
        assertThat(provider.getContext()).isEqualTo(new AuditContext("bruno", "192.0.2.20", "example"));
        resetSpringContexts();
        assertThat(provider.getContext().user()).isEqualTo("SISTEMA:ROTINA");
    }

    @Test
    void preservesPropagatedAuthenticationWithoutHttp() {
        oidc("ana");
        assertThat(provider.getContext()).isEqualTo(new AuditContext("ana", "0.0.0.0", "example"));
    }

    @Test
    void supportsOtherAuthenticatedSpringPrincipals() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("ana", null, List.of()));
        assertThat(provider.getContext().user()).isEqualTo("ana");
    }

    private static AuditOracleProperties properties() {
        var properties = new AuditOracleProperties();
        properties.setApplication("example");
        return properties;
    }

    private static void oidc(String subject) {
        var token = new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("sub", subject, "name", "Ana Silva", "preferred_username", "ana.login"));
        var principal = new DefaultOidcUser(List.of(), token);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    private static void jwt() {
        var token = Jwt.withTokenValue("token").header("alg", "RS256").subject("sub-jwt")
                .claim("name", "Ana Silva").claim("preferred_username", "ana.jwt").build();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(token, null, List.of()));
    }

    private static MockHttpServletRequest request(String ip) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }
}
