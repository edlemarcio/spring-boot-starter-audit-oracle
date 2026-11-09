# Bateria manual de concorrência da auditoria

A bateria testa a POC com Oracle/HikariCP reais, JDBC, Hibernate, transações Spring e os controllers, serviços, filtros de segurança e provider do exemplo. A rodada atual implementa somente a dupla limpeza na conexão: antes de configurar a identidade no checkout e antes de devolver ao pool. Outras regressões continuam sendo testadas e relatadas com suas expectativas originais.

## Executar

Requisitos: Java 21, Maven 3.9+, Python 3 e ambiente POSIX. Docker é necessário apenas para provisionar automaticamente o Free 23.

```bash
./scripts/test-audit-concurrency.sh --expected-major 23
```

Sem `AUDIT_ORACLE_URL`, esse comando cria um Oracle Free 23 descartável, com porta local aleatória e senhas temporárias, e remove o contêiner/volume ao encerrar. Com as três variáveis configuradas, utiliza o endpoint fornecido. O provisionamento do banco não consome o orçamento de execução da bateria.

Para Oracle 19c, configure um schema exclusivo de testes:

```bash
export AUDIT_ORACLE_URL='jdbc:oracle:thin:@//host-de-testes:1521/PDB_DE_TESTES'
export AUDIT_ORACLE_USER='SCHEMA_EXCLUSIVO_DE_TESTES'
export AUDIT_ORACLE_PASSWORD='SENHA_DO_SCHEMA'
./scripts/test-audit-concurrency.sh --expected-major 19
```

O schema precisa de CREATE SESSION, CREATE TABLE, CREATE TRIGGER e quota suficiente. A bateria cria tabelas `AC_*` e, nos cenários HTTP, `CLIENTE` e `AUDIT_LOG`. Recusa tabelas do exemplo já existentes; não apaga uma base da aplicação para preparar esses testes. Um marcador com bloqueio de linha, `AC_TEST_LEASE`, protege os cenários novos contra uso simultâneo do schema e permanece instalado entre execuções. Cada execução requer uso exclusivo do schema, inclusive em relação aos testes antigos.

O 19c não é substituído pelo Free 23 se a configuração estiver ausente. Versão incorreta, falta de configuração, erro de compilação, seleção vazia de testes e teste ignorado não produzem aprovação. O runner não altera arquivos de produção, não publica artefatos e não cria jobs de CI. O build inicial instala os artefatos da POC apenas no repositório Maven local para que os testes de cada módulo possam continuar mesmo se outro módulo reprovar.

Opções úteis:

```bash
# Somente os casos determinísticos:
./scripts/test-audit-concurrency.sh --expected-major 23 --phase deterministic

# Estresse curto para diagnosticar a infraestrutura; não aprova a solução:
./scripts/test-audit-concurrency.sh --expected-major 23 --phase stress --stress-seconds 60

# Mutações em cópias temporárias:
./scripts/test-audit-concurrency.sh --expected-major 23 --phase mutations

# Maven/JDK fora do PATH:
./scripts/test-audit-concurrency.sh --expected-major 23 \
  --maven /caminho/para/mvn --java-home /caminho/para/jdk-21
```

Os perfis Maven `oracle-concurrency` e `oracle-stress` também podem ser acionados diretamente com as variáveis Oracle configuradas. Informe `-Daudit.expectedOracleMajor=19` para o 19c. O runner é a entrada recomendada porque agrega resultados dos módulos, exerce as mutações, controla processos e gera a conclusão completa.

## Casos e invariantes

| Grupo | Cobertura |
| --- | --- |
| Pool e reutilização | Pools 1/2/5/10, trabalhadores até 32, disputa por conexões, mesma sessão física entre autores, troca de IP/aplicativo para o mesmo usuário e reutilização das threads. |
| Transações | Autocommit, commit, rollback explícito/implícito, sete propagações no JDBC e no JPA, resultados independentes de REQUIRES_NEW, savepoints e falta de capacidade para transação interna e corrida entre falha de rollback e evicção física. |
| JPA | JpaTransactionManager real, concorrência, batch, flush explícito/no commit, cascatas, orphanRemoval, falha real no flush e rejeição de savepoint não suportado. |
| Segurança | Principais OIDC/JWT já autenticados nos filtros reais, claim configurável, nomes de exibição iguais, requisições anônimas/sem claim, rotinas técnicas e autenticação propagada ao executor. |
| Falhas | Configuração/limpeza parcial efetiva no Oracle, erro SQL/runtime, falha no fechamento do statement, rollback, restauração de autocommit, evicção, falha de fechamento físico, abort, conexão física interrompida e commit de resultado incerto. |
| Capacidade e recursos | Espera expirada/interrompida, timeout de bloqueio Oracle, violação de integridade, READ COMMITTED/SERIALIZABLE, soft eviction, pool encerrado durante transação e statements retidos. |
| Interfaces alternativas | Fechamento via Statement.getConnection e DatabaseMetaData.getConnection, unwrap da interface Connection e diagnóstico de referência Oracle retida fora do empréstimo lógico. |

A autoria esperada vem das operações submetidas, não do provider ou dos atributos lidos do banco. Cada evento é reconciliado por registro, versão e operação. Marcadores gravados na mesma transação determinam o resultado de commits incertos. Isso verifica eventos ausentes, inesperados, duplicados e atribuídos incorretamente, além da ausência de eventos confirmados para operações revertidas.

A limpeza é observada antes da devolução ao pool e, com todos os trabalhadores parados, antes do checkout seguinte. Não se considera suficiente verificar apenas o estado após o próximo usuário sobrescrever os atributos. Os testes de descarte também exigem uma geração física diferente para o próximo usuário. Nos cenários HTTP, uma única conexão observadora independente e serializada evita que as consultas da própria fixture produzam tempestades de logon, preservando a carga dos trabalhadores e do pool da aplicação.

A limpeza de entrada tem um cenário específico que contamina deliberadamente a sessão física ociosa, comprova a limpeza nativa antes de o próximo autor usá-la e reconcilia os eventos desse autor. Há também cinco injeções de falha na limpeza inicial, cobrindo execução parcial e fechamento do statement. A bateria contém 105 casos determinísticos e dois de estresse; os testes de unidade verificam a ordem limpeza/configuração/negócio e a rejeição do checkout em falhas SQL e de runtime.

Casos concorrentes usam barreiras, coleta de todas as Futures e limites de espera. As classes são executadas sequencialmente; a concorrência ocorre dentro de cada cenário. O runner impõe um limite global de 30 minutos e termina os processos da etapa em caso de timeout/interrupção. Uma execução interrompida ou com etapas faltantes permanece incompleta.

## Estresse e repetição

O orçamento padrão de estresse é 900 segundos: 720 para JDBC/JPA e 180 para HTTP. São utilizadas as sementes `1,42,20261108`, 50 identidades, até 32 trabalhadores e pools de 1, 2, 5 e 10 conexões. A criação das fixtures e a drenagem de operações podem acrescentar tempo, respeitando o limite global do runner.

A sequência é derivada por semente e tamanho do pool, sem depender da duração da fase anterior. O relatório registra a fase e as operações do lote atual, para repetir a carga que produziu uma divergência. A semente reproduz a carga; os escalonamentos de threads do sistema operacional não são reproduzidos apenas pela semente.

O estresse combina JDBC direto, transações Spring, JPA, savepoints, REQUIRES_NEW com capacidade controlada, commits, rollbacks, falhas de limpeza selecionadas, requisições OIDC/JWT e rotinas técnicas. Cada lote é reconciliado antes de limpar seus dados; as expectativas não são acumuladas indefinidamente na memória.

As dez mutações alteram usuário/IP/aplicativo, omitem a nova limpeza de entrada ou cada campo limpo, contornam a evicção, antecipam a limpeza antes do flush e trocam rollback implícito por commit. Cada alvo precisa passar sem mutação. Somente uma falha de assertion do mesmo teste conta como detecção; erro de compilação, SQLException de infraestrutura, seleção vazia e timeout não contam. Os arquivos originais não são modificados.

## Relatórios e aprovação

Os resultados ficam em `target/audit-concurrency/oracle-<versão>-<execução>/`:

- `report.html`: resumo navegável e cenários.
- `summary.json`: conclusão, etapas, versões, hashes do código/testes, contadores e mutações.
- `scenarios.csv`: resultado de cada cenário normal.
- `evidence/*.json`: metadados por caso, sessões físicas, trocas de autor comprovadas, divergências e informações de repetição.
- `junit/` e logs: resultados originais das etapas, inclusive testemunhas e mutações.

O comando `mvn clean` remove `target`, incluindo esses relatórios. Execute a suíte existente antes da bateria ou arquive os resultados fora de `target` antes de limpar o build. A opção `--report-dir /caminho/fora/do/target` também permite preservar uma execução nesse local.

Os contadores somam as observações dos cenários. `auditEventsVerified` e `httpAuditEventsVerified` contam eventos lidos nas reconciliações; um evento pode ser conferido mais de uma vez. `crossUserPhysicalReuses` registra a entrada de um novo autor numa geração física já utilizada por outro autor. Esses totais não representam cobertura de todos os escalonamentos possíveis.

Alterar código ou testes durante a execução invalida a conclusão. A aprovação global exige baterias completas aprovadas no 19c e no Free 23, com os mesmos hashes de produção, dependências e testes, sem cenários obrigatórios ignorados ou mutações sobreviventes. Uma execução curta é diagnóstica e não substitui a carga padrão.

Uma conclusão vermelha é uma entrega válida da bateria, mas não uma aprovação da solução. A única alteração funcional autorizada nesta rodada é a dupla limpeza; as demais [regressões identificadas](audit-concurrency-findings.md) mantêm os testes originais e não são corrigidas por esta entrega.

## Verificar o runner e a suíte existente

```bash
python3 -B -m unittest discover -s scripts/tests -v
mvn clean verify
```

O `verify` comum continua sem a nova carga longa. Os testes Oracle antigos mantêm sua habilitação por ambiente; os novos cenários só são selecionados pelos perfis/tags manuais. Os testes Python verificam, entre outros casos, que resultados vazios, interrupções, skips e falhas de infraestrutura não se transformam em sucesso.

A autenticação de teste fornece principais já autenticados; os testes não substituem validação criptográfica de tokens, login real do SSO ou configuração real de proxies/Tomcat. Compartilhar deliberadamente a conexão física após fechar o empréstimo lógico é registrado como uso fora do contrato, não como uma garantia oferecida pela POC.
