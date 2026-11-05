# audit-oracle-example

Aplicação web demonstrativa do starter.

## Funcionalidades planejadas

- Página de boas-vindas Thymeleaf.
- Login OIDC usando Spring Security OAuth2 Client.
- CRUD de clientes com Spring Data JPA.
- Tela de consulta dos eventos de auditoria.
- Exibição de CPF, nome, operação, tabela, chave do registro, instante e correlation ID, conforme o modelo de auditoria.

## Arquitetura

A aplicação é a responsável pela autenticação. No ambiente local, o Keycloak emite tokens OIDC/JWT; a integração ocorre por protocolos e APIs padrão do Spring Security. O código da aplicação não usará SDK ou adapter específico do Keycloak.

A aplicação fornece a implementação de `AuditIdentityProvider`, adaptando o principal autenticado aos campos de auditoria. Essa implementação pertence somente a este módulo.

## Dependências

Além do starter, a aplicação utiliza Spring MVC, Thymeleaf, Spring Data JPA, Spring Security OAuth2 Client e driver JDBC Oracle. Nenhuma dessas dependências de interface/autenticação é transitiva do starter.

## Execução

Consulte o [README global](../README.md) e o [guia de desenvolvimento local](../docs/guides/local-development.md). O módulo deve ser compilado junto com suas dependências Maven antes de executar o Spring Boot plugin.
