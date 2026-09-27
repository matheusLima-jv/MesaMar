# Relatório — Fase 0: Fundação técnica

- **Branch / PR:** `fase-0-fundacao` → [PR #1](https://github.com/matheusLima-jv/MesaMar/pull/1)
- **Data:** 2026-09-27
- **CI:** ✅ verde (lint, 11 testes, build da imagem e smoke test com `docker compose`)

## 1. O que foi feito

- Monorepo com Gradle Kotlin DSL, version catalog (`gradle/libs.versions.toml`) e wrapper 8.14.3.
- Backend Spring Boot 4.1.1 + Kotlin 2.2.21 (JDK 21) com Actuator: `/actuator/health`, `/actuator/health/liveness` e `/actuator/health/readiness`.
- Perfis `local` (padrão), `test` e `prod` (logs em JSON, segredos só por variável de ambiente).
- PostgreSQL 16 + Flyway com a migração `V1__baseline.sql`.
- `web-staff` (servido em `/staff/`) e `web-customer` (servido em `/cliente/`) como placeholders Vite + TypeScript. O build dos dois roda dentro do `./gradlew build` e vai empacotado no JAR. Cada página mostra se o servidor está online.
- `Dockerfile` multi-stage (Node → Gradle/JDK → JRE, rodando com usuário sem privilégios) e `docker-compose.yml` (app + Postgres).
- GitHub Actions: ktlint → build dos fronts + testes com PostgreSQL real → build da imagem → **smoke test subindo o `docker compose` e chamando o health**. Publicação no GHCR só em `main`.
- Falhas de build e de teste viram anotações no PR, e o resumo dos testes aparece na aba *Checks*.
- README, `.env.example` e o plano completo em `docs/MESAMAR_PROMPT.md`.

## 2. Decisões tomadas

| Decisão | ADR |
|---|---|
| Spring Boot **4.1.1** (não precisou cair para 3.5), Kotlin 2.2.21, Gradle 8.14.3, Testcontainers 2.x; build dos fronts por tarefas `Exec` do Gradle | [ADR 0001](../adr/0001-stack-e-versoes.md) |
| **detekt adiado:** só existe em alpha para Kotlin 2.2+. O CI roda apenas ktlint por enquanto | [ADR 0002](../adr/0002-detekt-adiado.md) |

Decisões menores, sem ADR:

- A migração inicial cria uma tabela `app_metadata` com o marco `fase-0`, para o teste provar que o Flyway rodou de fato.
- O `docker-compose.yml` usa o perfil `local` (logs legíveis) mesmo dentro do Docker. O perfil `prod` fica para o deploy da Fase 1.
- Rotas dos fronts: `/staff/` e `/cliente/`. A rota do QR do cliente (`/c/{token}`) só chega na Fase 3.

## 3. Como rodar e testar localmente

```bash
git clone https://github.com/matheusLima-jv/MesaMar.git
cd MesaMar
git checkout fase-0-fundacao

# Tudo no Docker (primeira vez leva alguns minutos)
docker compose up --build
```

Com o ambiente no ar:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP","components":{...,"db":{"status":"UP",...}}}
```

Testes (precisa de JDK 21, Node 22 e Docker rodando):

```bash
./gradlew ktlintCheck build
```

## 4. Resultado dos testes automatizados

**11 testes, 11 passando** (execução no CI com PostgreSQL 16 real via Testcontainers):

| Classe | Testes | O que garante |
|---|---|---|
| `ApplicationSmokeTest` | 2 | O contexto Spring sobe; `/actuator/health` responde `UP`; probes de liveness e readiness respondem 200 |
| `FlywayMigrationTest` | 3 | A migração V1 foi aplicada com sucesso; a tabela do marco zero existe com o valor esperado; o banco dos testes é mesmo PostgreSQL 16 |
| `FrontendBundleTest` | 6 | `/staff/` e `/cliente/` servem o `index.html` gerado pelo build; o JS referenciado no HTML existe e é servido; `/staff` e `/cliente` sem barra redirecionam |

Além dos testes, o CI faz um **smoke test do `docker compose up`**, que é o mesmo passo 1 da validação manual abaixo, e ele passou.

## 5. Roteiro de validação manual (Matheus)

| # | Passo | Resultado esperado |
|---|---|---|
| 1 | No seu computador: `docker compose up --build` na raiz do projeto | Depois de alguns minutos, o log mostra `Started MesaMarApplication` |
| 2 | Abrir http://localhost:8080/actuator/health | JSON com `"status":"UP"` e o componente `db` também `UP` |
| 3 | Abrir http://localhost:8080/staff/ | Página "MesaMar — Painel do quiosque" com **Servidor: online ✅** |
| 4 | Abrir http://localhost:8080/cliente/ | Página "MesaMar — Sua comanda" com **Servidor: online ✅** |
| 5 | Parar só o banco: `docker compose stop db` e recarregar o passo 2 | Health passa a responder `DOWN` (HTTP 503). Depois: `docker compose start db` e volta para `UP` |
| 6 | Abrir o [PR #1](https://github.com/matheusLima-jv/MesaMar/pull/1), aba *Checks* | Os dois jobs verdes e a anotação "Testes: 11 passaram…" |
| 7 | Conferir a estrutura de pastas na raiz | `backend/`, `web-staff/`, `web-customer/`, `e2e/`, `deploy/`, `docs/` como no plano |

## 6. Limitações conhecidas e riscos

- **Não compilei nada localmente.** O ambiente de desenvolvimento em que o código foi escrito não tem acesso ao Maven Central nem ao Gradle Plugin Portal, então todo o build Kotlin foi validado pelo CI do GitHub. Isso não afeta você, mas o passo 1 da validação manual é a primeira execução fora do CI.
- **detekt fora do CI** até sair a versão estável (ADR 0002).
- **Primeiro build no Docker é lento** (baixa Gradle, dependências e pacotes npm). Os builds seguintes usam cache.
- **Aviso do GitHub:** o runner `ubuntu-latest` migra para Ubuntu 26 a partir de 19/10/2026. Não deve quebrar nada, mas se o CI falhar do nada nessa data, é o primeiro suspeito.
- Os fronts ainda são placeholders: sem PWA, sem Service Worker e sem Dexie. Isso entra nas fases em que eles são usados de verdade.

## 7. Proposta para a Fase 1 (sem implementar)

- **Landing page em Thymeleaf** na raiz `/`, com os textos da seção B5 do plano. Sugiro você revisar os textos já no primeiro checkpoint da fase, antes de eu mexer em estilo.
- Dividir a Fase 1 em **3 checkpoints** menores, para você validar aos poucos:
  1. Landing estática (conteúdo, SEO, Open Graph, política de privacidade)
  2. Formulário da lista de espera com validação, anti-spam e página de confirmação + `/teste-sinal`
  3. Painel administrativo com CSV + arquivos de deploy (`deploy/` e `docs/deploy.md`) + E2E Playwright
- **Decisões que preciso de você antes do deploy:** o domínio (`mesamar.r4d4r.online` ou outro), o número de WhatsApp do botão "Falar no WhatsApp" e se já quer criar o site no Cloudflare Turnstile (é gratuito, gera as chaves do anti-spam).

⏸️ Aguardando validação. Responda "APROVADO FASE 0" ou "AJUSTAR FASE 0: ..."
