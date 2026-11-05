# Oracle 19c — schema e auditoria

## Ordem de execução

Execute os scripts no schema da aplicação, ajustando owner e grants conforme o ambiente:

1. `01_audit_context.sql` — package PL/SQL e Application Context.
2. `02_audit_tables.sql` — tabela de exemplo e tabela de auditoria.
3. `03_cliente_audit_triggers.sql` — trigger DML demonstrativa.

## Application Context

O namespace `CTX_AUDITORIA` contém `CPF`, `NOME` e `REQUEST_ID`. O package confiável valida os parâmetros antes de definir o contexto.

O contexto é estado da sessão Oracle: COMMIT e ROLLBACK não o removem. A aplicação precisa limpar o contexto antes de devolver a conexão ao HikariCP. Se a limpeza falhar, a conexão deve ser descartada.

## Segurança

- Execute os scripts com um usuário de implantação controlado.
- Conceda EXECUTE no package somente ao usuário técnico que precisa definir o contexto.
- Não conceda acesso irrestrito ao package a usuários finais.
- O package não autentica o usuário final. A aplicação deve obter a identidade de um principal autenticado e confiável.
- A trigger falha de forma fechada quando os atributos de auditoria não estão presentes.
- A trigger registra DML na mesma transação; rollback também reverte o registro de auditoria.

## Escopo

A trigger de exemplo audita INSERT, UPDATE e DELETE da tabela CLIENTE. SELECT não é capturado por triggers DML e requer mecanismo separado.
