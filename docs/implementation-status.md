# Status da implementação e validação

A POC utiliza o mesmo ciclo de conexão do TRE Condenações: `AuditDataSource` e `AuditConnection`, com usuário/IP/aplicativo em `CLIENT_IDENTIFIER`, `CLIENT_INFO` e `MODULE`. A API anterior baseada em anotação e contexto próprio foi substituída.

## Cobertura automatizada

| Área | Validação |
| --- | --- |
| Core | 19 casos de IPv4/IPv6, identidades inválidas e limites em bytes sem truncamento |
| Ciclo JDBC | 8 testes de configuração, commit/rollback, fechamento idempotente, trabalho pendente e descarte em falhas |
| Provider antes do checkout | 2 testes de rejeição antes da abertura do pool |
| Auto-configuração | 5 testes de pool único, propriedades/lifecycle, desabilitação, provider ausente e DataSource customizado |
| Spring Security | 9 testes de OIDC/JWT, claim configurável, IP, HTTP anônimo, identidade técnica e troca de usuário |
| Oracle e pool reais | 5 testes de mesma sessão reutilizada, limpeza, IPv6, identidade técnica, flush Hibernate com orphanRemoval, rollback implícito, concorrência e REQUIRES_NEW |
| Exemplo com Oracle | 3 testes de scripts/triggers, CRUD com JpaTransactionManager, JSON antes/depois, rollback, SQL direto e migração preservando eventos antigos |

Os testes Oracle são opcionais por ambiente e exigem `AUDIT_ORACLE_URL`, `AUDIT_ORACLE_USER` e `AUDIT_ORACLE_PASSWORD`. Eles usam um schema exclusivo e criam/removem `AUDIT_TEST_*`, `CLIENTE` e `AUDIT_LOG`. Sem essas variáveis, oito testes são explicitamente ignorados.

## Resultado da execução local em 2026-11-08

- `mvn clean verify`: aprovado com Java 21; 51 testes, zero falhas, zero erros e zero ignorados, incluindo oito testes Oracle.
- Mutação em cópia temporária: inverter a condição de fechamento de `AuditConnection` causou falha em cinco testes. A cópia foi removida; o código validado manteve a condição correta.
- `git diff --check` e referências locais da documentação: sem problemas.

## Ambiente de validação

A validação local utiliza JDK 21 e um Oracle Database Free 23 em contêiner isolado. Os scripts continuam destinados a Oracle 19c, mas a execução na edição 19c ainda deve ser confirmada no ambiente correspondente. O teste do exemplo verifica erros de compilação da trigger antes de executar DML.

A integração contínua executa `mvn clean verify` com Java 21. O login real no Keycloak e a configuração de proxies exigem o smoke test descrito no [guia local](guides/local-development.md).

## Limites de escopo

- O exemplo mantém `AUDIT_LOG`; os consumidores mantêm suas tabelas de auditoria e usam o contrato de sessão.
- SELECT não é auditado pelas triggers DML. FGA/política de leitura permanece pendente.
- A auditoria pertence à transação de negócio e também é revertida em rollback.
- Sessões SQL diretas usam os fallbacks do TRE; os metadados fornecidos pela aplicação dependem da autenticação validada por ela.
- A migração da base anterior é aditiva, exige parada coordenada e não reatribui eventos históricos.
- A POC ainda não foi publicada como versão estável.
