# audit-oracle-core

## Responsabilidade

Módulo mínimo e independente de framework. Contém somente os tipos e contratos que representam a identidade de auditoria.

## Limites

- Não depende de Spring, Spring Security, JPA, JDBC, Oracle ou Keycloak.
- Não extrai claims de JWT.
- Não autentica usuários.
- Não executa SQL nem controla transações.

A aplicação consumidora (ou a integração Spring do starter) fornece uma implementação do contrato de identidade usando o mecanismo de autenticação que já adota.

## Contratos

- `AuditIdentity`: CPF, nome e identificador de correlação.
- `AuditIdentityProvider`: fonte da identidade autenticada para a operação atual.
