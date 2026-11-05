# Status da implementação

Este documento diferencia estrutura criada de funcionalidade efetivamente implementada e validada.

| Área | Estado | Critério para conclusão |
| --- | --- | --- |
| Estrutura Maven multi-module | Implementada inicialmente | Build Maven validado em CI |
| Contratos core | Implementados inicialmente | Testes unitários executados |
| Auto-configuração | Implementada inicialmente | Testes de contexto Spring |
| Anotação `@Audited` e limite transacional | Implementados inicialmente | Testes de propagação e rollback |
| Gerenciamento do contexto Oracle | Implementado inicialmente | Testes reais com Oracle 19c |
| Scripts PL/SQL e triggers DML | Implementados para CLIENTE | Executar scripts em Oracle 19c e validar JSON/rollback |
| Auditoria de SELECT via Oracle FGA | Pendente | Criar política FGA e integrar trail ao painel sem misturar com triggers DML |
| Aplicação Thymeleaf | Implementada inicialmente | Executar login, CRUD e consulta de logs |
| Keycloak demo | Realm e client versionados | Importação real, login OIDC e roles |
| Docker Compose | Oracle 19c e Keycloak declarados | Subir em checkout limpo com acesso ao registry |
| Documentação | READMEs e guias iniciais | Revisão completa após execução do ambiente |

## Portões de qualidade

Não declarar o starter pronto para produção antes de comprovar:

1. JPA e JDBC utilizam a mesma sessão Oracle na mesma transação.
2. O contexto é definido antes do primeiro DML auditável.
3. O contexto é removido antes da conclusão da operação e reutilização da conexão.
4. Uma falha de limpeza impede a reutilização da conexão contaminada.
5. `REQUIRES_NEW` não remove ou sobrescreve o contexto da transação suspensa.
6. Requisições concorrentes não compartilham identidade.
7. Rollback desfaz tanto a alteração de negócio quanto a linha de auditoria, conforme a política padrão.
8. Identidade ausente falha de forma segura nas operações que exigem auditoria.
9. O mapper OIDC publica os claims CPF e roles esperados.
10. A consulta de auditoria fica restrita às authorities adequadas.

## Validação pendente

O build ainda não foi executado neste ambiente de trabalho. A integração contínua foi adicionada para executar `mvn clean verify` com Java 21 em pushes e pull requests. A execução efetiva e os testes de Oracle devem ser confirmados antes de qualquer release.
