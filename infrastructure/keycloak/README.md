# Keycloak — ambiente de demonstração

Keycloak é usado exclusivamente para autenticação e emissão de tokens no ambiente local. Nenhum módulo do starter depende do Keycloak.

## Importação automática

O Docker Compose inicia Keycloak com `--import-realm` e monta `realm/audit-demo-realm.json` no diretório de importação.

- URL: http://localhost:8081
- Realm: `audit-demo`
- Client: `audit-example`
- Redirect URI: `http://localhost:8080/login/oauth2/code/keycloak`
- Flow: OpenID Connect Authorization Code
- PKCE: S256
- Client: public, sem client secret; PKCE será aplicado pela aplicação

## Usuários de teste

| Usuário | CPF | Roles |
| --- | --- | --- |
| `usuario.teste` | 11122233344 | USER |
| `auditor.teste` | 22233344455 | USER, AUDIT_VIEWER |
| `admin.teste` | 33344455566 | USER, AUDIT_VIEWER, ADMIN |

Os usuários são importados com a ação obrigatória `UPDATE_PASSWORD`. Entre no Admin Console e defina uma senha local para cada usuário antes do primeiro login. Não há senhas de usuário versionadas no Git.

## Configuração manual do client

Se não importar o realm, crie o realm `audit-demo` e um client OpenID Connect com:

- Client ID: `audit-example`
- Client authentication: OFF (public client)
- Standard flow: ON
- Direct access grants: OFF
- Valid redirect URI: `http://localhost:8080/login/oauth2/code/keycloak`
- Web origin: `http://localhost:8080`
- PKCE code challenge method: S256

Crie os roles `USER`, `AUDIT_VIEWER` e `ADMIN`, os usuários acima e o mapper de atributo de usuário para o claim `cpf`. Adicione também um mapper de realm roles para publicar `realm_access.roles` no ID Token.

## Segurança

O admin bootstrap padrão e as senhas locais devem ser alterados em qualquer ambiente compartilhado. Não exponha este Keycloak de desenvolvimento na internet.
