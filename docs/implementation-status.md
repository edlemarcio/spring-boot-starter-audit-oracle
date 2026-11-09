# Status da implementação e validação

A POC utiliza o ciclo de conexão baseado no TRE Condenações: `AuditDataSource` e `AuditConnection`, com usuário/IP/aplicativo em `CLIENT_IDENTIFIER`, `CLIENT_INFO` e `MODULE`. A API anterior baseada em anotação e contexto próprio foi substituída. Nesta rodada, somente a POC recebeu uma limpeza adicional antes da configuração da identidade no checkout; a limpeza na devolução foi mantida.

A solução não está homologada pela bateria de concorrência: cinco casos de regressão expõem três problemas, incluindo commit inesperado após falha de rollback. A validação no Oracle 19c também permanece pendente. O `verify` comum não seleciona os testes manuais de concorrência e não substitui sua aprovação.

## Cobertura automatizada

| Área | Validação |
| --- | --- |
| Core | 19 casos de IPv4/IPv6, identidades inválidas e limites em bytes sem truncamento |
| Ciclo JDBC | 10 testes de limpeza de entrada, configuração, commit/rollback, fechamento idempotente, trabalho pendente e descarte em falhas |
| Provider antes do checkout | 2 testes de rejeição antes da abertura do pool |
| Auto-configuração | 5 testes de pool único, propriedades/lifecycle, desabilitação, provider ausente e DataSource customizado |
| Spring Security | 9 testes de OIDC/JWT, claim configurável, IP, HTTP anônimo, identidade técnica e troca de usuário |
| Oracle e pool reais | 5 testes de mesma sessão reutilizada, limpeza, IPv6, identidade técnica, flush Hibernate com orphanRemoval, rollback implícito, concorrência e REQUIRES_NEW |
| Exemplo com Oracle | 3 testes de scripts/triggers, CRUD com JpaTransactionManager, JSON antes/depois, rollback, SQL direto e migração preservando eventos antigos |

Os testes Oracle são opcionais por ambiente e exigem `AUDIT_ORACLE_URL`, `AUDIT_ORACLE_USER` e `AUDIT_ORACLE_PASSWORD`. Eles usam um schema exclusivo e criam/removem `AUDIT_TEST_*`, `CLIENTE` e `AUDIT_LOG`. Sem essas variáveis, oito testes são explicitamente ignorados.

## Resultado inicial da execução local em 2026-11-08

- `mvn clean verify`: aprovado com Java 21; 51 testes, zero falhas, zero erros e zero ignorados, incluindo oito testes Oracle.
- Mutação em cópia temporária: inverter a condição de fechamento de `AuditConnection` causou falha em cinco testes. A cópia foi removida; o código validado manteve a condição correta.
- `git diff --check` e referências locais da documentação: sem problemas.

## Ambiente de validação

A validação local utiliza JDK 21 e um Oracle Database Free 23 em contêiner isolado. Os scripts continuam destinados a Oracle 19c, mas a execução na edição 19c ainda deve ser confirmada no ambiente correspondente. O teste do exemplo verifica erros de compilação da trigger antes de executar DML.

A integração contínua executa `mvn clean verify` com Java 21. O login real no Keycloak e a configuração de proxies exigem o smoke test descrito no [guia local](guides/local-development.md).

## Nova bateria manual de concorrência

A bateria adicional está documentada no [guia de execução](audit-concurrency.md). Inclui 105 casos determinísticos, dois testes de estresse que compartilham 15 minutos de carga, dez mutações em cópias temporárias e 18 testes Python do runner. A suíte padrão inclui os testes antigos e dois novos casos unitários de rejeição de checkout, sem a nova carga longa.

A única alteração funcional desta rodada é a dupla limpeza de contexto. Os scripts de banco e o código do TRE não foram alterados. As expectativas das cinco [regressões identificadas](audit-concurrency-findings.md) foram preservadas; outras correções não fazem parte desta entrega. Uma execução vermelha não deve ser interpretada como homologação aprovada.

### Reexecução com dupla limpeza, em 2026-11-08

Execução `oracle-23-20261108-173758-5fb6e428`, disponível localmente em `target/audit-concurrency`, com HTML, JSON, CSV, JUnit e evidências por cenário:

| Verificação | Resultado |
| --- | --- |
| Casos determinísticos | 105 executados: 100 aprovados e os mesmos cinco casos reprovados |
| Estresse JDBC/JPA e HTTP | Dois casos aprovados, com orçamento conjunto de 900 segundos |
| Total de cenários | 107 executados: 102 aprovados, cinco reprovados, nenhum ignorado |
| Cobertura específica da limpeza de entrada | Um caso com sessão ociosa contaminada e cinco falhas de checkout aprovados |
| Mutações | Dez de dez detectadas, incluindo a remoção da nova limpeza de entrada |
| Suíte padrão (`verify`) | 53 aprovados, incluindo os oito casos Oracle e dois novos casos unitários de falha na limpeza inicial; nenhum ignorado |
| Executor Python | 18 testes aprovados |
| Integridade da execução | 107 evidências, nenhuma etapa ausente e nenhum código/teste alterado durante a bateria |

A execução integral consumiu 1.123,75 segundos (18 minutos e 44 segundos), dentro do limite de 30 minutos. Registrou 107.829 observações de checkout, 434.226 observações de limpeza, 20.720 entradas de novos autores em gerações físicas já usadas por outro autor e 374 falhas de limpeza injetadas durante o estresse. As reconciliações somaram 227.588 verificações de eventos JDBC/JPA e 105.840 HTTP; esses contadores podem incluir a conferência repetida de um mesmo evento e não constituem um benchmark de desempenho.

Ambiente: Oracle Free `23.26.3.0.0`, Java `21.0.9`, driver `23.9.0.25.07` e HikariCP `6.3.3`. Sementes: `1,42,20261108`; pools: `1,2,5,10`; até 32 trabalhadores e 50 identidades. O hash de produção/dependências é `c1ac6ec279f3197805e1d11070e73dcd26bd65ca5fb21abf8f1fb2c1fd260fc3`; o hash de testes/executor é `b3d7932f5bab002a0e423edc615cf5a34347f51f8d9966b9bbf90d52dff7e68d`.

O relatório conclui `overall_validation: rejected`: a dupla limpeza funciona nos novos cenários, mas não corrige os dois caminhos por aliases, os dois casos de perda da exceção original nem a corrida de commit após rollback falho. Não houve divergência de autoria nos fluxos aprovados. Os seis casos Oracle adicionais passaram; o aumento de 101 para 107 cenários não representa correção das cinco regressões anteriores.

O preflight `oracle-19-20261108-173757-9c6687e9` registrou `overall_validation: incomplete`, sem executar testes por ausência de configuração 19c. Os relatórios da execução anterior foram preservados para comparação, com seus próprios hashes e resultados.

### Execução anterior à dupla limpeza, em 2026-11-08

Execução `oracle-23-20261108-165741-f75c9cd1`, disponível localmente em `target/audit-concurrency`:

| Verificação | Resultado |
| --- | --- |
| Novos casos determinísticos | 99 executados: 94 aprovados e cinco regressões reprovadas |
| Estresse JDBC/JPA e HTTP | Dois casos aprovados, com orçamento conjunto de 900 segundos |
| Total de cenários novos | 101 executados: 96 aprovados, cinco reprovados, nenhum ignorado |
| Mutações | Nove de nove detectadas por assertions de testes com baseline verde |
| Suíte original (`clean verify`) | 51 aprovados, incluindo os oito casos Oracle; nenhum ignorado |
| Executor Python | 18 testes aprovados |
| Integridade da execução | 101 evidências, nenhuma etapa ausente e nenhum código/teste alterado durante a bateria |

A execução integral consumiu 1.116,10 segundos (18 minutos e 36 segundos), dentro do limite de 30 minutos. Foram registradas 109.783 observações de checkout, 21.037 entradas de novos autores em gerações físicas já usadas por outro autor e 380 falhas de limpeza injetadas durante o estresse. As reconciliações somaram 231.790 verificações de eventos JDBC/JPA e 118.286 HTTP; esses contadores podem incluir a conferência repetida de um mesmo evento.

Ambiente: Oracle Free `23.26.3.0.0`, Java `21.0.9`, driver `23.9.0.25.07` e HikariCP `6.3.3`. Sementes: `1,42,20261108`; pools: `1,2,5,10`; até 32 trabalhadores e 50 identidades na carga. O relatório integral conclui `overall_validation: rejected`, pelas cinco regressões descritas nos achados. Nos fluxos aprovados não foi observada divergência de autoria; isso não elimina os caminhos de devolução de sessão suja encontrados nos casos reprovados.

O preflight `oracle-19-20261108-165738-a623daab` registrou `overall_validation: incomplete`, sem executar testes: não havia endpoint/schema 19c configurado. Essa tentativa não conta como validação do 19c.

O Oracle 19c continua obrigatório para a aprovação global. A ausência de ambiente 19c é registrada como validação incompleta; não se substitui esse requisito pelo Free 23.

## Limites de escopo

- O exemplo mantém `AUDIT_LOG`; os consumidores mantêm suas tabelas de auditoria e usam o contrato de sessão.
- SELECT não é auditado pelas triggers DML. FGA/política de leitura permanece pendente.
- A auditoria pertence à transação de negócio e também é revertida em rollback.
- Sessões SQL diretas usam os fallbacks do TRE; os metadados fornecidos pela aplicação dependem da autenticação validada por ela.
- A migração da base anterior é aditiva, exige parada coordenada e não reatribui eventos históricos.
- A POC ainda não foi publicada como versão estável.
