package io.github.edlemarcio.audit.oracle.example.concurrency;

import io.github.edlemarcio.audit.oracle.core.AuditContext;
import io.github.edlemarcio.audit.oracle.testsupport.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.concurrent.DelegatingSecurityContextCallable;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("oracle-concurrency")
@ExtendWith(ScenarioReport.class)
class OracleHttpConcurrencyTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10})
    void realSecurityFiltersAndJpaKeepConcurrentRequestAuthors(int poolSize) throws Exception {
        try (var harness = new HttpOracleHarness(poolSize)) {
            harness.run((mvc, service) -> {
                try (var work = new ConcurrentWork(16)) {
                    for (int i = 0; i < 32; i++) {
                        int user = i;
                        work.submit(() -> {
                            harness.flow(mvc, HttpOracleHarness.identity(user % 10), HttpOracleHarness.identity((user + 1) % 10));
                            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
                            assertThat(RequestContextHolder.getRequestAttributes()).isNull();
                            return null;
                        });
                    }
                    work.join(Duration.ofSeconds(90));
                }
                harness.reconcile(false);
            });
        }
    }

    @Test
    void sameWorkerAlternatesAuthenticatedAnonymousInvalidIdentityAndSystemWork() throws Exception {
        try (var harness = new HttpOracleHarness(1); var work = new ConcurrentWork(1)) {
            harness.run((mvc, service) -> {
                work.submit(() -> {
                    harness.flow(mvc, HttpOracleHarness.identity(1), HttpOracleHarness.identity(2));
                    mvc.perform(post("/clientes").with(csrf()).with(anonymous()).param("cpf", "11122233344").param("nome", "DENIED"))
                            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("http://localhost/oauth2/authorization/test"));
                    try {
                        assertThatThrownBy(() -> mvc.perform(post("/clientes").with(csrf())
                            .with(oidcLogin().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")))
                            .param("cpf", "11122233344").param("nome", "INVALID")))
                            .hasRootCauseInstanceOf(IllegalArgumentException.class);
                    } finally {
                        // MockMvc.perform does not restore RequestContextHolder if its filter chain throws.
                        // Model the enclosing servlet-container boundary, absent from standalone MockMvc.
                        RequestContextHolder.resetRequestAttributes();
                    }
                    assertThat(harness.provider.getContext()).isEqualTo(
                            new AuditContext("SISTEMA:ROTINA", "0.0.0.0", HttpOracleHarness.APPLICATION));
                    harness.job(service);
                    harness.flow(mvc, HttpOracleHarness.identity(3), HttpOracleHarness.identity(4));
                    return null;
                }).get(30, TimeUnit.SECONDS);
                work.join(Duration.ofSeconds(10));
                harness.reconcile(false);
            });
        }
    }

    @Test
    void propagatedAuthenticationAndUnpropagatedJobsDoNotContaminateTheExecutor() throws Exception {
        try (var harness = new HttpOracleHarness(1); var work = new ConcurrentWork(1)) {
            var context = SecurityContextHolder.createEmptyContext();
            var token = new OidcIdToken("test", Instant.now(), Instant.now().plusSeconds(60),
                    Map.of("sub", "oidc-ana", "preferred_username", "ana"));
            context.setAuthentication(new OAuth2AuthenticationToken(new DefaultOidcUser(List.of(), token), List.of(), "test"));
            var propagated = new DelegatingSecurityContextCallable<Void>(() -> {
                var expected = new AuditContext("ana", "0.0.0.0", HttpOracleHarness.APPLICATION);
                try (var connection = harness.scenario.pool.getConnection()) {
                    harness.scenario.observe(connection, expected);
                    harness.scenario.operation(connection, expected, false, false);
                }
                return null;
            }, context);
            work.submit(propagated).get(10, TimeUnit.SECONDS);
            work.submit(() -> {
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
                var expected = new AuditContext("SISTEMA:ROTINA", "0.0.0.0", HttpOracleHarness.APPLICATION);
                try (var connection = harness.scenario.pool.getConnection()) {
                    harness.scenario.observe(connection, expected);
                    harness.scenario.operation(connection, expected, false, false);
                }
                return null;
            }).get(10, TimeUnit.SECONDS);
            work.join(Duration.ofSeconds(10)); harness.scenario.reconcile();
        }
    }
}
