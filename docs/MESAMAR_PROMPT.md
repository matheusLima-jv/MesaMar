# MesaMar — Levantamento do projeto + Prompt de execução por fases (v2)

> Documento para colar num agente de código (ex.: Claude Code) na raiz do repositório `matheusLima-jv/mesamar`.
> A **Parte A** é o contexto que o agente precisa entender. A **Parte B** é o prompt de execução com regras e fases.
> Cada fase termina num **PORTÃO DE VALIDAÇÃO**: o agente para, entrega um relatório e só continua após a aprovação explícita do Matheus.
>
> **Mudança da v2:** o **módulo do cliente** (check-in no quiosque → QR pessoal → cardápio no celular → pedido direto para o dono → aviso de pagamento) passa a ser o **núcleo do MVP**. O modo atendente vira **contingência**.

---

## PARTE A — Levantamento do projeto

### A1. Problema

Nas praias do Guarujá, os carrinhos e quiosques de areia atendem dezenas de guarda-sóis com poucos atendentes. O controle é feito de memória e em caderninho. O resultado:

- o cliente **espera para ser visto** e desiste do segundo pedido;
- ninguém sabe com certeza **o que cada cliente pediu** nem **em que ordem**;
- pedidos são **esquecidos ou entregues em duplicidade**;
- no fim do dia é difícil saber **quem pagou, quanto e como**;
- o cliente não tem um **extrato confiável** e desconfia da conta.

### A2. Proposta de valor (MVP)

**O cliente pede e fecha a conta pelo próprio celular, sem chamar garçom.** O dono recebe tudo em fila, por ordem de chegada.

Fluxo principal:

1. **Check-in no quiosque:** o cliente chega e o dono ou atendente faz um cadastro breve (nome, WhatsApp opcional, número do guarda-sol) em até 20 segundos.
2. **QR pessoal:** o sistema gera na hora um **QR Code exclusivo da comanda daquele cliente**. O cliente lê com a câmera (sem baixar app) e o link também pode ser enviado por WhatsApp.
3. **Cardápio e pedido:** o cliente vê todos os itens à venda, monta o carrinho e **confirma**. O pedido vai **direto para o dono**, sem intermediário.
4. **Fila do dono:** o dono recebe uma **notificação** e vê os pedidos numa **fila por ordem de chegada** (FIFO), com o guarda-sol e o tempo de espera. Ele marca "em preparo" e "entregue", e o cliente acompanha o status no celular.
5. **Fechamento pelo app:** o cliente toca em **"Fechar conta"**, vê o extrato completo e escolhe como vai pagar:
   - **Pix:** o QR e o "copia e cola" aparecem no **próprio celular do cliente**, com a chave do barraqueiro;
   - **dinheiro ou cartão:** o cliente sinaliza **"estou indo pagar"**.
   Em ambos os casos o dono recebe a notificação *"Guarda-sol 12 · Ana está indo pagar · R$ 87,50 · Dinheiro"*.
6. **Confirmação:** o dono confirma o recebimento, a comanda fecha e o cliente mantém o **extrato** por 7 dias.

**Contingência:** um **modo atendente** para lançar pedidos de quem não tem celular, sem bateria ou sem sinal.

### A3. Personas

| Persona | Contexto | O que precisa |
|---|---|---|
| **Dono da barraca** | Opera de dezembro a março, usa WhatsApp e Pix, pouca paciência para configurar sistema | Fazer check-in rápido, receber pedidos em fila com notificação, saber quem vai pagar e o faturamento do dia |
| **Atendente** | Mão molhada, sol na tela, correria | Check-in rápido, ver o que entregar e onde, lançar pedido para quem não usa o celular |
| **Banhista (cliente)** | Não quer baixar app nem criar conta com senha | Ler o QR, pedir em poucos toques, ver o status, saber quanto está devendo e pagar sem esperar |

### A4. Restrições e premissas

- **O cliente depende de internet:** o módulo do cliente roda no celular dele e **precisa de rede**. Em praia lotada o 4G pode congestionar. Mitigações:
  - a página do cliente é **leve** (meta: abaixo de 150 KB transferidos no primeiro acesso, cardápio em cache pelo Service Worker);
  - pedido com **reenvio automático** e **idempotência** (UUID gerado no celular; reenviar não duplica);
  - aviso claro quando o pedido não foi enviado: *"Sem sinal. Tentaremos de novo. Se preferir, chame um atendente."*;
  - o **modo atendente** funciona offline como contingência (Fase 6).
- **Notificações do dono:** som + vibração + destaque visual no painel aberto (SSE) **e** **Web Push**, para avisar com a tela bloqueada.
  - No **iPhone**, o Web Push só funciona com o app instalado na tela inicial (iOS 16.4 ou mais recente). Isso precisa estar no onboarding.
  - Não usar a API paga do WhatsApp para notificar no MVP.
- **Uso ao sol:** alto contraste, botões grandes (mínimo 48px), pouco texto, fonte grande, tanto no app do dono quanto na página do cliente.
- **Sem custódia de dinheiro:** o sistema **nunca** recebe nem repassa pagamentos. O Pix é um BR Code estático com o valor e a chave do barraqueiro. A **confirmação é manual** pelo dono no MVP.
- **Calote e trote:** como o pedido vai direto sem aprovação, o risco é controlado por:
  - a comanda **só existe após o check-in presencial** feito pela equipe (não há autocadastro remoto);
  - o **token do QR** é aleatório, de 128 bits e não enumerável, e expira quando a comanda fecha;
  - um **limite de saldo em aberto** configurável por barraca (ex.: acima de R$ 150, pede pagamento parcial antes de novos pedidos);
  - um **modo de pagamento** configurável: *pagar no final* (padrão) ou *pagar a cada pedido*;
  - o dono pode **pausar pedidos**, **bloquear uma comanda** e **cancelar itens** com motivo (o cliente é avisado).
- **Regulação local (Guarujá):**
  - existe limite de guarda-sóis e cadeiras por ambulante ou quiosque, então o limite é configurável;
  - a cobrança de consumação mínima pelo uso de cadeiras e guarda-sóis é proibida (venda casada);
  - há indicação de que o **aluguel** de cadeiras e guarda-sóis é proibido em Guarujá (**confirmar**).
  - **Decisão:** o MVP **não** tem produto do tipo "aluguel" nem "consumação mínima". O guarda-sol é só um identificador de local.
- **LGPD** (agora há coleta de nome e WhatsApp):
  - finalidade declarada: identificar a comanda e enviar o link da comanda;
  - consentimento registrado no check-in, com texto curto lido ou mostrado ao cliente;
  - WhatsApp **opcional**;
  - **anonimização automática após 90 dias** (nome → "Cliente", WhatsApp apagado), mantendo os valores para os relatórios;
  - política de privacidade acessível pela página do cliente.
- **Sazonalidade:** o uso real é concentrado entre dezembro e março. **O piloto precisa estar pronto no início de dezembro de 2026.**

### A5. Escopo do MVP testável

**Dentro do MVP:**
- Landing page com lista de espera (piloto grátis)
- Cadastro do dono e da barraca, login; cadastro de atendentes com PIN
- Cadastro de guarda-sóis (numeração) e cardápio (categorias, produtos, preço, **esgotado com 1 toque**)
- **Check-in do cliente** no quiosque com geração do **QR pessoal** (e envio do link pelo WhatsApp via `wa.me`, sem API paga)
- **Página do cliente:** cardápio, carrinho, observação por item, confirmação do pedido, status dos pedidos, extrato e saldo
- **Fila de pedidos do dono** por ordem de chegada, com status *Recebido → Em preparo → Entregue* e cancelamento com motivo
- **Notificações do dono:** novo pedido, cliente indo pagar, pedido esperando além do tempo limite
- **Fechamento pelo cliente:** extrato, escolha da forma de pagamento, Pix no celular do cliente, aviso "estou indo pagar"
- Confirmação de pagamento pelo dono, pagamento dividido, troco
- Extrato do cliente por 7 dias após o fechamento
- Relatório do dia
- Modo atendente com operação offline (contingência)

**Fora do MVP (backlog):**
- Integração com PSP para confirmar o Pix automaticamente
- Pagamento com cartão dentro do app
- Emissão de nota fiscal
- Controle de estoque
- App nativo (Android/iOS)
- Notificação para o cliente via API do WhatsApp
- Autocadastro remoto do cliente (QR fixo no balcão)
- Multi-idioma
- Programa de fidelidade

**Condicional (só após o piloto aprovado):**
- Cobrança por temporada e onboarding self-service; múltiplas barracas por dono

### A6. Métricas de sucesso

| Momento | Métrica | Meta |
|---|---|---|
| Landing (Portão A) | Leads qualificados (barraqueiros reais) | ≥ 5 leads **ou** ≥ 3 donos que aceitam o piloto em conversa presencial |
| Landing (Portão A) | Sinal na praia | Página de teste abre em até 5s num domingo de sol, em 3 praias diferentes |
| Piloto (Portão B) | Retenção espontânea | A barraca usa **no fim de semana seguinte sem o Matheus pedir** |
| Piloto | Adoção pelo cliente | ≥ 50% dos clientes com check-in fazem pelo menos 1 pedido pelo celular |
| Piloto | Tempo do pedido até a entrega | Mediana ≤ 10 minutos |
| Piloto | Calote | Zero comandas abandonadas com saldo acima do limite configurado |
| Piloto | Divergência no fechamento | Nenhuma reclamação de conta errada atribuída ao sistema |
| Técnico | Resiliência | Pedido reenviado após falha de rede nunca duplica |

### A7. Hipótese de preço (a validar, não implementar agora)

Cobrança **por temporada** (dezembro a março), entre R$ 149 e R$ 199 por barraca. O piloto é gratuito na temporada 2026/27 em troca de feedback.

---

## PARTE B — Prompt de execução

### B1. Papel e regras do agente

Você é o engenheiro responsável pelo MesaMar. Siga estas regras sem exceção:

1. **Trabalhe apenas na fase atual.** Não antecipe funcionalidades de fases futuras.
2. **Portões de validação:** ao terminar uma fase, **PARE** e entregue o relatório da seção B4. Só avance quando receber a mensagem exata `APROVADO FASE N`. Se receber `AJUSTAR FASE N: ...`, corrija e entregue o relatório de novo.
3. **Não invente requisitos.** Se algo não estiver especificado e a decisão for difícil de desfazer, pergunte. Se for fácil de desfazer, decida, registre em ADR (`docs/adr/NNNN-titulo.md`) e siga.
4. **Testes são obrigatórios.** Nenhuma fase é entregue com teste falhando ou sem os testes listados na fase.
5. **Commits pequenos** no padrão Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, `chore:`), com uma branch por fase (`fase-N-nome`) e um PR para `main` ao final.
6. **Segredos nunca vão para o repositório** (incluindo as chaves VAPID do Web Push). Use `.env` (ignorado pelo git) e mantenha um `.env.example` atualizado.
7. **Infraestrutura do servidor:** **nunca** altere regras de firewall (UFW), a porta de SSH nem a configuração do Cloudflare. Produza apenas arquivos (`docker-compose`, `nginx.conf`, scripts) e um passo a passo em `docs/deploy.md` que o Matheus executa manualmente.
8. **README** sempre atualizado com como rodar localmente, como testar e como fazer o deploy.
9. Idioma: código, identificadores e commits em **inglês**. Textos de interface e documentação em **português do Brasil**.

### B2. Stack e decisões técnicas

| Área | Decisão | Por quê |
|---|---|---|
| Linguagem | Kotlin 2.x | Exigência do projeto |
| Framework | Spring Boot, última versão estável (4.x). Se houver incompatibilidade relevante, use 3.5.x e registre em ADR | Exigência + suporte de longo prazo |
| JDK | 21 LTS | Máxima compatibilidade de ecossistema |
| Build | Gradle Kotlin DSL, version catalog (`libs.versions.toml`) | Padrão Kotlin |
| Banco | PostgreSQL 16+ | Robusto e relacional |
| Migrações | Flyway | Versionamento de schema explícito |
| Persistência | Spring Data JPA + plugins `kotlin-jpa` e `kotlin-allopen`; SQL nativo nos relatórios | Familiar e produtivo |
| Landing + páginas administrativas simples | Thymeleaf + HTMX, CSS próprio | Rápido, bom para SEO, pouco JavaScript |
| **App do dono/atendente** | PWA em TypeScript + Vite + Dexie (IndexedDB), servido pelo Spring | Web Push, som, instalação na tela inicial e offline no modo atendente |
| **Página do cliente** | Página web leve (TypeScript + Vite, **sem framework pesado**) com Service Worker para cachear o cardápio. **Não** pede instalação | O cliente não deve baixar nada; a página precisa carregar em rede ruim |
| Tempo real | SSE com heartbeat a cada 25s: canal do dono (fila) e canal da comanda (status para o cliente) | Mais simples que WebSocket; o heartbeat evita o timeout de proxy do Cloudflare |
| Notificação push | Web Push com VAPID (biblioteca `web-push` para Java/Kotlin ou equivalente) apenas para dono e atendentes | Avisa com a tela bloqueada, sem custo por mensagem |
| Autenticação | Spring Security com sessão e cookie `HttpOnly`/`Secure`/`SameSite=Lax`. Dono: e-mail + senha (BCrypt/Argon2). Atendente: código da barraca + PIN com limite de tentativas. **Cliente: token da comanda no QR** (sem senha) | Zero atrito para o cliente; a segurança vem do token aleatório e do check-in presencial |
| Multi-tenancy | `barraca_id` em todas as tabelas de negócio; escopo aplicado na camada de serviço e **coberto por testes de isolamento** | Simples e suficiente para o MVP |
| QR Codes | Gerados **no navegador** do dono na hora do check-in (biblioteca JS de QR) e no servidor (ZXing) quando necessário | Instantâneo, funciona com rede fraca |
| Pix | Gerador próprio de BR Code (padrão EMV do Banco Central, com CRC16-CCITT), estático com valor e `txid` | Sem PSP, sem custódia de dinheiro |
| Anti-spam (landing) | Cloudflare Turnstile + honeypot + rate limit (Bucket4j) | O domínio já passa pelo Cloudflare |
| Rate limit (cliente) | Limite de pedidos por comanda por minuto e de requisições por token | Evita abuso e loops de reenvio |
| Testes | JUnit 5, MockK, Kotest assertions, Testcontainers (PostgreSQL), MockMvc/WebTestClient, Playwright (E2E multi-contexto: cliente + dono) | Cobertura real de banco, navegador e fluxo entre dois atores |
| Qualidade | ktlint + detekt no CI | Consistência |
| Observabilidade | Spring Actuator, logs estruturados em JSON, métricas básicas (pedidos/min, latência da fila) | Diagnóstico no servidor |
| Deploy | Docker multi-stage + Docker Compose (app + postgres) atrás do Nginx existente, com Cloudflare Full (Strict) | Reaproveita a infraestrutura atual da VPS |
| CI/CD | GitHub Actions: lint → testes → build da imagem → push para GHCR. Deploy manual documentado | Nada mexe no servidor sem ação humana |
| Analytics da landing | Cloudflare Web Analytics (sem cookies) | Gratuito e compatível com a LGPD |

**Estrutura do repositório:**

```
mesamar/
├── backend/                 # Spring Boot (Kotlin)
│   ├── src/main/kotlin/...  # pacotes por domínio: lead, account, barraca, catalog, checkin, order, bill, payment, notification, report
│   ├── src/main/resources/
│   │   ├── templates/       # Thymeleaf (landing, páginas administrativas)
│   │   ├── static/          # CSS, imagens, builds das PWAs copiados aqui
│   │   └── db/migration/    # Flyway
│   └── src/test/kotlin/...
├── web-staff/               # PWA do dono/atendente (TS + Vite + Dexie)
├── web-customer/            # Página do cliente (TS + Vite, leve)
├── e2e/                     # Playwright
├── deploy/                  # docker-compose.prod.yml, nginx snippet, scripts
├── docs/
│   ├── adr/
│   ├── deploy.md
│   └── relatorios/
└── README.md
```

### B3. Modelo de domínio (referência)

```
Barraca(id, nome, slug, praia, chavePix, tipoChavePix, nomeRecebedorPix, cidadePix,
        limiteGuardaSois, modoPagamento=FINAL|POR_PEDIDO, limiteSaldoAbertoCentavos?,
        tempoAlertaPedidoMin, pedidosPausados, ativa, criadaEm)

Usuario(id, barracaId, nome, email, senhaHash, papel=OWNER)
Atendente(id, barracaId, nome, pinHash, ativo, tentativasFalhas, bloqueadoAte)
GuardaSol(id, barracaId, numero, ativo)
Categoria(id, barracaId, nome, ordem)
Produto(id, barracaId, categoriaId, nome, descricao?, precoCentavos, ativo, esgotado, ordem)

Cliente(id, barracaId, nome, whatsappE164?, consentimentoEm, criadoEm, anonimizadoEm?)

Comanda(id UUID, barracaId, clienteId, guardaSolId?, numeroChegada (sequencial por barraca/dia),
        tokenAcesso (128 bits, único), status=ABERTA|AGUARDANDO_PAGAMENTO|FECHADA|CANCELADA|BLOQUEADA,
        metodoPagamentoPretendido?, avisoPagamentoEm?, abertaPor, abertaEm, fechadaEm?)

Pedido(id UUID gerado no cliente, barracaId, comandaId, numeroPedido (sequencial por barraca/dia),
       origem=CLIENTE|ATENDENTE, status=RECEBIDO|EM_PREPARO|ENTREGUE|CANCELADO,
       criadoEm, emPreparoEm?, entregueEm?, canceladoEm?, motivoCancelamento?, atendidoPor?)

ItemPedido(id, pedidoId, produtoId, nomeSnapshot, precoSnapshotCentavos, quantidade, observacao?,
           cancelado, motivoCancelamento?)

Pagamento(id UUID, comandaId, metodo=PIX|DINHEIRO|CARTAO, valorCentavos, valorRecebidoCentavos?,
          confirmadoPor, confirmadoEm)

PushSubscription(id, barracaId, usuarioId?, atendenteId?, endpoint, p256dh, auth, criadoEm, ultimoUsoEm)
Notificacao(id, barracaId, tipo=NOVO_PEDIDO|INDO_PAGAR|PEDIDO_ATRASADO|PEDIDO_CANCELADO,
            referenciaId, criadaEm, lidaEm?)

Lead(id, nome, nomeBarraca, praia, whatsapp, consentimentoEm, origem, criadoEm)
```

**Regras importantes:**
- Valores monetários sempre em **centavos (Long)**. Nunca use `Double`.
- Nome e preço do produto ficam **congelados no item** (snapshot).
- **Fila do dono = pedidos não entregues ordenados por `criadoEm` no servidor** (ordem de chegada). Empate: `numeroPedido`. A ordem **não** pode ser alterada pelo cliente.
- Total da comanda = soma dos itens não cancelados. Saldo = total − pagamentos.
- **"Fechar conta" pelo cliente** muda a comanda para `AGUARDANDO_PAGAMENTO`, **bloqueia novos pedidos** e dispara a notificação `INDO_PAGAR`. O cliente pode desistir e voltar para `ABERTA` enquanto nenhum pagamento foi confirmado.
- A comanda **só fecha** com saldo zero confirmado pelo dono, ou com um fechamento forçado registrado com motivo.
- O `tokenAcesso` deixa de permitir pedidos quando a comanda sai de `ABERTA`. Depois de `FECHADA`, dá acesso somente leitura ao extrato por 7 dias e então expira.
- Se `limiteSaldoAbertoCentavos` for atingido, um novo pedido é recusado com uma mensagem amigável pedindo pagamento parcial.
- Se `modoPagamento = POR_PEDIDO`, o pedido só entra na fila depois de o pagamento ser confirmado pelo dono.

### B4. Relatório obrigatório ao fim de cada fase

Salvar em `docs/relatorios/fase-N.md` e também exibir na conversa:

1. **O que foi feito** (lista curta)
2. **Decisões tomadas** e links dos ADRs
3. **Como rodar e testar localmente** (comandos exatos)
4. **Resultado dos testes automatizados** (quantidade, cobertura das partes críticas)
5. **Roteiro de validação manual** para o Matheus (passo a passo, com o resultado esperado de cada passo)
6. **Limitações conhecidas e riscos**
7. **Proposta para a próxima fase** (ajustes sugeridos, sem implementar)

Encerrar com: `⏸️ Aguardando validação. Responda "APROVADO FASE N" ou "AJUSTAR FASE N: ..."`

---

## B5. Fases

### FASE 0 — Fundação técnica
**Objetivo:** esqueleto do projeto que compila, testa, gera imagem Docker e roda localmente.

**Entregas:**
- Monorepo com a estrutura da seção B2, Gradle Kotlin DSL e version catalog
- Build do `web-staff` e do `web-customer` integrado ao Gradle (os bundles são copiados para `static/`)
- Spring Boot com Actuator (`/actuator/health`), perfis `local`, `test` e `prod`
- `docker-compose.yml` local (app + PostgreSQL) e `Dockerfile` multi-stage
- Flyway com a migração inicial
- CI no GitHub Actions: ktlint, detekt, testes com Testcontainers, build dos fronts e da imagem
- README inicial e `.env.example`

**Testes obrigatórios:** smoke test do contexto Spring; integração com PostgreSQL via Testcontainers e Flyway; o build dos dois fronts gera os arquivos esperados.

**Validação manual (Matheus):**
1. `docker compose up` → `http://localhost:8080/actuator/health` retorna `UP`
2. O CI fica verde no primeiro PR
3. A estrutura de pastas está de acordo com o combinado

---

### FASE 1 — Landing page + lista de espera (primeiro objetivo)
**Objetivo:** validar o interesse de barraqueiros **antes** de construir o produto.

**Conteúdo da página** (mobile-first, carregamento abaixo de 1,5s em 4G):
1. **Título:** foco na dor. Sugestão: *"Seu cliente pede pelo celular, você recebe na ordem certa e ninguém sai sem pagar."*
2. **Como funciona**, em 4 passos com ícones: cadastro rápido no quiosque → cliente lê o QR e pede → você recebe o pedido na fila → cliente fecha a conta e você é avisado.
3. **Diferenciais:** sem app para o cliente baixar, pedidos por ordem de chegada, o dinheiro vai direto para o seu Pix, aviso quando o cliente vai pagar.
4. **Oferta:** *"Piloto gratuito na temporada 2026/27 para as primeiras barracas do Guarujá."*
5. **Formulário:** nome, nome da barraca, praia (lista: Enseada, Pitangueiras, Astúrias, Tombo, Pernambuco, Guaiúba, outra), WhatsApp e checkbox de consentimento LGPD.
6. **Botão "Falar no WhatsApp"** com mensagem pré-preenchida.
7. **FAQ curto:** "E se o cliente não tiver celular?", "E se a internet cair?", "E se o cliente for embora sem pagar?", "Quanto custa depois do piloto?"
8. **Rodapé:** página de Política de Privacidade.

**Página extra — teste de sinal:** a rota `/teste-sinal` é uma página mínima que mede e mostra o tempo de carregamento e de uma requisição à API, e registra o resultado de forma anônima com a praia escolhida num dropdown. Serve para o Matheus medir o sinal real nas praias antes da Fase 3.

**Regras:**
- **Proibido** inventar depoimentos, números de clientes ou logos de parceiros.
- Sem cookies de rastreamento; apenas o Cloudflare Web Analytics.
- SEO básico: `title`, `meta description`, Open Graph (prévia bonita no WhatsApp), `sitemap.xml`, `robots.txt`.
- Acessibilidade: contraste AA, `labels`, navegação por teclado.

**Backend:**
- Entidade `Lead` + migração Flyway; entidade `MedicaoSinal` (praia, tempos, data/hora)
- Validação do WhatsApp (formato brasileiro com DDD, normalizado para E.164)
- Anti-spam: Turnstile + honeypot + rate limit por IP
- Página de confirmação após o envio
- Rota administrativa protegida por senha (variável de ambiente) para listar os leads e as medições e exportá-los em CSV

**Deploy:**
- `deploy/docker-compose.prod.yml` e snippet de Nginx para o subdomínio (sugestão: `mesamar.r4d4r.online`, ou domínio próprio se o Matheus decidir)
- `docs/deploy.md` com o passo a passo manual

**Testes obrigatórios:**
- Validação do formulário (campos obrigatórios, WhatsApp inválido, consentimento ausente)
- Honeypot preenchido → lead descartado silenciosamente
- Rate limit → HTTP 429 após N envios
- Rota administrativa exige autenticação
- E2E (Playwright): enviar o formulário no viewport de celular; a página de teste de sinal registra uma medição

**Validação manual (Matheus):**
1. Abrir no próprio celular, ao sol se possível, e avaliar a legibilidade
2. Enviar um lead real e ver o registro no painel administrativo e no CSV
3. Compartilhar o link no WhatsApp e conferir a prévia (Open Graph)
4. Revisar e aprovar os textos da página

#### 🚦 PORTÃO A — Decisão de negócio (fora do código)
Após o deploy, o Matheus faz a validação de campo: posta nos grupos, visita barracas, conversa pessoalmente e **mede o sinal** em pelo menos 3 praias num domingo de sol, entre 12h e 15h.
**Critério para seguir:** ≥ 5 leads qualificados **ou** ≥ 3 donos que aceitam o piloto, **e** página de teste abrindo em até 5s na maioria das medições.
Se o sinal for ruim, registre em ADR e **priorize a Fase 6 (contingência) antes do piloto**.
**O agente não inicia a Fase 2 sem `APROVADO FASE 1` e a confirmação explícita do Portão A.**

---

### FASE 2 — Contas, barraca, atendentes, guarda-sóis e cardápio
**Objetivo:** o dono configura a barraca inteira pelo celular em até 10 minutos.

**Entregas:**
- Cadastro e login do dono (e-mail + senha), recuperação de senha por e-mail (SMTP configurável; no perfil `local`, apenas registrar em log)
- Cadastro da barraca: nome, praia, chave Pix (validação por tipo: CPF, CNPJ, e-mail, telefone ou aleatória), nome do recebedor, limite de guarda-sóis
- Configurações: modo de pagamento (final / por pedido), limite de saldo em aberto, tempo para alerta de pedido atrasado
- CRUD de atendentes com PIN, ativar/desativar, bloqueio após 5 tentativas erradas; login por código da barraca + PIN
- Guarda-sóis: criação em lote ("do 1 ao 20"), respeitando o limite
- CRUD do cardápio: categorias, produtos, preço, ordenação, **"esgotado" com 1 toque**
- PWA `web-staff`: login, navegação, instalação na tela inicial com instruções específicas para Android e iPhone

**Testes obrigatórios:**
- **Isolamento multi-tenant:** um usuário da barraca A **não consegue** ler nem alterar dados da barraca B (teste para cada endpoint)
- Hash de senha e PIN; bloqueio por tentativas
- Limite de guarda-sóis respeitado
- Validação de chave Pix por tipo

**Validação manual (Matheus):**
1. Criar uma barraca fictícia pelo celular, cronometrar e anotar o tempo
2. Cadastrar 2 atendentes, 10 guarda-sóis e um cardápio de 15 itens
3. Instalar o app na tela inicial (Android e, se possível, iPhone)
4. Tentar acessar os dados de outra barraca pela URL (deve falhar)

---

### FASE 3 — Check-in do cliente, QR pessoal e pedido pelo celular ⭐ núcleo
**Objetivo:** o cliente faz pedidos sozinho pelo celular após um cadastro breve no quiosque.

**Entregas — lado do dono/atendente (`web-staff`):**
- Botão grande **"Novo cliente"** → formulário com **3 campos**: nome (obrigatório), número do guarda-sol (seletor rápido) e WhatsApp (opcional), mais a confirmação de consentimento. Meta: **≤ 20 segundos**.
- Ao salvar: cria o `Cliente` + a `Comanda` (com `numeroChegada` e `tokenAcesso`) e mostra **em tela cheia** o QR pessoal, grande o suficiente para ler a 50cm sob o sol.
- Botão **"Enviar link pelo WhatsApp"**: abre `wa.me/<numero>?text=...` com o link da comanda (sem custo de API).
- Lista de clientes ativos por ordem de chegada, com a opção de reexibir o QR.

**Entregas — lado do cliente (`web-customer`, rota `/c/{token}`):**
- Cabeçalho: nome da barraca, *"Olá, Ana · Guarda-sol 12"*
- **Cardápio** por categorias, com itens esgotados visíveis porém desabilitados
- **Carrinho:** quantidade, observação por item ("sem gelo") e total do carrinho
- **Confirmar pedido** → tela de confirmação clara → envio com **UUID gerado no celular** e **reenvio automático** se falhar
- **Meus pedidos:** lista com o status em tempo real (*Recebido → Em preparo → A caminho/Entregue*) via SSE, com polling de fallback a cada 15s
- **Saldo atual** sempre visível
- Mensagens para os bloqueios: pedidos pausados pelo quiosque, limite de saldo atingido, comanda aguardando pagamento ou fechada
- Link para a política de privacidade

**Backend:**
- Endpoints do cliente autenticados **somente** pelo token (sem sessão), com rate limit por token
- Criação de pedido **idempotente** (mesmo UUID → mesmo pedido, nunca duplica)
- Validação no servidor: produtos ativos e não esgotados, comanda `ABERTA`, limite de saldo e modo de pagamento
- Publicação de eventos internos (`PedidoCriado`) para a Fase 4 consumir

**Testes obrigatórios:**
- Check-in cria cliente, comanda e token único; o token tem 128 bits de entropia
- Token de outra barraca ou de comanda fechada → sem acesso a pedidos
- Idempotência: o mesmo POST 3 vezes gera 1 pedido
- Produto esgotado ou inativo → pedido recusado com mensagem clara
- Limite de saldo e modo "por pedido" respeitados
- Snapshot de preço
- E2E: dono faz check-in → Playwright abre a URL do QR em outro contexto → cliente pede 3 itens → o pedido aparece persistido no banco

**Validação manual (Matheus):**
1. No celular A (dono): fazer o check-in de "Ana, guarda-sol 12" e **cronometrar**
2. No celular B (cliente): ler o QR com a câmera nativa, fazer um pedido, **cronometrar do QR até o pedido confirmado** e contar os toques
3. Colocar o celular B em modo avião, pedir, desligar o modo avião e confirmar que o pedido chegou **uma única vez**
4. Marcar um produto como esgotado e ver o reflexo no celular B
5. Testar a legibilidade da página do cliente ao sol

---

### FASE 4 — Fila do dono por ordem de chegada + notificações
**Objetivo:** o dono nunca perde um pedido e atende na ordem certa.

**Entregas:**
- **Tela "Fila"** no `web-staff`:
  - pedidos não entregues em **ordem de chegada** (mais antigo no topo)
  - cada cartão mostra nº do pedido, **guarda-sol em destaque**, nome do cliente, itens e observações, e **tempo de espera** com cor (verde / amarelo / vermelho pelo tempo configurado)
  - ações com 1 toque: **Em preparo**, **Entregue**, **Cancelar item/pedido** (motivo obrigatório: esgotou, cliente desistiu, outro)
  - filtros: todos / só pendentes / por guarda-sol
- Atualização em tempo real via SSE, com reconexão automática e indicador de conexão
- **Notificações para o dono e os atendentes logados:**
  - `NOVO_PEDIDO`: som + vibração no app aberto **e** Web Push com o app fechado: *"Pedido #23 · Guarda-sol 12 · 3 itens"*
  - `PEDIDO_ATRASADO`: quando passar do tempo configurado
  - tocar na notificação abre o pedido na fila
- Gestão de inscrições de push (ativar/desativar, limpar inscrições inválidas)
- **Botão "Pausar pedidos"** (ex.: pico ou fim do estoque) com mensagem para os clientes
- O cliente vê a mudança de status em tempo real e recebe um aviso na tela se um item foi cancelado, com o motivo

**Testes obrigatórios:**
- Ordenação da fila por `criadoEm` do servidor, inclusive com pedidos reenviados tarde (o horário vem do servidor, não do cliente)
- SSE entrega o evento só para a barraca correta
- Transições de status válidas (não se pode voltar de `ENTREGUE` para `RECEBIDO`; cancelado é terminal)
- Web Push: mock do serviço de push; uma inscrição inválida (410) é removida
- E2E multi-contexto: 3 clientes pedem em sequência → a fila do dono mostra a ordem correta → o dono marca entregue → a tela do cliente atualiza

**Validação manual (Matheus):**
1. Três celulares como clientes pedindo com 10s de intervalo → conferir a ordem na fila
2. Dono com o **app fechado e a tela bloqueada** → um novo pedido gera uma notificação push (testar Android; iPhone com app instalado)
3. Deixar um pedido parado além do tempo e ver o alerta de atraso
4. Pausar os pedidos e verificar a mensagem no celular do cliente

---

### FASE 5 — Fechamento pelo cliente, aviso de pagamento, Pix e extrato
**Objetivo:** o cliente fecha a conta sozinho e o dono sabe exatamente quem vai pagar, quanto e como.

**Entregas — cliente:**
- Botão **"Fechar conta"** → extrato completo (itens, quantidades, cancelados riscados, pagamentos já feitos, saldo)
- Escolha da forma de pagamento:
  - **Pix:** QR + botão **"Copiar código Pix"** no celular do cliente, com o valor do saldo e a chave, o nome e a cidade do recebedor; depois o botão **"Já paguei"**
  - **Dinheiro:** campo opcional "vou pagar com R$ ___" para o dono separar o troco; botão **"Estou indo pagar"**
  - **Cartão:** botão **"Estou indo pagar"** (maquininha no quiosque)
- Após a escolha: a comanda fica `AGUARDANDO_PAGAMENTO`, os novos pedidos são bloqueados e aparece a tela *"Aguardando confirmação do quiosque"*
- **"Voltar e pedir mais"**: possível enquanto nenhum pagamento foi confirmado
- Após a confirmação: a tela *"Conta paga ✅"* com o extrato final, acessível por 7 dias

**Entregas — dono:**
- Notificação `INDO_PAGAR` (SSE + push): *"Guarda-sol 12 · Ana está indo pagar · R$ 87,50 · Dinheiro (troco para R$ 100)"* ou *"… · Pix — conferir no app do banco"*
- Aba **"Pagamentos"** com as comandas em `AGUARDANDO_PAGAMENTO` por ordem de aviso
- Confirmar pagamento: total ou parcial, múltiplas formas, cálculo de troco
- Aviso explícito na confirmação de Pix: *"Confira no app do seu banco antes de confirmar"*
- **Comandas abertas com saldo** destacadas (proteção contra calote), com a opção de bloquear a comanda
- Fechamento forçado com motivo (auditoria)
- **Relatório do dia:** faturamento total e por forma de pagamento, nº de clientes, pedidos pelo cliente vs. pelo atendente, **tempo médio do pedido à entrega**, itens mais vendidos, cancelamentos com motivo, comandas em aberto. Exportação em CSV.

**Backend:**
- Gerador de BR Code (EMV/BCB) com `txid` derivado da comanda
- Job diário de **anonimização LGPD** (clientes com mais de 90 dias)

**Testes obrigatórios:**
- Gerador de BR Code: CRC16 correto, campos EMV válidos, testado contra payloads de referência (documentar a fonte)
- Máquina de estados da comanda: `ABERTA → AGUARDANDO_PAGAMENTO → FECHADA`; voltar para `ABERTA` só sem pagamento confirmado; sem novos pedidos fora de `ABERTA`
- Troco, pagamento parcial e dividido (em centavos)
- A comanda não fecha com saldo pendente
- O extrato expira em 7 dias; o token não é enumerável
- Relatório bate com a soma das comandas (dados gerados)
- Job de anonimização remove nome e WhatsApp e mantém os valores
- E2E: cliente pede → recebe → fecha a conta com dinheiro → o dono recebe o aviso → confirma → o cliente vê "Conta paga"

**Validação manual (Matheus):**
1. **Pagar R$ 0,01 de verdade** lendo o QR Pix no celular do cliente com o app do banco e também usando o "copia e cola". Valor, nome e chave precisam aparecer corretos.
2. Fechar uma comanda com Pix + dinheiro (pagamento dividido)
3. Avisar "estou indo pagar" e conferir a notificação no celular do dono **com a tela bloqueada**
4. Desistir do fechamento e pedir mais; depois fechar de novo
5. Conferir o relatório do dia contra os pedidos de teste

---

### FASE 6 — Contingência: modo atendente offline
**Objetivo:** a barraca continua funcionando quando o cliente não tem celular, bateria ou sinal.

> Se o Portão A indicou **sinal ruim**, esta fase vem **antes do piloto**. Se o sinal foi bom, pode ser feita **depois** da primeira semana de piloto.

**Entregas:**
- No `web-staff`: **"Lançar pedido para cliente"**. O atendente escolhe a comanda (ou faz um check-in sem QR para quem não tem celular) e lança os itens. O pedido entra na mesma fila com `origem=ATENDENTE`.
- **Offline-first no `web-staff`:** Service Worker + IndexedDB (Dexie) + **outbox** com UUIDs, reenvio em ordem, retry com backoff
- Check-in offline: gerar o token no dispositivo (aleatório seguro via `crypto.getRandomValues`) e sincronizar depois. O QR funciona para o cliente assim que o servidor recebe o check-in.
- Indicador: `🟢 online` / `🟠 offline — X ações aguardando envio` / `🔴 erro de sincronização`
- Regras de conflito (ADR): itens e pagamentos são append-only; status terminais (entregue/cancelado) — o primeiro evento terminal vence; o fechamento é validado no servidor
- Hora da última sincronização de cada dispositivo visível para o dono

**Testes obrigatórios:**
- Unitários da outbox (ordem, retry, idempotência)
- E2E em **modo offline**: lançar 10 itens offline → reconectar → servidor com exatamente 10
- Dois dispositivos offline alterando a mesma comanda → convergência sem duplicatas
- Fechar o navegador offline e reabrir: os dados continuam lá

**Validação manual (Matheus):**
1. Celular do atendente em **modo avião** por 30 minutos, operando 5 comandas
2. Voltar a rede e conferir no painel que tudo chegou uma única vez
3. Repetir com dois celulares ao mesmo tempo

---

### FASE 7 — Piloto em produção
**Objetivo:** uso real numa barraca, com segurança operacional.

**Entregas:**
- Deploy de produção documentado em `docs/deploy.md` (o Matheus executa)
- Backup diário do PostgreSQL (`pg_dump` + rotação de 14 dias) e **teste de restauração** documentado
- Health check externo (ex.: UptimeRobot) e log de erros
- **Barraca de demonstração** com dados fictícios para treino
- Materiais impressos:
  - guia de 1 página para o dono/atendente (check-in, fila, confirmar pagamento)
  - **plaquinha para o balcão**: *"Peça pelo celular: faça seu cadastro aqui e receba seu QR"*
- Botão "reportar problema" nos dois apps, registrando o contexto (tela, horário, estado da conexão)

**Validação (Matheus, em campo):**
1. Treinar o dono e os atendentes de 1 barraca na sexta-feira
2. Operar sábado e domingo, com o Matheus presente no primeiro dia
3. Anotar os problemas no mesmo dia, especialmente os momentos em que o cliente **desistiu** de usar o celular e o motivo
4. **Não aparecer no segundo fim de semana** e verificar se continuaram usando

#### 🚦 PORTÃO B — Decisão de produto
**Critérios:** a barraca usou no segundo fim de semana sem ser pedida, ≥ 50% dos clientes com check-in pediram pelo celular e nenhuma conta errada foi atribuída ao sistema.
- Se **sim** → Fase 8 e mais barracas.
- Se **não** → entrevistar o dono e 5 clientes ("por que não usou?") e priorizar as correções antes de qualquer funcionalidade nova.

---

### FASE 8 — (Condicional) Monetização e escala
Só inicia com `APROVADO PORTÃO B` + `INICIAR FASE 8`.
- Assinatura por temporada, onboarding self-service e trial
- Múltiplas barracas por dono
- Avaliar: autocadastro do cliente por QR fixo no balcão, confirmação automática do Pix via PSP (ADR com custos e responsabilidades) e pagamento com cartão no app

---

## B6. Cronograma estimado (dedicação parcial, noites e fins de semana)

| Fase | Estimativa | Data-alvo |
|---|---|---|
| 0 — Fundação | 2–3 dias | 04/out |
| 1 — Landing + teste de sinal | 4–6 dias | 12/out |
| Portão A (validação de campo + medição de sinal) | 2 fins de semana | 26/out |
| 2 — Contas e cadastros | 1,5 semana | 05/nov |
| 3 — Check-in, QR e pedido pelo cliente | 1,5 semana | 16/nov |
| 4 — Fila + notificações | 1 semana | 23/nov |
| 5 — Fechamento, Pix e extrato | 1–1,5 semana | 03/dez |
| 6 — Contingência offline | 1–2 semanas | antes ou depois do piloto (ver Portão A) |
| 7 — Piloto | 2 fins de semana | 06–14/dez |

**Plano B:** se houver atraso, o piloto mínimo é o das **Fases 2 a 5**. Nesse caso, comece numa praia ou horário com bom sinal e deixe o dono com o caderninho como reserva no primeiro fim de semana.

## B7. Riscos principais

| Risco | Impacto | Mitigação |
|---|---|---|
| Sinal ruim na praia lotada | O cliente não consegue pedir | Medição no Portão A, página leve, reenvio idempotente, modo atendente offline |
| Cliente não quer usar o celular | Baixa adoção | Check-in feito pela equipe (sem autocadastro), zero app para baixar, plaquinha no balcão, métrica de adoção no Portão B |
| Calote (cliente sai sem pagar) | Perda financeira | Check-in presencial, limite de saldo, modo "pagar por pedido", destaque de comandas com saldo |
| Trote ou pedido indevido | Entrega errada | Token não enumerável e exclusivo, rate limit, cancelamento pelo dono |
| Dono não ouve a notificação | Pedido esquecido | Push + som + alerta de atraso; instalação na tela inicial no onboarding |
| iPhone sem push | Dono com iPhone perde notificações | Onboarding obrigando a instalação da PWA; alerta de atraso como rede de segurança |
| Pix com payload errado | Pagamento para a chave ou o valor errado | Testes contra referência + pagamento real de R$ 0,01 no Portão da Fase 5 |
| Pix "já paguei" falso | Cliente diz que pagou sem pagar | Confirmação **sempre manual** pelo dono, com aviso para conferir no banco |
| Vazamento entre barracas ou de dados de clientes | Grave (financeiro + LGPD) | Testes de isolamento, WhatsApp opcional, anonimização em 90 dias |
| Mudança nas regras municipais | Funcionalidade vira problema legal | Sem "aluguel" e sem "consumação mínima"; revisar antes da Fase 8 |
| Sazonalidade | Pouco tempo de teste real | Cronograma com Plano B |

---

## B8. Primeira instrução para o agente

> Leia este documento inteiro. Confirme em até 10 linhas que entendeu o objetivo, o fluxo do cliente (check-in → QR → pedido → fila → fechamento), as regras dos portões e a stack. Liste qualquer dúvida bloqueante. Em seguida, **execute somente a FASE 0** e entregue o relatório da seção B4.
