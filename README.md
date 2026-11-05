# Spring Boot Starter Audit Oracle

Biblioteca para propagar a identidade autenticada de uma aplicação Spring Boot até o contexto de sessão do Oracle Database, permitindo que triggers registrem o usuário responsável pelas operações de persistência.

> **Status:** implementação inicial em andamento. A estrutura, contratos básicos, anotação transacional, scripts Oracle demonstrativos e aplicação web inicial estão versionados. A solução ainda precisa de build validado e testes de integração Oracle/concor­rência antes de qualquer uso produtivo.

## Objetivos

- Registrar CPF, nome e identificador de requisição nas auditorias produzidas por triggers Oracle.
- Integrar a definição e a limpeza do contexto ao ciclo de vida transacional.
- Evitar vazamento de identidade entre requisições quando conexões são reutilizadas pelo HikariCP.
- Manter o starter independente do provedor de identidade e da interface da aplicação.
- Demonstrar a solução em uma aplicação web separada.

## Princípios arquiteturais

1. O starter não depende de Keycloak, Thymeleaf ou de um cliente OAuth2 específico.
2. A aplicação consumidora fornece a identidade autenticada por meio de `AuditIdentityProvider`.
3. A identidade deve vir de um principal autenticado e validado; nunca de parâmetros HTTP arbitrários.
4. O contexto Oracle é estado de sessão e não é limpo automaticamente por COMMIT/ROLLBACK.
5. O contexto é associado à conexão JDBC vinculada à transação e removido antes do encerramento da operação auditada.
6. Falhas de limpeza devem invalidar a conexão para impedir seu retorno contaminado ao pool.
7. O padrão de auditoria DML grava na mesma transação da operação de negócio.

## Módulos

| Módulo | Responsabilidade | Dependências de domínio |
| --- | --- | --- |
| `audit-oracle-core` | Contratos e tipos mínimos independentes de framework | Nenhuma |
| `audit-oracle-spring-boot-autoconfigure` | Integração Spring, configuração e ciclo de vida Oracle | Spring Boot, JDBC, TX, AOP |
| `audit-oracle-spring-boot-starter` | Dependência de conveniência | Auto-configuração |
| `audit-oracle-example` | Aplicação web demonstrativa | Thymeleaf, JPA, Spring Security OIDC |
| `database/oracle-19c` | Package, Application Context, tabelas e triggers | Oracle 19c |
| `infrastructure/keycloak` | IdP usado exclusivamente no ambiente local | Keycloak |

## Arquitetura

```text
Aplicação consumidora
  ├── autenticação escolhida pela aplicação
  ├── AuditIdentityProvider
  └── serviços anotados com @Audited
          |
          v
Spring Boot Starter Audit Oracle
  ├── TransactionTemplate
  ├── OracleAuditContextManager
  ├── contexto associado à Connection JDBC
  └── limpeza / invalidação em caso de falha
          |
          v
Oracle Database
  ├── Application Context CTX_AUDITORIA
  ├── PKG_AUDITORIA_CONTEXT
  ├── trigger DML
  └── AUDIT_LOG
```

O starter não interpreta JWT e não autentica usuários. A aplicação fornece um `AuditIdentityProvider`. O exemplo implementa esse contrato a partir de um `OidcUser` autenticado pelo Spring Security.

### Limite do Keycloak

Keycloak é somente o provedor OIDC do ambiente de demonstração. O starter e seus módulos não dependem de SDK, adapter, classe, configuração ou API específica do Keycloak.

## Como funciona a transação auditada

O consumidor anota métodos de serviço que executam DML com `@Audited`. A anotação abre a transação por meio do `PlatformTransactionManager`, obtém a identidade do provider, define o contexto na conexão vinculada à transação, executa o método e limpa o contexto antes da conclusão da operação.

Exemplo:

```java
@Service
public class ClienteService {

    @Audited
    public Cliente salvar(Cliente cliente) {
        return repository.save(cliente);
    }
}
```

A anotação `@Audited` é dona do limite transacional do método. Não combine `@Audited` e uma segunda anotação `@Transactional` conflitante no mesmo método. Configure propagação com `@Audited(propagation = Propagation.REQUIRES_NEW)` quando necessário.

O gerenciador mantém estado por identidade de objeto da conexão JDBC, não por ThreadLocal. Escopos aninhados na mesma conexão compartilham a identidade; conexões distintas, como em REQUIRES_NEW, mantêm contextos independentes. Esse comportamento precisa ser confirmado pelos testes de integração Oracle antes da versão estável.

## Tecnologias previstas

- Java 21
- Spring Boot 3.5.x
- Spring Data JPA / Hibernate
- Spring JDBC e Spring Transactions
- Spring AOP
- HikariCP
- Oracle Database 19c
- Maven
- JUnit 5
- Thymeleaf (somente no exemplo)
- Spring Security OAuth2 Client / OIDC (somente no exemplo)
- Docker Compose

## Estrutura do repositório

```text
.
├── README.md
├── pom.xml
├── docker-compose.yml
├── env.example
├── audit-oracle-core/
├── audit-oracle-spring-boot-autoconfigure/
├── audit-oracle-spring-boot-starter/
├── audit-oracle-example/
├── database/oracle-19c/
├── infrastructure/keycloak/
└── docs/
```

## Ambiente local

### Pré-requisitos

- JDK 21
- Maven 3.9+
- Docker Engine e Docker Compose plugin
- Acesso ao Oracle Container Registry e aceite dos termos da imagem Enterprise Oracle Database 19c.

### Subir Oracle e Keycloak

```bash
cp env.example .env
# Edite .env e defina senhas locais
docker login container-registry.oracle.com
docker compose up -d
docker compose ps
```

O Compose inicia:
- Oracle Database 19c em `localhost:1521`;
- Keycloak em `http://localhost:8081`.

A imagem Enterprise Oracle 19c exige acesso ao Oracle Container Registry e aceite dos termos aplicáveis. A imagem não é redistribuída por este projeto.

### Preparar o schema Oracle

Conecte ao PDB `ORCLPDB1` com uma conta administrativa. Para um ambiente local de demonstração, crie o usuário e conceda os privilégios necessários. Ajuste senha e tablespace conforme sua instalação:

```sql
CREATE USER audit_app IDENTIFIED BY "CHANGE_ME"
  DEFAULT TABLESPACE USERS
  TEMPORARY TABLESPACE TEMP
  QUOTA UNLIMITED ON USERS;

GRANT CREATE SESSION, CREATE TABLE, CREATE PROCEDURE, CREATE TRIGGER,
      CREATE ANY CONTEXT TO audit_app;
```

Em ambientes reais, prefira separar o usuário de implantação do usuário runtime e conceder apenas os privilégios estritamente necessários. O package confiável deve ser executável somente pelo usuário técnico da aplicação.

Execute na ordem:

1. `database/oracle-19c/01_audit_context.sql`
2. `database/oracle-19c/02_audit_tables.sql`
3. `database/oracle-19c/03_cliente_audit_triggers.sql`

Consulte [database/oracle-19c/README.md](database/oracle-19c/README.md).

### Keycloak

O realm `audit-demo` é importado automaticamente pelo container. O client OIDC `audit-example` usa Authorization Code com PKCE e é configurado como public client no ambiente local.

Usuários importados:

| Usuário | CPF | Permissões |
| --- | --- | --- |
| `usuario.teste` | 11122233344 | USER |
| `auditor.teste` | 22233344455 | USER, AUDIT_VIEWER |
| `admin.teste` | 33344455566 | USER, AUDIT_VIEWER, ADMIN |

Os usuários exigem atualização de senha no primeiro acesso. Defina as senhas pelo Admin Console do Keycloak; nenhuma senha pessoal de teste é armazenada no repositório.

Detalhes: [infrastructure/keycloak/README.md](infrastructure/keycloak/README.md).

### Executar a aplicação de exemplo

A aplicação roda no host para que o redirect OIDC seja resolvido em `localhost`. Exporte as variáveis de `.env`:

```bash
set -a
source .env
set +a
mvn -pl audit-oracle-example -am install -DskipTests
mvn -pl audit-oracle-example spring-boot:run
```

Abra [http://localhost:8080](http://localhost:8080), clique em Entrar e autentique-se com um dos usuários de teste.

### Funcionalidades do exemplo

- Página de boas-vindas Thymeleaf.
- Login OIDC pelo Spring Security.
- CRUD de clientes com INSERT, UPDATE e DELETE auditados.
- Consulta dos eventos na tabela `AUDIT_LOG`, restrita às roles `AUDIT_VIEWER` e `ADMIN`.

A trigger de demonstração captura DML. SELECT não é capturado por trigger DML e não faz parte da auditoria implementada.

## Build e testes

```bash
mvn clean verify
```

Os testes unitários do core e o build Maven são o primeiro gate. Testes reais de rollback, concorrência, `REQUIRES_NEW`, identidade ausente e reutilização HikariCP dependem de Oracle 19c e serão adicionados antes de considerar o starter pronto para produção.

## Documentação adicional

- [Arquitetura e decisões](docs/architecture.md)
- [Status da implementação e critérios de aceite](docs/implementation-status.md)
- [Guia de desenvolvimento local](docs/guides/local-development.md)
- [Oracle 19c e scripts](database/oracle-19c/README.md)
- [Aplicação de exemplo](audit-oracle-example/README.md)
- [Starter](audit-oracle-spring-boot-starter/README.md)
- [Auto-configuração](audit-oracle-spring-boot-autoconfigure/README.md)
- [Core](audit-oracle-core/README.md)
- [Keycloak](infrastructure/keycloak/README.md)

## Segurança e limitações

- CPF e nome são dados pessoais. Defina retenção, controle de acesso, mascaramento e base legal conforme LGPD.
- O package PL/SQL não prova a identidade do usuário final; ele confia na aplicação que o invoca.
- A trigger falha de forma segura quando o contexto está ausente.
- A auditoria DML está na mesma transação do negócio: rollback reverte ambos.
- SELECT exige mecanismo complementar.
- O projeto ainda está em desenvolvimento e não deve ser tratado como biblioteca pronta para produção.

## Licença

A licença será formalizada antes da primeira publicação estável.
