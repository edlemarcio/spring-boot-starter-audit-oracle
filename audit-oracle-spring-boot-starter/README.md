# audit-oracle-spring-boot-starter

## Responsabilidade

Dependência de conveniência que disponibiliza a auto-configuração do Audit Oracle em aplicações Spring Boot.

## Uso previsto

A aplicação adicionará esta dependência e fornecerá um `AuditIdentityProvider` compatível com seu contexto de autenticação.

## Independência

Este módulo não inclui:

- Keycloak adapters ou SDKs;
- Spring Security OAuth2 Client;
- Thymeleaf;
- componentes da aplicação de demonstração;
- configuração de realm, usuários ou client OIDC.

O provedor de identidade permanece uma decisão da aplicação consumidora.
