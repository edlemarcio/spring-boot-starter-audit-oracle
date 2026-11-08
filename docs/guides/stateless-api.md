# Integração em API stateless com JWT

A aplicação valida assinatura, issuer e audience usando Spring Security Resource Server. O starter permanece independente de Spring Security; o provider pertence à aplicação.

Implemente `AuditContextProvider` com a mesma resolução do TRE. Exemplo de adaptador para uma API servlet:

```java
@Component
public class JwtAuditContextProvider implements AuditContextProvider {
    private final String application;
    private final String userClaim;
    private final AuditContext systemContext;

    public JwtAuditContextProvider(AuditOracleProperties properties) {
        this.application = properties.getApplication();
        this.userClaim = properties.getUserClaim();
        this.systemContext = new AuditContext(properties.getSystemUser(), "0.0.0.0", application);
    }

    @Override
    public AuditContext getContext() {
        var attributes = RequestContextHolder.getRequestAttributes();
        var request = attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            if (request != null) {
                throw new IllegalStateException("Requisição sem usuário autenticado");
            }
            return systemContext;
        }
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new IllegalStateException("Autenticação JWT esperada");
        }
        return new AuditContext(jwt.getToken().getClaimAsString(userClaim),
                request == null ? "0.0.0.0" : request.getRemoteAddr(), application);
    }
}
```

Configure `audit.oracle.application`, `user-claim` (padrão `sub`) e `system-user` (padrão `SISTEMA:ROTINA`). Não use parâmetros HTTP como identidade. Não substitua um claim ausente por nome de exibição ou identidade técnica. Uma identidade propagada para execução sem HTTP é preservada.

No exemplo OIDC, o adaptador equivalente está em `audit-oracle-example/.../SecurityAuditContextProvider.java` e também suporta principal JWT.

Use as transações normais do Spring:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void registrarOperacaoIndependente() {
    // DML de negócio, com auditoria na mesma transação.
}
```

A auditoria acompanha o pool, inclusive comandos JDBC sem anotação de serviço. Não há requisito de interceptação AOP para configurar os metadados. A anotação transacional continua sujeita às regras normais do proxy Spring.

Configure proxies autorizados no servidor para que `getRemoteAddr` represente o cliente real. O provider não deve interpretar diretamente cabeçalhos encaminhados arbitrários. Reserve `CLIENT_INFO` ao IP e utilize sempre o `AuditDataSource` registrado pelo starter.
