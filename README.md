# MesaMar

Comanda digital para quiosques e carrinhos de praia. O cliente faz um cadastro rápido no quiosque, recebe um QR pessoal, pede pelo celular e fecha a conta sem chamar garçom. O dono recebe os pedidos numa fila por ordem de chegada.

> **Status:** cliente, equipe, fila, pagamentos, relatórios e modo offline da equipe disponíveis. As revisões 61–116 estão na `main`; as [correções da auditoria geral](docs/relatorios/correcoes-auditoria-geral.md), com banco V21, aguardam aprovação. Publicação e serviços externos (S3, monitores, SMTP) ainda pendentes. Estado atual: [`ESTADO_DO_PROJETO.md`](ESTADO_DO_PROJETO.md).

## Stack

| Parte | Tecnologia |
|---|---|
| Backend | Kotlin 2.2 · Spring Boot 4.1 · JDK 21 |
| Banco | PostgreSQL 16 · Flyway |
| App do dono/atendente (`web-staff`) | TypeScript + Vite, servido em `/staff/` |
| Página do cliente (`web-customer`) | TypeScript + Vite, servida em `/cliente/` |
| Testes | JUnit 5 · Testcontainers (PostgreSQL real) |
| Qualidade | ktlint · ESLint · validação de contratos · testes unitários JS/TS |
| Entrega | Docker multi-stage · Docker Compose · GitHub Actions → GHCR |

As decisões e os motivos estão em [`docs/adr/`](docs/adr/).

## Estrutura

```
mesamar/
├── backend/        # Spring Boot (Kotlin)
├── web-staff/      # App do quiosque (PWA) em /staff/: login do dono e da equipe, cardápio
├── web-customer/   # PWA do cliente, carrinho e reenvio idempotente
├── e2e/            # Testes Playwright em Chromium e WebKit
├── deploy/         # Operação, migrações, backup e recuperação
└── docs/           # Plano, ADRs e relatórios de cada fase
```

> Roteiro completo para validar tudo na sua máquina antes do deploy: [`docs/testar-localmente.md`](docs/testar-localmente.md).

## Pré-requisitos

- **Docker** (com Docker Compose) — suficiente para rodar tudo.
- Para desenvolver sem Docker: **JDK 21** e **Node 22**.

## Rodar localmente

### Opção 1: tudo no Docker (mais simples)

```bash
docker compose up --build
```

- Landing page: http://localhost:8080/
- Lista de espera: formulário na própria landing (seção do piloto)
- Teste de sinal na praia: http://localhost:8080/teste-sinal
- Política de privacidade: http://localhost:8080/privacidade
- Painel (leads, sinal por praia, CSV): http://localhost:8080/admin — usuário e senha em `MESAMAR_ADMIN_USER` / `MESAMAR_ADMIN_PASSWORD` no `.env` (sem senha, o painel fica trancado)
- Health check: http://localhost:8080/actuator/health → `{"status":"UP", ...}`
- Painel do dono (conta criada por convite do /admin): http://localhost:8080/painel
- App do quiosque (dono ou equipe): http://localhost:8080/staff/
- Página do cliente (o QR individual abre /c/{token}): http://localhost:8080/cliente/

O primeiro build demora alguns minutos (baixa o Gradle e as dependências). Os seguintes usam cache.

### Opção 2: backend na IDE / terminal, banco no Docker

```bash
docker compose up -d db
./gradlew :backend:bootRun
```

O `bootRun` também faz o build dos dois fronts (`npm ci` + `npm run build`). Para pular esse passo depois do primeiro build:

```bash
./gradlew :backend:bootRun -PskipFrontendBuild
```

### Desenvolvendo um front com recarga automática

```bash
cd web-staff        # ou web-customer
npm install
npm run dev         # http://localhost:5173/staff/ (as chamadas /api vão para o Spring na 8080)
```

## Testes

```bash
./gradlew ktlintCheck    # estilo do código Kotlin
./gradlew build          # compila, builda os fronts e roda todos os testes
```

Os testes de integração sobem um **PostgreSQL 16 real via Testcontainers**, então o Docker precisa estar rodando.

### Testes de ponta a ponta (Playwright, tamanho de celular)

Com a aplicação no ar (`docker compose up`):

```bash
cd e2e
npm ci
npx playwright install chromium webkit
E2E_ADMIN_PASSWORD=<a senha do seu .env> npx playwright test
npx playwright show-report   # relatório com prints e vídeos das falhas
```

Relatório HTML dos testes: `backend/build/reports/tests/test/index.html`.

Para corrigir o estilo automaticamente: `./gradlew ktlintFormat`.

## Perfis do Spring

| Perfil | Uso | Observações |
|---|---|---|
| `local` (padrão) | Desenvolvimento | Logs em texto, detalhes no `/actuator/health` |
| `test` | Testes automatizados | Banco vem do Testcontainers |
| `prod` | Servidor | Logs em JSON (ECS); todos os segredos vêm de variáveis de ambiente |

Variáveis de ambiente: veja [`.env.example`](.env.example). O `.env` **nunca** vai para o git.

Para ver o botão de WhatsApp da landing localmente, crie um `.env` com `MESAMAR_WHATSAPP=55DDDNUMERO` (só dígitos) e rode `docker compose up --build` de novo.

## CI

A cada PR, a CI executa análise estática, testes unitários e integração com PostgreSQL, constrói a imagem uma vez e executa smoke, E2E e recuperação contra essa imagem. A publicação em `main` reutiliza a imagem aprovada e registra commit, ID e digest em `versao-validada.json`. O deploy usa esse digest e confere a faixa de esquema permitida. Dependências e segredos têm checagem semanal; Actions, bases Docker e Wrapper possuem referências verificáveis.

O deploy permanece manual. Procedimentos, metas de recuperação, alertas e configuração S3 estão em [`docs/deploy.md`](docs/deploy.md). Resultados e verificações externas pendentes estão em [`ESTADO_DO_PROJETO.md`](ESTADO_DO_PROJETO.md) e nos relatórios em [`docs/relatorios/`](docs/relatorios/).
