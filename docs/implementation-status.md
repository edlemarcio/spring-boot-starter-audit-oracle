# Status da implementação

Este documento diferencia estrutura criada de funcionalidade efetivamente implementada e validada.

| Área | Estado | Critério para conclusão |
| --- | --- | --- |
| Estrutura Maven multi-module | Inicial | Build Maven executado em CI |
| Contratos core | Inicial | Testes unitários e revisão de API |
| Auto-configuração | Inicial | Testes de contexto Spring |
| Contexto Oracle | Pendente | Scripts executados em Oracle 19c |
| Ciclo de vida de conexão/transação | Pendente | Testes de integração, pool e propagação |
| Triggers DML | Pendente | Testes INSERT/UPDATE/DELETE e rollback |
| Aplicação Thymeleaf | Pendente | Login, CRUD e consulta de auditoria funcionando |
| Keycloak demo | Pendente | Realm/client/usuários documentados e importáveis |
| Docker Compose | Pendente | Ambiente iniciado a partir de checkout limpo |
| Documentação final | Em andamento | README global e READMEs de cada módulo completos |

## Portões de qualidade

Não declarar o starter pronto para produção antes de comprovar:

1. JPA e JDBC utilizam a mesma sessão Oracle na mesma transação.
2. O contexto é definido antes do primeiro DML auditável.
3. O contexto é removido antes da conexão ser reutilizada.
4. Uma falha de limpeza impede a reutilização da conexão contaminada.
5. `REQUIRES_NEW` não remove ou sobrescreve o contexto da transação suspensa.
6. Requisições concorrentes não compartilham identidade.
7. Rollback desfaz tanto a alteração de negócio quanto a linha de auditoria, conforme a política padrão.
8. Identidade ausente falha de forma segura nas operações que exigem auditoria.
