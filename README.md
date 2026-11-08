# Spring Boot Starter Audit Oracle

Propaga usuário, IP e aplicativo para a sessão Oracle na mesma conexão JDBC usada pelo JPA/JDBC. A implementação segue a correção do TRE Condenações: um único pool HikariCP auditado, metadados configurados a cada obtenção de conexão e limpeza antes de devolvê-la ao pool.

## Contrato de sessão

| Informação | Oracle | Origem no exemplo |
| --- | --- | --- |
| Usuário | `USERENV.CLIENT_IDENTIFIER` | Claim `sub` do principal OIDC/JWT autenticado; configurável |
| IP | `USERENV.CLIENT_INFO` | `HttpServletRequest.getRemoteAddr()` após tratamento dos proxies confiáveis |
| Aplicativo | `USERENV.MODULE` | `audit.oracle.application` |

O starter fornece `AuditContext(user, ip, application)` e `AuditContextProvider`. A aplicação fornece um bean do provider usando sua autenticação já validada. Não há dependência de Spring Security, Keycloak ou interface web no starter.

## Uso

Adicione `audit-oracle-spring-boot-starter` e forneça um `AuditContextProvider`. Configure a conexão normalmente com `spring.datasource.*` e `spring.datasource.hikari.*`. O starter cria um `AuditDataSource`, que é o próprio pool HikariCP, preservando suas configurações e seu encerramento.

```yaml
audit:
  oracle:
    enabled: true
    application: minha-aplicacao
    user-claim: sub
    system-user: 'SISTEMA:ROTINA'
```

`application`, `user-claim` e `system-user` são usados pelo adaptador de identidade da aplicação. O exemplo implementa esse adaptador em `SecurityAuditContextProvider`. Veja o [guia de API stateless](docs/guides/stateless-api.md).

Os serviços usam as transações normais do Spring:

```java
@Transactional
public Cliente salvar(Cliente cliente) {
    return repository.save(cliente);
}
```

A auditoria é automática em cada conexão obtida do pool, inclusive fora de um método transacional. `@Transactional` continua definindo a atomicidade do negócio. `REQUIRES_NEW` usa outra conexão; não há anotação de auditoria, aspecto ou sincronização transacional própria.

## Ciclo da conexão

1. O provider resolve e valida o contexto antes de obter uma conexão do pool.
2. Um bloco PL/SQL parametrizado executa `DBMS_SESSION.SET_IDENTIFIER`, `DBMS_APPLICATION_INFO.SET_CLIENT_INFO` e `SET_MODULE` nessa conexão.
3. O contexto permanece durante os comandos, flush do Hibernate, commit ou rollback.
4. No fechamento, trabalho pendente é desfeito antes de limpar os três campos. Só então a conexão retorna ao pool.
5. Falhas de configuração, rollback ou limpeza descartam a conexão. Se descarte e abort falharem, ela não é devolvida ao pool.

A identidade é validada sem truncamento: usuário até 64 bytes UTF-8, aplicativo até 48 bytes UTF-8 e IP literal IPv4/IPv6 até 45 caracteres. `CLIENT_INFO` fica reservado ao IP.

## Identidade e proxies no exemplo

- O claim padrão é `sub`, não o nome de exibição ou o CPF. `AUDIT_USER_CLAIM` permite escolher outro claim único confiável.
- Requisições HTTP sem autenticação válida não podem obter conexão usando uma identidade técnica como alternativa.
- Sem HTTP e sem autenticação, o provider usa `SISTEMA:ROTINA`, IP `0.0.0.0` e o aplicativo configurado. Autenticação propagada para uma rotina é preservada.
- O provider consulta os contextos do Spring a cada conexão; não armazena o último usuário nem cria um ThreadLocal de auditoria.
- O exemplo utiliza o tratamento nativo de proxies do Tomcat. Configure `TRUSTED_PROXY_REGEX` com os proxies autorizados. O padrão aceita apenas loopback. O provider não lê diretamente `X-Forwarded-For` ou `X-Real-IP`.

Um DataSource customizado deve ser um `AuditDataSource`; com auditoria habilitada, a inicialização rejeita pools que contornariam o mecanismo. Para testes H2, use explicitamente `audit.oracle.enabled=false`.

## Banco

Não é necessário criar package ou Application Context próprio. As triggers leem `USERENV`, como no TRE:

```sql
NVL(SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER'), SYS_CONTEXT('USERENV', 'SESSION_USER'))
```

O IP prioriza `CLIENT_INFO`, depois `IP_ADDRESS`, depois `0.0.0.0`; o aplicativo vem de `MODULE`. Sessões SQL diretas mantêm os fallbacks para usuário/IP do banco.

Em um schema novo, execute `database/oracle-19c/02_audit_tables.sql` e `03_cliente_audit_triggers.sql`. O script `01_audit_session.sql` é apenas uma consulta de diagnóstico.

O exemplo mantém uma tabela `AUDIT_LOG` para demonstrar o contrato e exibir usuário, IP, aplicativo e JSON antes/depois. Aplicações como o TRE continuam usando suas próprias tabelas `LOG_*` e triggers. A auditoria DML pertence à mesma transação do negócio: rollback desfaz ambos. SELECT exige mecanismo complementar.

Para atualizar uma base da POC anterior, siga a [migração de banco](database/oracle-19c/README.md). A migração preserva os registros históricos e seus campos antigos.

## Executar o exemplo

Pré-requisitos: JDK 21, Maven 3.9+, Docker e acesso à imagem Enterprise Oracle 19c no Oracle Container Registry.

```bash
cp env.example .env
# Defina as senhas locais em .env.
docker login container-registry.oracle.com
docker compose up -d
```

Crie o usuário `audit_app` no PDB com os privilégios de implantação necessários (`CREATE SESSION`, `CREATE TABLE`, `CREATE TRIGGER` e quota no tablespace). A implantação não exige mais `CREATE ANY CONTEXT` ou package próprio. Separe as permissões de implantação das permissões runtime conforme seu ambiente.

Aplique os scripts de banco. O Compose importa o realm `audit-demo` no Keycloak. Defina as senhas dos usuários no Admin Console; as roles `USER`, `AUDIT_VIEWER` e `ADMIN` controlam acesso ao CRUD e à consulta de auditoria.

```bash
set -a
source .env
set +a
mvn -pl audit-oracle-example -am install -DskipTests
mvn -pl audit-oracle-example spring-boot:run
```

Abra http://localhost:8080. Consulte o [guia local](docs/guides/local-development.md) para o smoke test.

## Build e testes

```bash
mvn clean verify
```

Os testes Oracle são habilitados por `AUDIT_ORACLE_URL`, `AUDIT_ORACLE_USER` e `AUDIT_ORACLE_PASSWORD`. Utilize um schema exclusivo de testes: eles criam e removem `AUDIT_TEST_*`, `CLIENTE` e `AUDIT_LOG`.

```bash
export AUDIT_ORACLE_URL='jdbc:oracle:thin:@//localhost:1521/ORCLPDB1'
export AUDIT_ORACLE_USER='SCHEMA_EXCLUSIVO_DE_TESTES'
export AUDIT_ORACLE_PASSWORD='SENHA_DO_SCHEMA_DE_TESTES'
mvn clean verify
```

A suíte cobre validação, identidade OIDC/JWT, pool único, configuração Hikari, falhas e descarte, reutilização da sessão, IPv6, concorrência, `REQUIRES_NEW`, rollback, flush com `orphanRemoval`, CRUD via `JpaTransactionManager`, scripts Oracle e migração dos logs antigos. Sem as variáveis, os testes Oracle são explicitamente ignorados.

## Módulos e documentação

- [Core](audit-oracle-core/README.md): contrato independente de framework.
- [Auto-configuração](audit-oracle-spring-boot-autoconfigure/README.md): pool e ciclo da conexão.
- [Starter](audit-oracle-spring-boot-starter/README.md): dependência de conveniência.
- [Exemplo](audit-oracle-example/README.md): autenticação e interface.
- [Arquitetura](docs/architecture.md) e [status de validação](docs/implementation-status.md).

## Migração da API da POC

A API anterior foi substituída: implemente `AuditContextProvider` em lugar de `AuditIdentityProvider`, retorne usuário/IP/aplicativo em lugar de CPF/nome/request ID e use `@Transactional` em lugar de `@Audited`. As propriedades de namespace/procedures não fazem mais parte da configuração.

## Licença

A licença será formalizada antes da primeira publicação estável.
