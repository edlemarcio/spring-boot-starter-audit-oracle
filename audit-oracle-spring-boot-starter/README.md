# audit-oracle-spring-boot-starter

Dependência de conveniência que inclui a auto-configuração do pool Oracle auditado. A aplicação fornece um `AuditContextProvider` e configura sua conexão em `spring.datasource.*` e `spring.datasource.hikari.*`.

O starter não inclui autenticação, interface web ou configuração do Keycloak. Não exige anotação de auditoria nos serviços: as conexões do `AuditDataSource` transportam usuário/IP/aplicativo automaticamente. O controle transacional continua com `@Transactional`.

Consulte o [README](../README.md) e o [guia stateless](../docs/guides/stateless-api.md).
