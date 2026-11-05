# audit-oracle-spring-boot-autoconfigure

## Responsabilidade

Integra os contratos de `audit-oracle-core` ao ecossistema Spring Boot. Este módulo concentra propriedades, condições de auto-configuração e componentes de infraestrutura Oracle.

## Princípios

- Não conhece Keycloak nem qualquer provedor OIDC específico.
- Não depende de Thymeleaf ou do projeto de demonstração.
- Deve reutilizar o `DataSource` e o gerenciador transacional da aplicação.
- Deve falhar de forma explícita quando a identidade for obrigatória e estiver ausente.
- Deve garantir que contexto de sessão não sobreviva à devolução de uma conexão ao pool.

## Estado

A integração do ciclo de vida transacional e da limpeza segura da conexão será implementada após a definição e validação dos pontos de extensão do Spring JDBC/JPA. Não considerar o módulo pronto para produção enquanto os testes de integração Oracle não forem aprovados.
