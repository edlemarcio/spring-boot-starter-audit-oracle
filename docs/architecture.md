# Arquitetura e decisões técnicas

## Separação de responsabilidades

O repositório separa biblioteca reutilizável, aplicação demonstrativa, scripts Oracle e infraestrutura local. O starter não importa classes, configuração ou dependências do exemplo.

## Identidade

A aplicação consumidora fornece `AuditIdentityProvider`. A implementação deve obter CPF, nome e correlation ID de um contexto autenticado e confiável. O starter não interpreta JWT e não conhece Keycloak.

## Sessão Oracle

Oracle Application Context é estado da sessão física, não estado transacional. COMMIT e ROLLBACK não limpam automaticamente os atributos definidos com `DBMS_SESSION.SET_CONTEXT`.

Consequências:

1. O contexto deve ser definido na mesma conexão física usada pela operação auditada.
2. O contexto deve ser removido antes de a conexão ser reutilizada por outra operação.
3. Uma falha de limpeza exige impedir a reutilização daquela conexão.
4. A solução deve considerar propagação transacional, incluindo `REQUIRES_NEW`, e não presumir que um callback genérico de conclusão executa antes da liberação do recurso.

## Transações

A auditoria DML será gravada na mesma transação da operação de negócio. Portanto, o rollback do negócio também reverte o registro feito pela trigger. Não será usada transação autônoma na trigger por padrão.

## Identidade ausente

A política padrão para operações auditáveis será fail-closed: não permitir DML auditável sem contexto de auditoria. Identidades técnicas devem ser explícitas e fornecidas por um provider confiável.

## Auditoria de leitura

Triggers DML registram INSERT, UPDATE e DELETE. SELECT não é capturado por trigger DML de tabela e exigirá solução complementar se fizer parte do escopo.

## Privilegios Oracle

O package confiável do Application Context deve ter privilégios mínimos. A permissão EXECUTE no package permite definir atributos conforme suas regras; ela não constitui prova criptográfica da identidade do usuário final. O acesso ao package deve ficar restrito ao usuário técnico da aplicação.

## Compatibilidade

A primeira linha de implementação mira Java 21, Spring Boot 3.5.x, Spring Data JPA, HikariCP e Oracle 19c. A aplicação de exemplo usará OIDC padrão via Spring Security; Keycloak será somente o IdP do ambiente local.
