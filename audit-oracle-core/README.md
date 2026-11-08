# audit-oracle-core

Contrato mínimo independente de Spring, Spring Security, JDBC, JPA, Oracle e Keycloak.

- `AuditContext(user, ip, application)`: usuário único, IP literal IPv4/IPv6 e identificador do aplicativo.
- `AuditContextProvider.getContext()`: resolve o contexto confiável de cada obtenção de conexão.

O usuário admite até 64 bytes UTF-8, o aplicativo até 48 bytes e o IP até 45 caracteres. Identidades vazias, caracteres de controle e valores acima do limite são rejeitados, sem truncamento. IPs são literais; não são resolvidos nomes DNS.

A implementação do provider pertence à aplicação. Ela deve consultar a autenticação atual, recusar HTTP anônimo e fornecer identidade técnica explícita para rotinas sem usuário. `AuditContext.UNAVAILABLE_IP` representa ausência de IP HTTP (`0.0.0.0`).
