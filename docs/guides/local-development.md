# Desenvolvimento local

Requer JDK 21, Maven 3.9+, Docker e acesso à imagem Oracle 19c no Oracle Container Registry.

## Preparar

```bash
cp env.example .env
# Ajuste senhas e variáveis locais.
docker login container-registry.oracle.com
docker compose up -d
```

Aguarde o PDB abrir, crie o schema e aplique os [scripts de banco](../../database/oracle-19c/README.md). O healthcheck do Compose verifica a porta; confirme também a abertura do PDB. O Keycloak importa o realm `audit-demo`. Defina senhas para os usuários pelo Admin Console.

```bash
set -a
source .env
set +a
mvn -pl audit-oracle-example -am install -DskipTests
mvn -pl audit-oracle-example spring-boot:run
```

Aplicação: http://localhost:8080. Keycloak: http://localhost:8081.

## Smoke test

1. Entre como `usuario.teste` e cadastre/altere/exclua um cliente.
2. Entre como `auditor.teste` e consulte a auditoria.
3. Confira o identificador `sub`, IP e aplicativo, operação e JSON antes/depois.
4. Repita com outro usuário e confirme a troca de autoria nas operações seguintes.
5. Atrás de proxy reverso, configure `TRUSTED_PROXY_REGEX` e confirme o IP com acesso real e cabeçalho forjado pelo cliente.

`AUDIT_USER_CLAIM=preferred_username` permite exibir um login em lugar do `sub`, desde que seja um identificador único confiável no emissor. CPF permanece um campo de negócio.

## Testes

```bash
mvn clean verify
```

Para incluir Oracle, exporte `AUDIT_ORACLE_URL`, `AUDIT_ORACLE_USER` e `AUDIT_ORACLE_PASSWORD` apontando para um schema exclusivo de testes. A suíte cria/remove `AUDIT_TEST_*`, `CLIENTE` e `AUDIT_LOG`; não use schema da aplicação em execução. Sem essas variáveis, a integração Oracle é ignorada. H2 é usado apenas para testar configuração, com auditoria desabilitada quando suas conexões são utilizadas.

## Encerrar

```bash
docker compose down
```

O volume Oracle é preservado. A remoção explícita de volumes apaga os dados locais.
