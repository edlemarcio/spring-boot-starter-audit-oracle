# Arquitetura

## Responsabilidades

A aplicação autentica o usuário e fornece `AuditContextProvider`. O core valida `AuditContext(user, ip, application)`. A auto-configuração registra `AuditDataSource` como o único pool HikariCP, antes de `DataSourceAutoConfiguration`. O starter não depende de Spring Security, Keycloak, servlet, JPA ou biblioteca AOP.

O exemplo adapta os principais OIDC, JWT e outros principais Spring Security já autenticados. A resolução é feita a cada checkout. O claim padrão é `sub`; usuários com o mesmo nome de exibição continuam distintos. O nome do aplicativo é configuração explícita.

## Mesmo mecanismo do TRE Condenações

`AuditConnection` configura `CLIENT_IDENTIFIER`, `CLIENT_INFO` e `MODULE` com um bloco PL/SQL parametrizado na conexão emprestada. Um proxy JDBC intercepta o fechamento; os demais comandos e exceções JDBC são preservados. Não há mapa global de conexões, ThreadLocal de auditoria, aspecto ou callback transacional.

O contexto é estado da sessão física: commit e rollback não o limpam. Ele deve sobreviver ao flush e ao término da transação, inclusive quando o Hibernate remove filhos fisicamente. A limpeza acontece no `close`, antes da devolução ao HikariCP. Uma transação pendente recebe rollback antes da limpeza e da restauração de autocommit. A auditoria não faz commit do negócio.

Falhas de configuração, rollback ou limpeza exigem evicção. Se a evicção falhar, tenta-se `Connection.abort`. Se ambas falharem, a conexão permanece reservada, evitando devolver uma sessão contaminada. O fechamento é idempotente.

Transações Spring permanecem normais, inclusive `REQUIRES_NEW`. Conexões diferentes recebem contextos independentes. A identidade de uma conexão permanece a do checkout até o fechamento; não se deve trocar de usuário dentro da mesma conexão/transação.

## Segurança e rotinas técnicas

O provider rejeita HTTP sem autenticação válida. Sem HTTP e sem autenticação, ele fornece explicitamente `SISTEMA:ROTINA` e `0.0.0.0`. Uma identidade propagada para execução sem HTTP é mantida. O IP vem de `getRemoteAddr`, após o Tomcat tratar somente proxies configurados como confiáveis.

O usuário tem limite de 64 bytes UTF-8, o aplicativo de 48 bytes UTF-8 e o IP de 45 caracteres. Valores inválidos são rejeitados antes do checkout, sem truncamento de identidade. `CLIENT_INFO` é reservado ao IP da aplicação.

A aplicação é a fronteira de confiança: os atributos de sessão Oracle não autenticam o usuário final. A trigger mantém os fallbacks do TRE para sessões SQL diretas: `SESSION_USER` e `IP_ADDRESS`. A inicialização rejeita um DataSource customizado sem auditoria quando o mecanismo está habilitado.

## Triggers e transações

As triggers usam `SYS_CONTEXT('USERENV', ...)`. Não dependem de Application Context ou package próprio. O exemplo mantém `AUDIT_LOG` com usuário/IP/aplicativo e JSON antes/depois; o TRE mantém suas tabelas `LOG_*`. O contrato de sessão é o mesmo, sem impor um modelo de armazenamento à aplicação consumidora.

A auditoria DML é gravada na transação de negócio; rollback desfaz ambos. SELECT não é coberto por triggers DML e continua fora do escopo. A consulta de logs do exemplo exige as roles `AUDIT_VIEWER` ou `ADMIN` e retorna no máximo 200 eventos.

## Compatibilidade

A implementação mira Java 21, Spring Boot 3.5.x, HikariCP e Oracle 19c. O exemplo usa Spring Data JPA e OIDC padrão. O starter não executa migrações automaticamente. A atualização da POC anterior requer a migração aditiva de `AUDIT_LOG` e a substituição da trigger, com a aplicação anterior parada.
