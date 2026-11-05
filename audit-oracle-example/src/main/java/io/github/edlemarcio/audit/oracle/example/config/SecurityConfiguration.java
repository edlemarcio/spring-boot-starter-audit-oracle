package io.github.edlemarcio.audit.oracle.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain applicationSecurity(
            HttpSecurity http,
            OAuth2AuthorizationRequestResolver authorizationRequestResolver,
            org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper authoritiesMapper
    ) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/error", "/css/**", "/images/**").permitAll()
                        .requestMatchers("/auditoria/**").hasAnyRole("AUDIT_VIEWER", "ADMIN")
                        .requestMatchers("/clientes/**").hasAnyRole("USER", "ADMIN")
                        .anyRequest().authenticated())
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(endpoint ->
                                endpoint.authorizationRequestResolver(authorizationRequestResolver))
                        .userInfoEndpoint(userInfo -> userInfo.userAuthoritiesMapper(authoritiesMapper)))
                .logout(logout -> logout.logoutSuccessUrl("/"))
                .build();
    }

    @Bean
    OAuth2AuthorizationRequestResolver authorizationRequestResolver(
            ClientRegistrationRepository registrations
    ) {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(
                registrations, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }

    @Bean
    org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper userAuthoritiesMapper() {
        return authorities -> {
            Set<GrantedAuthority> mapped = new HashSet<>(authorities);
            for (GrantedAuthority authority : authorities) {
                if (authority instanceof OidcUserAuthority oidcAuthority) {
                    Object realmAccessClaim = oidcAuthority.getIdToken().getClaim("realm_access");
                    if (realmAccessClaim instanceof Map<?, ?> realmAccess
                            && realmAccess.get("roles") instanceof Collection<?> roles) {
                        roles.stream()
                                .filter(String.class::isInstance)
                                .map(String.class::cast)
                                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                                .forEach(mapped::add);
                    }
                }
            }
            return mapped;
        };
    }
}
