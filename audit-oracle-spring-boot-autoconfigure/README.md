# audit-oracle-spring-boot-autoconfigure

Registra um único `AuditDataSource`, que estende o próprio HikariCP, preservando `spring.datasource.*`, `spring.datasource.hikari.*`, métricas e encerramento. A aplicação deve fornecer um bean `AuditContextProvider`. Sem ele, a inicialização falha.

O provider é consultado antes de obter a conexão. `AuditConnection` limpa o contexto anterior e configura usuário/IP/aplicativo na mesma conexão com `SET_IDENTIFIER`, `SET_CLIENT_INFO` e `SET_MODULE`. Os atributos permanecem durante DML, flush, commit e rollback. O `close` desfaz trabalho pendente, limpa novamente o contexto e devolve a conexão. Falhas de configuração, rollback ou de qualquer uma das limpezas seguem o mecanismo existente de descarte.

Não há anotação própria de auditoria nem controle transacional adicional. Use `@Transactional` e sua propagação normal. O mecanismo também acompanha conexões usadas diretamente por JDBC.

```yaml
audit:
  oracle:
    enabled: true
    application: minha-aplicacao
    user-claim: sub
    system-user: 'SISTEMA:ROTINA'
```

O provider do exemplo usa os três últimos campos para resolver o contexto. Um provider customizado é responsável por respeitar esse contrato. Pools customizados devem ser `AuditDataSource`; uma configuração que contornaria a auditoria é rejeitada. Desabilite explicitamente `audit.oracle.enabled` para H2.

Veja o [contrato de sessão e a configuração operacional](../README.md) e os [testes Oracle](../docs/implementation-status.md).
