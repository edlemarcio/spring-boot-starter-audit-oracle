# Keycloak — ambiente de demonstração

Keycloak é utilizado exclusivamente como provedor de identidade do ambiente local. Ele autentica usuários de teste e emite tokens OIDC.

## Limite arquitetural

Nenhum módulo do starter depende do Keycloak. A aplicação exemplo usa Spring Security OAuth2 Client e configuração OIDC padrão.

## Configuração planejada

- Realm: `audit-demo`
- Client: `audit-example`
- Flow: Authorization Code
- PKCE: S256
- Claims necessários: `cpf` e `name`
- Roles demonstrativas: `USER`, `AUDIT_VIEWER`, `ADMIN`

O arquivo de exportação do realm e as instruções de criação manual do client e dos usuários serão adicionados junto ao Docker Compose. Senhas de teste serão configuráveis localmente e não serão credenciais de produção.
