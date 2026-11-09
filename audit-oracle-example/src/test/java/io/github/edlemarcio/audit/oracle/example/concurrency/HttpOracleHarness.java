package io.github.edlemarcio.audit.oracle.example.concurrency;

import io.github.edlemarcio.audit.oracle.autoconfigure.AuditOracleAutoConfiguration;
import io.github.edlemarcio.audit.oracle.autoconfigure.AuditOracleProperties;
import io.github.edlemarcio.audit.oracle.core.AuditContext;
import io.github.edlemarcio.audit.oracle.core.AuditContextProvider;
import io.github.edlemarcio.audit.oracle.example.audit.AuditLogQueryRepository;
import io.github.edlemarcio.audit.oracle.example.audit.SecurityAuditContextProvider;
import io.github.edlemarcio.audit.oracle.example.config.SecurityConfiguration;
import io.github.edlemarcio.audit.oracle.example.customer.*;
import io.github.edlemarcio.audit.oracle.testsupport.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.*;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real application controller/service/provider/filters; only authenticated principals are fixtures. */
final class HttpOracleHarness implements AutoCloseable {
    static final String APPLICATION = "http-example";
    final SecurityAuditContextProvider provider;
    final OracleScenario scenario;
    private final Map<String, String> expected = new ConcurrentHashMap<>();
    private boolean tablesCreated;
    private java.sql.Connection observer;
    interface WebWork { void run(MockMvc mvc, ClienteService service) throws Exception; }

    HttpOracleHarness(int poolSize) throws Exception {
        var properties = new AuditOracleProperties();
        properties.setApplication(APPLICATION); properties.setUserClaim("preferred_username");
        provider = new SecurityAuditContextProvider(properties);
        scenario = new OracleScenario(poolSize, provider);
        scenario.pool.setConnectionTimeout(15_000);
        try {
            try (var connection = scenario.environment.connect(); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME IN ('CLIENTE','AUDIT_LOG')")) {
                result.next();
                assertThat(result.getInt(1)).as("Dedicated example tables must not already exist; no existing application data is dropped").isZero();
            }
            try (var connection = scenario.environment.connect()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("../database/oracle-19c/02_audit_tables.sql")));
                tablesCreated = true;
                try (var statement = connection.createStatement()) {
                    statement.execute(Files.readString(Path.of("../database/oracle-19c/03_cliente_audit_triggers.sql"))
                            .replaceAll("(?m)^/\\s*$", ""));
                }
            }
            observer = scenario.environment.connect();
        } catch (Throwable failure) {
            try { close(); } catch (Throwable cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    void run(WebWork work) {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AuditOracleAutoConfiguration.class, DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class, HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class,
                        WebMvcAutoConfiguration.class, SecurityAutoConfiguration.class, OAuth2ClientAutoConfiguration.class))
                .withUserConfiguration(WebConfiguration.class)
                .withBean(DataSource.class, () -> scenario.pool)
                .withBean(AuditContextProvider.class, () -> provider)
                .withBean(ClientRegistrationRepository.class, HttpOracleHarness::registrations)
                .withPropertyValues("spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext()).apply(springSecurity()).build();
                    work.run(mvc, context.getBean(ClienteService.class));
                });
    }

    static AuditContext identity(int user) {
        return new AuditContext("subject-" + user, user % 2 == 0 ? "2001:db8::" + (user + 1) : "192.0.2." + (user + 1), APPLICATION);
    }

    MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request, AuditContext identity) {
        if ((identity.user().hashCode() & 1) == 0) {
            var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("validated-test-token")
                    .header("alg", "RS256").subject("jwt-" + identity.user())
                    .claim("preferred_username", identity.user()).claim("name", "Same display name").build();
            var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                    jwt, null, java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")));
            return request.with(authentication(authentication)).with(csrf())
                    .with(raw -> { raw.setRemoteAddr(identity.ip()); return raw; });
        }
        return request.with(oidcLogin().idToken(token -> token.subject("oidc-" + identity.user())
                        .claim("preferred_username", identity.user()).claim("name", "Same display name"))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"))).with(csrf())
                .with(raw -> { raw.setRemoteAddr(identity.ip()); return raw; });
    }

    void flow(MockMvc mvc, AuditContext first, AuditContext second) throws Exception {
        String token = UUID.randomUUID().toString();
        String initial = token + "-I", updated = token + "-U";
        expected.put(initial + "|INSERT", encoded(first));
        mvc.perform(authenticated(post("/clientes").param("cpf", "11122233344").param("nome", initial), first))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/clientes"));
        long id = findId(initial);
        expected.put(updated + "|UPDATE", encoded(second));
        mvc.perform(authenticated(post("/clientes").param("id", String.valueOf(id)).param("cpf", "11122233344").param("nome", updated), second))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/clientes"));
        expected.put(updated + "|DELETE", encoded(first));
        mvc.perform(authenticated(post("/clientes/" + id + "/excluir"), first))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/clientes"));
        scenario.report.count("httpFlows");
    }

    private synchronized long findId(String initial) throws Exception {
        // A single independent observer avoids a logon storm unrelated to the application's pool.
        try (var statement = observer.prepareStatement("SELECT ID FROM CLIENTE WHERE NOME=?")) {
            statement.setString(1, initial);
            try (var result = statement.executeQuery()) { assertThat(result.next()).isTrue(); return result.getLong(1); }
        }
    }

    void job(ClienteService service) {
        String name = UUID.randomUUID() + "-JOB";
        expected.put(name + "|INSERT", encoded(new AuditContext("SISTEMA:ROTINA", "0.0.0.0", APPLICATION)));
        Cliente result = service.salvar(new Cliente("11122233344", name, null));
        expected.put(name + "|DELETE", encoded(new AuditContext("SISTEMA:ROTINA", "0.0.0.0", APPLICATION)));
        service.excluir(result.getId());
    }

    synchronized void reconcile(boolean reset) throws Exception {
        var actual = new HashMap<String, String>();
        try (var statement = observer.createStatement();
             var result = statement.executeQuery("SELECT OPERATION, NM_USUARIO_APLICACAO, NR_IP_APLICACAO, NM_APLICATIVO, OLD_VALUES, NEW_VALUES FROM AUDIT_LOG")) {
            while (result.next()) {
                String operation = result.getString(1);
                var values = JsonParserFactory.getJsonParser().parseMap(result.getString(operation.equals("DELETE") ? 5 : 6));
                String key = values.get("nome") + "|" + operation;
                String identity = result.getString(2) + "|" + result.getString(3) + "|" + result.getString(4);
                assertThat(actual.put(key, identity)).as("duplicate HTTP audit event %s", key).isNull();
                if (!identity.equals(expected.get(key))) scenario.report.trace(Map.of("operation", key,
                        "actual", identity, "expected", String.valueOf(expected.get(key))));
            }
        }
        scenario.report.add("httpAuditEventsVerified", actual.size());
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
        if (reset) {
            scenario.execute("DELETE FROM AUDIT_LOG");
            expected.clear();
        }
    }

    private static String encoded(AuditContext identity) { return identity.user() + "|" + identity.ip() + "|" + identity.application(); }

    private static ClientRegistrationRepository registrations() {
        return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("test")
                .clientId("test").clientSecret("test").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}").scope("openid")
                .authorizationUri("http://localhost/unused/authorize").tokenUri("http://localhost/unused/token")
                .jwkSetUri("http://localhost/unused/jwks").userInfoUri("http://localhost/unused/userinfo")
                .userNameAttributeName("sub").build());
    }

    @Override public void close() throws Exception {
        try {
            if (observer != null) observer.close();
            if (tablesCreated) {
                scenario.execute("DROP TABLE CLIENTE PURGE"); scenario.execute("DROP TABLE AUDIT_LOG PURGE");
            }
        } finally { scenario.close(); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EntityScan(basePackageClasses = Cliente.class)
    @EnableJpaRepositories(basePackageClasses = ClienteRepository.class)
    @Import({ClienteController.class, ClienteService.class, AuditLogQueryRepository.class, SecurityConfiguration.class})
    static class WebConfiguration { }
}
