# Achados da bateria de auditoria

A investigação inicial testou os componentes de produção do commit `1ea55d1`. A alteração posterior autorizada acrescenta somente a dupla limpeza: antes de configurar o contexto no checkout e antes de devolver a conexão ao pool. Aliases JDBC, unwrap, tratamento de exceções e evicção não foram corrigidos nesta rodada. As expectativas dos cinco testes de regressão originais foram preservadas.

Para entender as sequências de falha, seus impactos e as correções propostas, consulte a [explicação didática dos cinco casos](audit-failures-explained.md).

## Efeito da dupla limpeza

`OracleConcurrencyTest.cleansStaleSessionOnCheckoutBeforeApplyingTheNextIdentity` contamina deliberadamente os três atributos de uma sessão física ociosa e verifica a limpeza nativa antes da utilização pelo próximo autor, além da autoria dos eventos de negócio. Cinco casos adicionais induzem falhas na limpeza de entrada; o checkout deve falhar e a geração física não deve ser reaproveitada. Os testes de unidade também verificam falhas SQL/runtime e a ordem das operações.

A implementação acrescenta uma execução PL/SQL por checkout e mantém o fluxo de saída anterior. Essa barreira de entrada não satisfaz, por si só, o requisito dos dois testes de aliases: eles examinam a sessão antes de existir um próximo checkout. Também não muda os caminhos que ocultam exceções ou a corrida de commit no descarte.

## Obrigatório: conexões obtidas por aliases JDBC contornam a limpeza

**Reprodução:** `OracleConcurrencyTest.jdbcConnectionAliasesMustNotReturnDirtySessionsToThePool`, parâmetros `statement` e `metadata`.

Após obter uma conexão auditada para Ana, o teste fecha a referência retornada por `Statement.getConnection()` ou `DatabaseMetaData.getConnection()`. A conexão volta ao pool sem passar pelo `AuditConnection.close()`. A inspeção da mesma sessão, antes de outro checkout, encontra:

- usuário: `sub-ana`;
- IP: `2001:db8::1`;
- aplicativo: `internal`.

Isso viola o contrato de limpeza antes da reutilização. Com a dupla limpeza, um próximo checkout normal limpa e configura os campos novamente; a constatação acima, por si só, não prova que esse próximo checkout gravou um evento com autor incorreto. Ela prova que há caminhos públicos JDBC que devolvem uma sessão contaminada. São duas regressões separadas.

A investigação de uma correção futura deve abranger as referências Connection expostas pelos objetos JDBC derivados; corrigir apenas uma das duas rotas deixa a outra aberta.

## Obrigatório: falhas de finalização podem perder a exceção original

**Reprodução:** `OracleLifecycleFaultTest.lifecycleFailuresMustNotBeReplacedByAnUnrelatedNullPointer`, parâmetros `ROLLBACK` e `AUTOCOMMIT_RESET`.

Com HikariCP real, uma SQLException induzida no rollback ou na restauração de autocommit provoca descarte. Ao fechar novamente a referência Hikari após a evicção, o caminho de reset pode lançar `NullPointerException` em `PoolBase.resetConnectionState`, ocultando a falha original.

Os cenários específicos de isolamento verificam separadamente se a conexão é retirada e se o próximo usuário recebe outra geração física. A regressão deste grupo é a preservação da exceção; não deve ser apresentada como troca de autoria observada. São mais duas regressões separadas.

## Critical: rollback falho pode terminar em commit no descarte

**Reprodução:** `OracleLifecycleFaultTest.failedRollbackMustNotCommitWhenPhysicalEvictionWinsTheCleanupRace`.

O teste cria dados e um marcador na mesma transação, induz uma SQLException antes de o rollback executar e controla o escalonamento para que o fechamento físico disparado pela evicção termine antes de o chamador prosseguir com o fechamento lógico. A conexão Hikari, o driver e o Oracle são reais.

Nesse interleaving, o marcador confirma que a transação pendente foi gravada, apesar da intenção de rollback. A verificação também lê os registros de negócio e os eventos da trigger. O callback de teste apenas aguarda o fechamento assíncrono; não executa commit nem introduz DML concorrente na mesma conexão.

Esse achado foi reproduzido no Oracle Free 23 com o driver e o HikariCP registrados nos relatórios. É uma falha de atomicidade/finalização, não uma troca de autoria observada. O caso permanece vermelho e requer investigação antes da homologação; a mesma reprodução no Oracle 19c ainda depende de ambiente disponível.

## FYI: referência física de unwrap fora do empréstimo lógico

`OracleConcurrencyTest.vendorUnwrapIsAnExplicitDiagnosticOutsideTheLogicalLeaseContract` mostra que uma referência Oracle obtida por unwrap pode continuar acessível depois do fechamento lógico e enxergar os metadados do próximo usuário da mesma sessão.

O cenário é um diagnóstico explícito de uso fora do contrato: não executa comandos simultâneos na mesma conexão nem considera esse acesso uma garantia suportada. A referência física não deve ser armazenada para utilização depois do fechamento da conexão auditada.

## Resultados de execução

| Rodada no Free 23 | Cenários aprovados | Reprovados | Mutações detectadas |
| --- | --- | --- | --- |
| Antes da dupla limpeza | 96 de 101 | Cinco | Nove de nove |
| Com dupla limpeza | 102 de 107 | Os mesmos cinco | Dez de dez |

Na reexecução `oracle-23-20261108-173758-5fb6e428`, os seis novos casos Oracle passaram: sessão ociosa contaminada no checkout e cinco falhas na limpeza de entrada. Os dois testes de estresse passaram novamente; a suíte padrão aprovou 53 testes e o executor aprovou seus 18 testes Python. Não houve cenários ignorados ou evidências ausentes.

A mutação `omit-checkout-clear` foi detectada pelo teste da sessão contaminada. Isso comprova que o teste exige a limpeza de entrada, em vez de aceitar apenas a sobrescrita posterior dos atributos pelo próximo autor. Os cinco testes de regressão anteriores mantêm as mesmas expectativas e os mesmos resultados vermelhos.

Nos fluxos aprovados não foi observada troca de autoria. O resultado global continua `rejected`, pelos casos de sessão suja e finalização descritos acima; a dupla limpeza foi a única alteração funcional autorizada nesta rodada.

Os números, versões e identificadores dos relatórios estão no [status da implementação](implementation-status.md). A aprovação depende também da execução no Oracle 19c; Free 23 sozinho não conclui a homologação.
