# MesaMar

Comanda digital para quiosques e carrinhos de praia. O cliente faz um cadastro rápido no quiosque, recebe um QR pessoal, pede pelo celular e fecha a conta sem chamar garçom. O dono recebe os pedidos numa fila por ordem de chegada.

> **Status:** Fase 1 em andamento (landing page + lista de espera). O plano completo, com as fases e os portões de validação, está em [`docs/MESAMAR_PROMPT.md`](docs/MESAMAR_PROMPT.md).

## Stack

| Parte | Tecnologia |
|---|---|
| Backend | Kotlin 2.2 · Spring Boot 4.1 · JDK 21 |
| Banco | PostgreSQL 16 · Flyway |
| App do dono/atendente (`web-staff`) | TypeScript + Vite, servido em `/staff/` |
| Página do cliente (`web-customer`) | TypeScript + Vite, servida em `/cliente/` |
| Testes | JUnit 5 · Testcontainers (PostgreSQL real) |
| Qualidade | ktlint |
| Entrega | Docker multi-stage · Docker Compose · GitHub Actions → GHCR |

As decisões e os motivos estão em [`docs/adr/`](docs/adr/).

## Estrutura

```
mesamar/
├── backend/        # Spring Boot (Kotlin)
├── web-staff/      # App do dono/atendente (placeholder na Fase 0)
├── web-customer/   # Página do cliente (placeholder na Fase 0)
├── e2e/            # Testes Playwright (a partir da Fase 1)
├── deploy/         # Arquivos de produção (a partir da Fase 1)
└── docs/           # Plano, ADRs e relatórios de cada fase
```

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
- Health check: http://localhost:8080/actuator/health → `{"status":"UP", ...}`
- App do quiosque (placeholder): http://localhost:8080/staff/
- Página do cliente (placeholder): http://localhost:8080/cliente/

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

A cada PR, o GitHub Actions roda: ktlint → build dos fronts → testes com PostgreSQL real → build da imagem Docker.
No merge para `main`, a imagem também é publicada em `ghcr.io/matheuslima-jv/mesamar`.

**O deploy é sempre manual** (o passo a passo chega na Fase 1, em `docs/deploy.md`).

## Fluxo de trabalho

Uma branch por fase (`fase-N-nome`) com PR para `main`. Cada fase termina com um relatório em [`docs/relatorios/`](docs/relatorios/) e só avança depois da validação manual.
