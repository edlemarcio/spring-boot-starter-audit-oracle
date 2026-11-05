# Desenvolvimento local

## Pré-requisitos

- JDK 21
- Maven 3.9+
- Docker Engine e Docker Compose plugin
- Acesso ao Oracle Container Registry e aceite dos termos da imagem Oracle Database 19c

## Preparar o ambiente

1. Copie `env.example` para `.env` e ajuste as senhas locais.
2. Faça login no Oracle Container Registry e aceite os termos da imagem Enterprise 19c.
3. Inicie os serviços:

```bash
cp env.example .env
docker compose up -d
docker compose ps
docker compose logs -f oracle keycloak
```

4. Aguarde o Oracle concluir a inicialização. O healthcheck verifica a porta; ele não substitui a verificação de que o PDB está aberto.
5. Conecte ao PDB `ORCLPDB1` com usuário administrativo e crie o schema de aplicação, grants e usuário técnico conforme a política local.
6. Execute, na ordem, os scripts de `database/oracle-19c`.

## Configurar a aplicação

A aplicação exemplo roda no host para que o redirect OIDC use `localhost`.

```bash
set -a
source .env
set +a
mvn -pl audit-oracle-example -am install -DskipTests
mvn -pl audit-oracle-example spring-boot:run
```

Abra http://localhost:8080. O Keycloak estará disponível em http://localhost:8081.

## Smoke test manual

1. No Admin Console do Keycloak, defina senhas para os usuários importados.
2. Clique em **Entrar** na aplicação.
3. Autentique-se como `usuario.teste`.
4. Abra o CRUD de clientes e cadastre, altere e exclua um cliente.
5. Entre como `auditor.teste` para consultar os eventos de auditoria.
6. Verifique na tabela `AUDIT_LOG` CPF, nome, operação e request ID.

## Testes automatizados

```bash
mvn clean verify
```

Os testes de integração que exigem Oracle serão documentados e separados dos testes unitários. O smoke test não substitui os testes de concorrência, rollback e reutilização do pool.

## Encerrar

```bash
docker compose down
```

Para apagar os dados persistidos do Oracle de demonstração, use `docker compose down -v`. Isso remove o volume local e todos os dados nele armazenados.
