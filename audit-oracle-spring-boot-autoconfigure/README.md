# audit-oracle-spring-boot-autoconfigure

## Responsabilidade

Integra os contratos de `audit-oracle-core` ao ecossistema Spring Boot. Concentra propriedades, auto-configuração, transação auditada e gerenciamento do contexto Oracle.

## API inicial

A aplicação fornece um bean `AuditIdentityProvider`. O starter disponibiliza a anotação `@Audited` para métodos de serviço que executam operações DML:

```java
@Service
public class ClienteService {

    @Audited
    public Cliente salvar(Cliente cliente) {
        return repository.save(cliente);
    }
}
```

A anotação abre a transação usando o `PlatformTransactionManager`, resolve a identidade autenticada, define o contexto na conexão JDBC da transação e executa o método. Uma sincronização limpa o contexto em `afterCompletion`, depois do flush/commit ou rollback e antes da liberação da conexão.

`@Audited` é responsável pelo limite transacional do método. Não coloque uma anotação `@Transactional` conflitante no mesmo método. A propagação é configurada pela própria anotação, por exemplo:

```java
@Audited(propagation = Propagation.REQUIRES_NEW)
public void registrarOperacaoIndependente() {
    // DML auditável
}
```

## Propriedades

```yaml
audit:
  oracle:
    enabled: true
    context-namespace: CTX_AUDITORIA
    set-context-procedure: PKG_AUDITORIA_CONTEXT.SET_CONTEXT
    clear-context-procedure: PKG_AUDITORIA_CONTEXT.CLEAR_CONTEXT
    fail-on-missing-identity: true
```

## Segurança e ciclo de vida

- O starter não conhece Keycloak, JWT, Thymeleaf ou qualquer protocolo de autenticação.
- O provider deve retornar apenas uma identidade autenticada e confiável.
- A identidade é associada ao objeto de conexão JDBC, não a um ThreadLocal.
- Escopos aninhados na mesma conexão preservam a identidade original e só o escopo proprietário limpa o contexto.
- Conexões distintas, como em `REQUIRES_NEW`, recebem contexto independente.
- O contexto Oracle é estado de sessão e deve ser removido antes da conclusão do método auditado.
- Se a sessão já possuir contexto inesperado ou não for possível verificar/limpar o contexto, a conexão é invalidada.
- A integração deve ser validada com o transaction manager e o pool reais da aplicação antes de produção.

## Limitações atuais

O módulo está em desenvolvimento. Ainda faltam testes de integração com Oracle 19c para comprovar JPA/JDBC na mesma sessão, rollback, concorrência, propagação `REQUIRES_NEW`, falha de limpeza e reutilização HikariCP.
