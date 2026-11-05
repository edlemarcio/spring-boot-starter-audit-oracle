# Spring Boot Starter Audit Oracle

Biblioteca reutilizável para propagar a identidade autenticada de uma aplicação Spring Boot até o contexto de sessão do Oracle Database, permitindo que triggers registrem o usuário responsável pelas operações de persistência.

> **Status:** implementação inicial.

## Objetivos

- Registrar CPF, nome e identificador de requisição nas auditorias produzidas por triggers Oracle.
- Integrar-se ao ciclo de vida das transações Spring/JPA.
- Evitar vazamento de identidade entre requisições quando conexões são reutilizadas pelo HikariCP.
- Manter o starter independente do provedor de identidade e da interface da aplicação.
- Demonstrar a solução em uma aplicação web separada.

## Princípios arquiteturais

1. O starter não depende de Keycloak, Thymeleaf ou de um cliente OAuth2 específico.
2. A aplicação consumidora fornece a identidade autenticada por meio de um contrato pequeno e extensível.
3. A identidade só é aceita após autenticação e validação pelo mecanismo de segurança da aplicação.
4. O contexto Oracle é estado de sessão; portanto, deve ser inicializado e limpo com controle explícito do ciclo de vida da conexão.
5. A auditoria transacional acompanha o commit/rollback da operação de negócio.
6. Falhas na limpeza não podem devolver uma conexão potencialmente contaminada ao pool.

## Componentes

| Componente | Responsabilidade |
| --- | --- |
| `audit-oracle-core` | Tipos e contratos mínimos, sem dependências de infraestrutura |
| `audit-oracle-spring-boot-autoconfigure` | Configuração automática e integração Spring |
| `audit-oracle-spring-boot-starter` | Dependência de conveniência para consumidores |
| `audit-oracle-example` | Aplicação demonstrativa Thymeleaf, CRUD e consulta de auditoria |
| `database/oracle-19c` | Scripts PL/SQL, contexto Oracle, tabelas e triggers |
| `infrastructure/keycloak` | IdP usado exclusivamente no ambiente de demonstração |
| `integration-tests` | Testes de integração, transação, concorrência e pool |

## Arquitetura de alto nível

```text
Aplicação consumidora
  ├── mecanismo de autenticação escolhido pela aplicação
  ├── contrato AuditIdentityProvider
  └── Spring Data JPA / transações Spring
          |
          v
Spring Boot Starter Audit Oracle
  ├── resolução da identidade autenticada
  ├── coordenação do contexto por transação/conexão
  └── inicialização e limpeza segura da sessão Oracle
          |
          v
Oracle Database
  ├── Application Context
  ├── package PL/SQL confiável
  ├── triggers DML
  └── tabela de auditoria
```

O Keycloak aparece somente no ambiente de demonstração: ele emite tokens OIDC/JWT, enquanto a aplicação exemplo usa os mecanismos padrão do Spring Security. Nenhum módulo do starter depende de bibliotecas ou APIs específicas do Keycloak.

## Tecnologias previstas

- Java 21
- Spring Boot 3.5.x
- Spring Framework / Spring Security
- Spring Data JPA e Hibernate
- HikariCP
- Oracle Database 19c
- Maven
- JUnit 5
- Thymeleaf (somente no projeto de exemplo)
- Docker Compose (ambiente local)

As versões serão centralizadas no POM raiz.

## Estrutura do repositório

```text
.
├── README.md
├── pom.xml
├── audit-oracle-core/
├── audit-oracle-spring-boot-autoconfigure/
├── audit-oracle-spring-boot-starter/
├── audit-oracle-example/
├── database/oracle-19c/
├── infrastructure/keycloak/
├── integration-tests/
├── docs/
└── docker-compose.yml
```

## Ambiente de demonstração

O ambiente local deverá oferecer Oracle Database para persistência e triggers, Keycloak para autenticação e emissão de tokens de teste e uma aplicação Spring Boot com página de boas-vindas, login, CRUD de clientes e consulta dos logs de auditoria.

O realm, os usuários de teste e o client OIDC serão versionados como configuração de demonstração. Credenciais locais não deverão ser credenciais de produção nem segredos reais versionados.

## Execução

Os comandos definitivos serão publicados quando o Docker Compose e os módulos executáveis estiverem implementados. O fluxo desejado:

```bash
docker compose up -d
./mvnw clean verify
./mvnw -pl audit-oracle-example spring-boot:run
```

Consulte os READMEs de cada módulo para detalhes de arquitetura, configuração e testes.

## Segurança e limitações

- Claims de CPF e nome devem vir de identidade autenticada e validada pela aplicação; não devem ser aceitos diretamente de parâmetros HTTP.
- O package Oracle que define o contexto deve ter privilégios restritos. A autorização de execução do package, isoladamente, não prova a autenticidade criptográfica do usuário.
- O contexto Oracle pertence à sessão e não é automaticamente limpo por COMMIT ou ROLLBACK.
- A solução deve provar, por testes de integração, o isolamento entre transações, o comportamento de `REQUIRES_NEW`, o rollback e a limpeza antes da reutilização da conexão.
- Triggers DML cobrem INSERT, UPDATE e DELETE. SELECT exige mecanismo de auditoria separado.

## Documentação

A documentação global e os documentos específicos de cada módulo serão mantidos junto ao código. Diagramas, scripts de banco, configuração do ambiente e cenários de teste também serão versionados.

## Licença

Apache-2.0 (a confirmar na publicação da primeira versão).
