# Oracle — contrato de sessão e scripts

## Schema novo

1. Crie o schema de aplicação com `CREATE SESSION`, `CREATE TABLE`, `CREATE TRIGGER` e quota suficiente, usando a conta de implantação.
2. Execute `02_audit_tables.sql`.
3. Execute `03_cliente_audit_triggers.sql`.

`01_audit_session.sql` é uma consulta de diagnóstico. Não instala objetos. Não há necessidade de criar Application Context ou package próprio. Os privilégios runtime devem permitir DML no schema e execução dos procedimentos nativos `DBMS_SESSION.SET_IDENTIFIER/CLEAR_IDENTIFIER` e `DBMS_APPLICATION_INFO.SET_CLIENT_INFO/SET_MODULE` conforme a política do ambiente.

## Contrato compatível com o TRE

| Campo de log | Origem |
| --- | --- |
| `NM_USUARIO_APLICACAO` | `CLIENT_IDENTIFIER`, com fallback para `SESSION_USER` |
| `NR_IP_APLICACAO` | `CLIENT_INFO`, depois `IP_ADDRESS`, depois `0.0.0.0`; até 45 caracteres |
| `NM_APLICATIVO` | `MODULE` |

A aplicação configura os três campos no checkout e limpa no fechamento da mesma conexão. Commit e rollback não limpam automaticamente a sessão. A trigger grava na transação do DML, sem transação autônoma. Sessões SQL diretas sem metadados da aplicação continuam funcionando com os fallbacks do TRE.

`AUDIT_LOG` é a estrutura demonstrativa. Aplicações com tabelas `LOG_*` podem manter seu modelo e usar o mesmo contrato. A trigger atual cobre INSERT, UPDATE e DELETE de CLIENTE. SELECT requer mecanismo separado.

## Atualizar uma base da POC anterior

Esta é uma mudança de contrato da POC ainda não publicada como versão estável. Para uma base que já usa CPF/nome/request ID:

1. Pare a aplicação anterior e preserve um backup conforme o procedimento local.
2. Execute `migrations/01_from_application_context.sql` uma única vez.
3. Execute `03_cliente_audit_triggers.sql` para substituir a trigger.
4. Publique a aplicação nova e verifique novos eventos com usuário, IP e aplicativo.

Não execute `02_audit_tables.sql` numa base existente. A migração adiciona os campos do contrato de sessão, permite NULL nos campos antigos e preserva todos os valores históricos de CPF/nome/request ID. Os campos novos permanecem NULL nos eventos anteriores; a aplicação não inventa metadados históricos. O package/contexto anterior pode permanecer instalado, mas não é mais utilizado. DDL Oracle possui commits implícitos: os passos não constituem uma transação única.

Os testes Oracle de migração e scripts devem usar schema exclusivo: criam e removem `CLIENTE`, `AUDIT_LOG` e `AUDIT_TEST_*`.
