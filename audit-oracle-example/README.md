# audit-oracle-example

Demonstra o contrato de auditoria do TRE: usuário em `CLIENT_IDENTIFIER`, IP em `CLIENT_INFO` e aplicativo em `MODULE`. Usa login OIDC, CRUD JPA e consulta dos 200 eventos DML mais recentes, incluindo valores anteriores e novos em JSON.

`SecurityAuditContextProvider` implementa o contrato da aplicação. Ele lê o principal validado pelo Spring Security e o IP de `getRemoteAddr`, sem armazenar o usuário anterior. Suporta OIDC, JWT e outros principais autenticados. O claim padrão é `sub`, configurável por `AUDIT_USER_CLAIM`; o aplicativo usa `AUDIT_APPLICATION`.

HTTP sem autenticação válida é rejeitado. Rotinas sem HTTP e sem autenticação usam `SISTEMA:ROTINA` e `0.0.0.0`; autenticação propagada é mantida. Configure `TRUSTED_PROXY_REGEX` para os proxies autorizados antes de publicar atrás de ingress/proxy reverso.

O CRUD usa `@Transactional`. O pool aplica o contexto a toda conexão, inclusive nas leituras; as triggers registram somente INSERT, UPDATE e DELETE. SELECT continua fora do escopo de auditoria. A consulta de logs exige `AUDIT_VIEWER` ou `ADMIN`.

CPF permanece um campo de negócio de `CLIENTE`; não é obrigatório para identificar o autor da operação. A interface de auditoria exibe usuário, IP e aplicativo. Consulte o [guia local](../docs/guides/local-development.md), o [banco e migração](../database/oracle-19c/README.md) e o [README global](../README.md).
