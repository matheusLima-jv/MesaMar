# Estado do projeto MesaMar: onde paramos e como continuar

> **Leia isto antes de qualquer outra coisa.** Este arquivo é a passagem de bastão entre sessões de trabalho: diz exatamente o que existe, o que está verificado, o que não está e qual é o próximo passo.
> Última atualização: **2026-09-30**, checkpoint **2.4** com CI verde, **aguardando o "aprovado 2.4"**.

---

## 0. Primeiro passo (antes de escrever qualquer código)

1. **Se o Matheus ainda não aprovou o 2.4:** apresente o relatório (`docs/relatorios/fase-2.md`, seção "Checkpoint 2.4") e peça o "aprovado 2.4". Esse checkpoint fecha a Fase 2.
2. **Depois da aprovação:** marque o 2.4 no relatório, tire o PR #3 do rascunho (o Matheus decide o merge) e comece o **checkpoint 3.1** (seção 7).

Os 8 commits que tinham ficado fora do GitHub já estão na `fase-2-contas`; o `mesamar-pendente.bundle` não é mais necessário.

**Dica para o Windows do Matheus:** a porta 5432 pode estar ocupada ou reservada pelo sistema. Para subir o compose, troque o mapeamento do banco para `127.0.0.1:5433:5432` (o app não usa essa porta).

---

## 1. Como o Matheus quer que o trabalho seja feito (regras que valem sempre)

- **Checkpoints pequenos, e só avança com aprovação explícita** ("aprovado 2.4", "continue"…). Ao fim de cada checkpoint: CI verde, seção nova em `docs/relatorios/fase-N.md` e itens novos no roteiro manual `docs/testar-localmente.md`. **Pare e peça aprovação.**
- **Os testes manuais ficam para a entrega final**: o Matheus aprova com base no relatório e no CI, e testa tudo no fim. Então os testes automatizados precisam ser bons.
- **Não mergear na `main` sem ele pedir.** Uma branch por fase, com PR para `main` (a Fase 2 está no PR #3, em rascunho).
- **Commits pequenos, no padrão Conventional Commits.** Mensagens em português (é o que o histórico usa), e cada commit termina com as linhas de coautoria da sessão.
- **Idioma:** textos de interface e documentação em **português do Brasil**. Identificadores de código seguem o que já existe (a maior parte do domínio está em português: `barraca`, `comanda`, `pedido`…). Mantenha a consistência.
- **Nunca** altere firewall (UFW), porta de SSH nem Cloudflare. Produza arquivos e um passo a passo em `docs/deploy.md`; o Matheus executa.
- **Segredos nunca vão para o repositório** (inclusive as chaves VAPID do Web Push). Use `.env` e mantenha `.env.example` e `deploy/prod.env.example` atualizados.
- **Não invente requisitos.** O que for fácil de desfazer, decida e registre em ADR (`docs/adr/NNNN-…md`). O que for difícil de desfazer, pergunte.
- **Proibido** inventar depoimentos, números de clientes ou logos de parceiros.
- **Estilo de resposta ao Matheus:** resposta direta primeiro, depois o porquê; seções curtas; números e trade-offs; tom informal e sem marketing; quando ele pedir opinião, escolha um lado. Não termine com "se quiser posso detalhar".

O plano completo, com o problema, as personas, o modelo de domínio, as fases e os portões, está em **`docs/MESAMAR_PROMPT.md`**. É a fonte da verdade do escopo.

---

## 2. Estado atual, fase por fase

| Fase | Situação |
|---|---|
| 0 — Fundação | ✅ Na `main` |
| 1 — Landing + lista de espera + teste de sinal + /admin + deploy | ✅ Na `main` (PR #2). Deploy em `mesamar.0mega.fun` **ainda não feito** pelo Matheus |
| Portão A (validação em campo + medição de sinal) | ⏳ O Matheus faz **em paralelo** (decisão dele). Não bloqueia o código |
| **2 — Contas, barraca, equipe, guarda-sóis, cardápio, app** | 🔶 Branch `fase-2-contas`, **PR #3 (rascunho)** |
| 3 — Check-in, QR e pedido pelo cliente | ⬜ |
| 4 — Fila + tempo real + Web Push | ⬜ |
| 5 — Fechar conta, Pix, pagamentos, relatório | ⬜ |
| 6 — Modo atendente offline | ⬜ (pode vir depois do piloto, se o sinal nas praias for bom) |
| 7 — Preparação do piloto | ⬜ |

### Checkpoints da Fase 2

| Checkpoint | Conteúdo | CI | Aprovação |
|---|---|---|---|
| 2.1 | Convites no /admin, cadastro/login/troca de senha do dono, painel com barraca, regras e Pix | ✅ verde (170 testes) | ✅ aprovado |
| 2.2 | Equipe (atendentes com PIN, bloqueio, desbloqueio) e guarda-sóis em lote com limite | ✅ verde (201 testes) | ✅ aprovado |
| 2.3 | Cardápio: categorias, produtos, ordem, esgotado com um toque, tirar do cardápio | ✅ verde (com o 2.4) | ✅ aprovado |
| **2.4** | **App do quiosque (PWA) em `/staff/` + API `/api/staff`** | ✅ verde (218 testes, 10 E2E) | ⏳ pendente |

**Sobre o 2.3:**
- O CI do commit `ed43ee5` falhou em 3 testes de `CardapioTest`. **Não era bug do app:** o cliente HTTP dos testes não segue redirecionamentos, então a mensagem "flash" de um passo de preparação ficava na sessão e aparecia no lugar da mensagem do passo testado.
- A correção está no commit `test(cardapio): passos de preparação seguem o redirect…`, já verde, assim como o E2E com guarda-sóis e cardápio.

**Sobre os ✗ vermelhos no histórico do PR #3:**

| Commit | O que foi | Situação |
|---|---|---|
| `98be114` | Smoke test do compose falhou: o indicador de saúde do e-mail derrubava `/actuator/health` (compose passa `SPRING_MAIL_HOST` vazio) | Corrigido em `f0e99d6` (verde depois) |
| `80eef57` | E2E travou ao clicar no tipo de chave Pix (rádio transparente sobre o texto) | Corrigido em `aab7dc5` (verde depois) |
| `e430cea` | **Cancelado**, não falhou: o push seguinte cancela o CI anterior (`concurrency: cancel-in-progress`) | Normal |
| `ed43ee5` | Os 3 testes do cardápio descritos acima | Corrigido em `5f76a10` (verde depois) |

Nada fora do esperado: todos os problemas foram entendidos e corrigidos.

---

## 3. Como rodar e testar

### Com Docker (o mais simples)
```bash
cp .env.example .env        # defina MESAMAR_ADMIN_PASSWORD
docker compose up --build   # http://localhost:8080
```
- Landing `/` · Admin `/admin` · Painel do dono `/painel` · App do quiosque `/staff/` · Teste de sinal `/teste-sinal`.
- Sem SMTP, o link de "esqueci a senha" aparece **no log** (`docker compose logs app`).

### Desenvolvimento
```bash
./gradlew ktlintCheck                         # lint (o CI roda isto primeiro)
./gradlew :backend:test                       # testes com PostgreSQL real (Testcontainers: precisa de Docker)
./gradlew :backend:bootRun -PskipFrontendBuild
cd web-staff && npm ci && npm run build       # tsc + vite (o Gradle faz isso sozinho no build normal)
cd e2e && npm ci && E2E_BASE_URL=http://localhost:8080 E2E_ADMIN_PASSWORD=<a do .env> npx playwright test
```

### Roteiro manual completo (para a entrega final)
`docs/testar-localmente.md`, itens 1 a 42.

### CI (GitHub Actions, `.github/workflows/ci.yml`)
- ktlint → build e testes → imagem Docker → smoke do compose → E2E (Playwright, Pixel 7) → push para o GHCR (só na `main`).
- **As falhas viram anotações** (`.github/scripts/annotate-failures.sh`, `test-summary.sh`, `e2e-summary.mjs`). Leia o motivo pela API de check-runs, sem abrir o log inteiro:
  ```bash
  gh api repos/matheusLima-jv/MesaMar/commits/<sha>/check-runs --jq '.check_runs[] | [.name,.conclusion,.id] | @tsv'
  gh api repos/matheusLima-jv/MesaMar/check-runs/<id>/annotations --jq '.[] | .title + " | " + .message'
  ```

---

## 4. Checkpoint 2.4: o que foi feito

O backend do 2.4 foi escrito numa sessão **sem acesso ao Maven**. Na sessão seguinte, ele compilou sem nenhum ajuste e passou em tudo: ktlint, 218 testes, smoke e 10 E2E.

**Arquivos novos ou alterados no backend:**
- `staff/StaffAuth.kt`:
  - `StaffPrincipal` (Serializable, com `barracaId`, `papel` DONO|ATENDENTE, `usuarioId`/`atendenteId`, `nome`);
  - `StaffSessao` (um `HttpSessionSecurityContextRepository` com a chave `MESAMAR_STAFF`);
  - `StaffPrincipalCheckFilter`, que revalida a cada requisição se o atendente continua ativo e a barraca ativa. **Não é `@Component` de propósito**, ver seção 6.
- `staff/StaffLoginService.kt`: login do dono (reaproveita `OwnerUserDetailsService` + BCrypt via `DaoAuthenticationProvider`) e do atendente (`AtendenteService.autenticar`).
- `staff/StaffController.kt`, rotas em `/api/staff`:
  - `GET /sessao` (entrega o CSRF);
  - `GET /barracas/{codigo}/atendentes`;
  - `POST /entrar/dono`, `POST /entrar/atendente`, `POST /sair`;
  - `GET /cardapio`;
  - `POST /produtos/{id}/esgotado` (**definir**, não alternar).
- `config/SecurityConfig.kt`: nova `staffChain` `@Order(3)` (a pública passou a `@Order(4)`), com:
  - CSRF por `HttpSessionCsrfTokenRepository` + `CsrfTokenRequestAttributeHandler` (token "cru" no cabeçalho `X-CSRF-TOKEN`);
  - 401/403 em JSON;
  - o filtro de revalidação antes do `AnonymousAuthenticationFilter`.
- `barraca/Barraca.kt`: `BarracaRepository.findBySlugAndAtivaTrue`.
- `cardapio/CardapioService.kt`: `definirEsgotado`.
- Testes: `staff/StaffApiTest.kt` (9 testes) e `support/HttpTestClient.postJson(…, headers)`.

**Pontos mais delicados** (confira primeiro se algo quebrar ao mexer aqui):
1. `StaffController`: parâmetro `csrf: CsrfToken` nos handlers (resolvido pelo `CsrfTokenArgumentResolver` do Spring Security).
2. `StaffLoginService.entrarDono`: `catch (e: AuthenticationException)` com `e` sem uso (só deve gerar aviso).
3. `SecurityConfig.staffChain`: nomes e pacotes de `CsrfTokenRequestAttributeHandler`, `HttpSessionCsrfTokenRepository` e `AnonymousAuthenticationFilter` no Spring Security 7, e os lambdas de `authenticationEntryPoint`/`accessDeniedHandler`.
4. `gravarSessao()` no controller: `request.changeSessionId()` + `staffSessao.repository.saveContext(...)`. Se o login "não pegar" (401 logo depois do 200), o problema está aqui.
5. `StaffApiTest.barracaPronta()` depende de HTML do painel (regex do código da barraca `<span class="mono">…</span>` e do link `produtos/novo?categoria=`).

**E2E:** `e2e/tests/painel.spec.ts`, passo 8 (a atendente entra no app em outro "celular" e devolve a água de coco ao cardápio). Passou.

---

## 5. Mapa do código

```
backend/src/main/kotlin/br/com/mesamar/
├── admin/        /admin: leads, convites, sinal, CSV (usuário único via .env)
├── barraca/      Barraca + /painel (home/checklist, dados, regras, Pix) + PainelPage/PainelAdvice
├── cardapio/     Categoria, Produto, /painel/cardapio
├── common/       ChavePix, Money (centavos), SecureTokens, RateLimiter (memória), EmailGateway, Turnstile, Praia, BrazilianPhone
├── config/       SecurityConfig (4 cadeias), SiteProperties, cache estático, rotas dos fronts, Clock
├── conta/        Usuario, Convite, ResetSenha, login do painel, cadastro por convite, troca de senha
├── equipe/       Atendente (PIN, bloqueio), /painel/equipe
├── guardasol/    GuardaSol, /painel/guarda-sois (lote com limite)
├── landing/      landing, privacidade, SEO
├── lead/         lista de espera
├── sinal/        /teste-sinal
└── staff/        API do app do quiosque (/api/staff)
backend/src/main/resources/
├── db/migration/ V1…V5 (Flyway)
├── templates/    landing/, admin/, painel/ (Thymeleaf)
└── static/css/   landing.css (base), painel.css (painel)
web-staff/        app do quiosque (TS puro + Vite): src/api.ts, src/dom.ts, src/views/*, public/sw.js, public/manifest.json
web-customer/     página do cliente: AINDA É PLACEHOLDER (Fase 3)
e2e/              Playwright: landing, lista-de-espera, teste-sinal, admin, painel (fluxo completo do dono + app)
docs/             MESAMAR_PROMPT.md (plano), adr/0001–0006, relatorios/fase-0..2, deploy.md, testar-localmente.md
```

**Quatro cadeias de segurança (`SecurityConfig`), cada uma com a sua chave na sessão:**

| Ordem | Rotas | Login | Chave na sessão |
|---|---|---|---|
| 1 | `/admin/**` | usuário único do `.env` | `MESAMAR_ADMIN` |
| 2 | `/painel/**` | dono, e-mail + senha, form | `MESAMAR_PAINEL` |
| 3 | `/api/staff/**` | dono ou atendente, JSON | `MESAMAR_STAFF` |
| 4 | resto | público, sem cookies na landing | — |

---

## 6. Convenções e armadilhas já descobertas (não repita os erros)

- **Isolamento entre barracas é regra de ouro.** Nenhuma URL do painel ou do app leva id de barraca: a barraca sempre vem de quem está logado (`PainelAdvice` / `StaffPrincipal`). Toda busca usa `findByIdAndBarracaId`. **Todo endpoint novo ganha teste de isolamento** (dono da barraca B tentando cada ação com ids de A). Onde der, reforce no banco (ex.: FK composta `produto(categoria_id, barraca_id)`).
- **Dinheiro sempre em centavos (`Long`)**. Use `Money.parseReais`, `Money.format` e `Money.toInput`. Nunca `Double`.
- **Filtros (`OncePerRequestFilter`) não podem ser `@Component`:** o Spring Boot registra todo `Filter` como filtro global, e aí ele é pulado dentro da cadeia de segurança. Crie a instância dentro do bean da cadeia.
- **Testes e mensagens flash:** o `HttpTestClient` **não segue redirects**. Se um passo de preparação faz POST e ninguém abre a página seguinte, a mensagem flash fica na sessão e aparece depois, no lugar errado. Nos passos de preparação, siga o redirect (ver `seguir()` em `CardapioTest`), ou faça sempre POST → GET.
- **Cada teste usa um IP próprio** (`TestIps.next()`, via `X-Forwarded-For`) para os limites por IP não vazarem entre testes. Use e-mails únicos (`PainelTestSupport.novoEmail()`).
- **Thymeleaf:** em **atributos** ele escapa acentos (`é` vira `&eacute;`); em `th:text`, não. Nos asserts de HTML, prefira texto de `th:text`.
- **Senha e PIN nunca voltam preenchidos no HTML**: não use `th:field` em campos de senha/PIN.
- **CSP restrita** (`default-src 'self'`, sem script nem style inline). Nada de `onclick=""` nem `style=""` no HTML. Os E2E falham com qualquer erro de console.
- **Um comentário KDoc com `/` seguido de `**`** abre um comentário aninhado no Kotlin e quebra a compilação. Escreva de outro jeito.
- **ktlint** roda antes de tudo no CI. Rode `./gradlew ktlintFormat` antes de cada commit.
- **O compose passa variáveis vazias** (ex.: `SPRING_MAIL_HOST=`). Trate vazio como "não configurado".

---

## 7. O que falta, checkpoint por checkpoint

> Cada item abaixo é um checkpoint: implementar, testar (inclusive isolamento), CI verde, relatório e **parar para aprovação**. Detalhes de escopo em `docs/MESAMAR_PROMPT.md`.

### Fase 2 (fechamento)
- **2.4:** CI verde e relatório atualizado → **aprovação** → **tirar o PR #3 do rascunho e o Matheus decide o merge.**

### Fase 3: check-in, QR e pedido pelo cliente (núcleo)

**3.1 — Check-in e QR:**
- Migração V6 com `cliente`, `comanda` e contador diário por barraca para `numeroChegada`.
- **Token de acesso da comanda com ≥128 bits**, usando o `SecureTokens` que já existe. Pode ser guardado só como hash, ou em claro com índice único. Decida e registre em ADR, considerando que o QR precisa ser reexibido.
- **No app, aba Clientes:** botão "Novo cliente" → nome, guarda-sol (seletor rápido com os ativos) e WhatsApp opcional, mais o consentimento → **QR em tela cheia** gerado no navegador (biblioteca JS de QR, pequena) e "Enviar pelo WhatsApp" (`wa.me`).
- Lista dos clientes ativos por ordem de chegada, com "mostrar QR de novo". Meta: check-in em ≤20 s.

**3.2 — Página do cliente:**
- Rota `/c/{token}`: fazer o forward para o bundle de `web-customer`, que hoje é placeholder em `/cliente/`.
- Cabeçalho "Olá, Ana · Guarda-sol 12", cardápio por categoria com esgotados desabilitados, carrinho com observação por item.
- **Pedido idempotente:** o UUID é gerado no celular; o mesmo POST 3 vezes gera 1 pedido. Reenvio automático.
- API `/api/c/{token}/…` **sem sessão** (o token é a credencial), com rate limit por token.
- Snapshot de nome e preço no `ItemPedido`.

**3.3 — Acompanhamento e regras:**
- "Meus pedidos" com o status (polling de 15 s; o SSE vem na Fase 4) e o saldo sempre visível.
- Bloqueios: pedidos pausados, limite de saldo, modo "pagar por pedido", comanda aguardando pagamento ou fechada.
- **E2E multi-contexto:** o dono faz o check-in, outro contexto abre o QR e pede 3 itens, e o pedido é conferido.

### Fase 4: fila e notificações
- **4.1:** aba Fila no app (ordem de chegada pelo relógio do **servidor**; tempo de espera com cor; Em preparo / Entregue / Cancelar com motivo; filtros). **SSE com heartbeat de 25 s.**
  - Decisão já tomada: publicar os eventos via **PostgreSQL `LISTEN/NOTIFY`**, e não só em memória. Custa quase o mesmo e deixa pronto para mais de um servidor.
  - O cliente vê o status em tempo real.
- **4.2:** Web Push (VAPID) para dono e atendentes.
  - Plano: implementar a criptografia **RFC 8291** com a criptografia do próprio JDK, testada contra os **vetores de teste da RFC**, para não depender de biblioteca abandonada.
  - Inscrições inválidas (410) são removidas.
  - Alerta de atraso: tarefa agendada com **ShedLock**, para não rodar em dobro com dois servidores.
  - "Pausar pedidos".

### Fase 5: fechamento e pagamento
- **5.1:** "Fechar conta" (máquina de estados `ABERTA → AGUARDANDO_PAGAMENTO → FECHADA`; voltar só sem pagamento confirmado).
  - **BR Code Pix estático** com CRC16-CCITT.
  - Testar contra o payload de exemplo do manual do BCB: chave `123e4567-e12b-12d1-a456-426655440000`, "Fulano de Tal", "BRASILIA", txid `***`, CRC esperado `1D3D`. Conferir a fonte e documentar.
- **5.2:** aviso "indo pagar" (SSE + push) com valor, forma e troco; aba Pagamentos; confirmação total/parcial/dividida com troco; aviso "confira no app do banco" para Pix; bloquear comanda; fechamento forçado com motivo.
- **5.3:** relatório do dia + CSV; job diário de anonimização LGPD (clientes com mais de 90 dias; mantém os valores).

### Fase 6: modo atendente offline
- Lançar pedido pelo atendente (`origem=ATENDENTE`); fila de ações no IndexedDB (Dexie) com UUID, reenvio em ordem e backoff; check-in offline; indicador 🟢/🟠/🔴; regras de conflito em ADR.
- A API de "esgotado" já é um "definir", pensada para isso.

### Fase 7: piloto
- Backup `pg_dump` diário com rotação de 14 dias e **teste de restauração documentado**.
- Barraca de demonstração.
- Guia de 1 página e plaquinha de balcão (páginas imprimíveis).
- Botão "reportar problema" nos dois apps.
- Revisão final por um agente independente.

---

## 8. Pendências fora do código (do Matheus)

- **Deploy** em `mesamar.0mega.fun`, seguindo o `docs/deploy.md`.
- **SMTP em produção**, para o "esqueci a senha" funcionar: escolher o serviço e criar SPF/DKIM na Cloudflare, manualmente.
- **Portão A:** divulgar a landing, visitar barracas e medir o sinal em ≥3 praias num domingo, das 12h às 15h. O resultado decide se a Fase 6 vem antes do piloto.
- **Testes manuais da entrega final:** `docs/testar-localmente.md`, itens 1 a 42 até aqui.

## 9. Riscos conhecidos e decisões de escala

- **Escala:** um servidor e um Postgres aguentam com folga o piloto e dezenas de barracas. Para rodar em **dois servidores**, faltam 4 pontos conhecidos:
  1. `RateLimiter` em memória;
  2. sessão HTTP em memória (Spring Session JDBC);
  3. eventos de tempo real (resolvidos na Fase 4 com `LISTEN/NOTIFY`);
  4. tarefas agendadas (ShedLock).

  **Não distribuir antes de precisar.**
- **Disponibilidade** é o risco real da primeira temporada (uma VPS só). As mitigações previstas:
  - pedido idempotente (Fase 3);
  - modo offline (Fase 6);
  - backup e deploy fora do horário de praia (Fase 7).
- **iPhone só recebe push com o app instalado na tela inicial.** O alerta de atraso é a rede de segurança.
