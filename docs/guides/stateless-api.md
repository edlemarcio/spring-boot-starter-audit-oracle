# Integração em API stateless com JWT

O starter não depende de Spring Security nem de um provedor OIDC. Em uma API stateless, a própria aplicação valida o JWT com Spring Security Resource Server e fornece um `AuditIdentityProvider`.

Exemplo ilustrativo:

```java
@Component
public class JwtAuditIdentityProvider implements AuditIdentityProvider {

    @Override
    public Optional<AuditIdentity> getCurrentIdentity() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            return Optional.empty();
        }

        Jwt jwt = jwtAuth.getToken();
        String cpf = jwt.getClaimAsString("cpf");
        String name = jwt.getClaimAsString("name");
        String requestId = MDC.get("requestId");

        if (cpf == null || name == null || requestId == null) {
            return Optional.empty();
        }

        return Optional.of(new AuditIdentity(cpf, name, requestId));
    }
}
```

O exemplo acima é um adaptador da aplicação, não uma classe do starter. A aplicação deve configurar o Resource Server para validar assinatura, issuer e audience do token. Os claims de identidade só podem ser utilizados depois dessa validação.

O serviço que executa DML usa `@Audited`:

```java
@Service
public class PedidoService {

    @Audited
    public Pedido atualizar(Pedido pedido) {
        return repository.save(pedido);
    }
}
```

## Regras de uso

- Os métodos anotados devem ser métodos públicos de beans Spring e chamados através do proxy Spring; self-invocation não é interceptada por AOP.
- `@Audited` controla o limite transacional. Não coloque `@Transactional` conflitante no mesmo método.
- Use `@Audited(propagation = Propagation.REQUIRES_NEW)` quando a operação realmente precisar de transação independente.
- O provider deve retornar identidade de usuário técnico explicitamente para rotinas de sistema; não reutilize CPF de usuário anterior.
- Não aceite CPF/nome de headers ou parâmetros HTTP sem validação de identidade.
- O request ID deve ser gerado ou validado pela aplicação e propagado por toda a operação.
