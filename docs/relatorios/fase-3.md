# Relatório — Fase 3: check-in, QR e pedido pelo cliente

- **Branch / PR:** `fase-3-cliente` → [PR #4](https://github.com/matheusLima-jv/MesaMar/pull/4) (rascunho até o último checkpoint)
- A Fase 3 está dividida em 3 checkpoints, aprovados um por vez:
  - [x] **3.1 — Check-in e QR pessoal** (aprovado; CI verde no PR #4)
  - [x] **3.2 — Página do cliente: cardápio, carrinho e pedido idempotente** (aprovado; CI verde no PR #4)
  - [ ] **3.3 — Meus pedidos, saldo, bloqueios e E2E multi-contexto** ← checkpoint atual
- Decisões de arquitetura: [ADR 0007](../adr/0007-checkin-e-token-da-comanda.md) (check-in e token) e [ADR 0008](../adr/0008-pedido-do-cliente.md) (pedido do cliente) e [ADR 0009](../adr/0009-saldo-limite-e-pagamento-por-pedido.md) (saldo, limite e pagar a cada pedido)

---

## Checkpoint 3.1 — Check-in e QR pessoal

- **Data:** 2026-09-30
- **CI (rodado localmente, com as mesmas etapas):** ✅ ktlint, **228 testes de backend** (+10), imagem Docker, smoke do compose e **10 E2E** no tamanho de celular

### 1. O que foi feito

- **Aba Clientes no app do quiosque**, que agora é a aba inicial:
  - botão grande **"+ Novo cliente"** → nome, guarda-sol (grade de números com um toque, ou "Sem guarda-sol"), WhatsApp opcional e o consentimento;
  - **QR em tela cheia**, com fundo branco e a largura da tela, junto com o nome, o guarda-sol e o número de chegada;
  - **"Enviar pelo WhatsApp"** (`wa.me`, sem custo), quando o cliente deu o número, com a mensagem e o link prontos;
  - **lista dos clientes com comanda aberta, por ordem de chegada**, com o botão **QR** para mostrar o código de novo.
- **Grade de guarda-sóis:** mostra só os ativos e marca com um ponto os que já têm cliente. Dois clientes podem dividir o mesmo guarda-sol, cada um com a sua comanda.
- **API `/api/staff`:** `GET /clientes`, `POST /clientes` (check-in) e `GET /guarda-sois`. A barraca vem sempre de quem está logado.
- **Banco (V6):** `cliente`, `comanda` e `contador_diario`, com FKs compostas que impedem misturar barracas.
- **`/c/{token}`** já abre a página do cliente. Ela ainda é o placeholder; o cardápio entra no 3.2.

### 2. Decisões

Ver o [ADR 0007](../adr/0007-checkin-e-token-da-comanda.md). Em resumo:
- token de 128 bits em claro, com índice único, para poder mostrar o QR de novo;
- número de chegada por barraca e por dia (fuso de São Paulo), com contador atômico no banco;
- check-in idempotente, com o id gerado no celular;
- guarda-sol opcional.

Também: **nome e consentimento são conferidos no celular antes de enviar** (resposta na hora, mesmo com sinal ruim), e o servidor confere tudo de novo.

### 3. Testes automatizados novos

- **Check-in (`CheckinApiTest`, 9 testes):**
  - cria cliente e comanda: nome normalizado, WhatsApp em E.164, consentimento registrado, quem fez o check-in, status `ABERTA`;
  - **token com 22 caracteres e 16 bytes (128 bits)**, e o link do QR na resposta;
  - o atendente também faz check-in; a lista sai por ordem de chegada; dois clientes no mesmo guarda-sol; a ocupação aparece na grade;
  - nome vazio ou longo demais, WhatsApp inválido e falta de consentimento: uma mensagem por campo, e nada é criado;
  - guarda-sol desativado ou de outra barraca é recusado;
  - **idempotência:** o mesmo check-in 3 vezes gera 1 comanda e não gasta número de chegada;
  - **20 check-ins simultâneos** recebem os números 1 a 20, sem repetir, e 20 tokens diferentes;
  - o número de chegada recomeça no dia seguinte, e o contador de pedidos é separado;
  - **isolamento:** a barraca B não vê os clientes de A, e reenviar o id de uma comanda de A dá 409 sem vazar o token;
  - sem login dá 401, e sem CSRF dá 403.
- **Rota:** `/c/{token}` serve a página do cliente.
- **E2E (celular):** a Carla entra no app e cai em Clientes. Ela faz o check-in da Ana no guarda-sol 12 e esquece o consentimento: o aviso aparece. Com o consentimento, gera o QR e confere o link do WhatsApp, volta à lista e mostra o QR de novo. Por fim, abre o link do QR em outro "celular". Tudo sem erro no console e sem rolagem lateral.

### 4. Como validar (na entrega final)

Itens 43 a 48 de [`docs/testar-localmente.md`](../testar-localmente.md).

---

## Checkpoint 3.2 — Página do cliente: cardápio, carrinho e pedido idempotente

- **Data:** 2026-09-30
- **CI:** ✅ verde no GitHub (PR #4): ktlint, **244 testes de backend**, imagem Docker, smoke do compose e **10 E2E** no tamanho de celular.

### 1. O que foi feito

- **Página do cliente** (`/c/{token}`), em TypeScript puro, ~48 KB no primeiro acesso (meta: 150 KB):
  - cabeçalho **"Olá, Ana · Guarda-sol 12"** com o nome da barraca;
  - **cardápio por categoria**, com o esgotado visível, riscado e sem botão, e o que saiu do cardápio escondido;
  - "Adicionar" e "− 2 +" (alvos de 48 px), com a barra amarela fixa **"Ver pedido · 3 itens · R$ 49,00"**;
  - **carrinho** com observação por item ("sem gelo"), total em destaque e "Enviar pedido";
  - **confirmação** "Pedido nº 1 enviado", com os itens e o total;
  - **sem sinal:** "Sem sinal. Tentaremos de novo. Se preferir, chame um atendente." O pedido fica guardado no celular e sai sozinho quando o sinal volta, **uma vez só**;
  - mensagens de bloqueio (pedidos pausados, conta fechando, comanda encerrada) e link para a política de privacidade;
  - service worker em `/c/sw.js`: sem sinal, o cliente ainda vê o último cardápio.
- **API `/api/c/{token}`**, sem sessão e sem cookies: `GET` (comanda e cardápio numa chamada) e `POST /pedidos`. Limite de uso por comanda e por IP.
- **Pedido idempotente:** o UUID nasce no celular e é a chave primária. O mesmo POST 3 vezes gera 1 pedido, e 8 ao mesmo tempo também.
- **Banco (V7):** `pedido` e `item_pedido`, com FKs compostas, nome e preço congelados no item e o número do pedido por barraca/dia (`contador_diario`).
- **Política de privacidade:** a seção 5 agora detalha os dados dos clientes (o que guardamos, para quê, quem vê, cookies, 90 dias).
- Evento `PedidoCriado` publicado para a fila da Fase 4.

### 2. Decisões

Ver o [ADR 0008](../adr/0008-pedido-do-cliente.md). As que merecem o seu olho:
- **Sem botão "cancelar envio"**, de propósito: se o pedido já chegou e a resposta se perdeu, cancelar só no celular criaria um pedido "fantasma" ou um duplicado.
- **Pedido tudo ou nada:** se um item acabou, o pedido inteiro é recusado, a página tira o item do carrinho e o cliente reenvia o resto.
- **Uma linha por produto no carrinho** (uma observação por produto).

### 3. O que ainda não está neste checkpoint (fica para o 3.3, como no plano)

- **Limite de saldo em aberto** e **modo "pagar a cada pedido"**: hoje uma barraca com essas regras ligadas recebe os pedidos sem aplicá-las.
- **"Meus pedidos"** com o status, e o **saldo sempre visível**. Depois de enviar, o cliente vê só a confirmação.
- A **anonimização após 90 dias** (a política já a promete) é o job da Fase 5.3.

### 4. Testes automatizados novos

- **API do cliente (`PedidoClienteApiTest`, 15 testes):**
  - a página abre com o cardápio, com o esgotado visível e sem produto fora do cardápio nem de outra barraca; `Cache-Control: no-store` e nenhum cookie;
  - token inexistente: 404 igual para qualquer valor, e **429** para quem insiste do mesmo IP;
  - pedido grava itens com **nome e preço congelados**, observação normalizada e número por barraca (cada barraca começa no 1);
  - **snapshot:** mudar preço e nome depois não mexe no pedido antigo, e reenviá-lo devolve o preço antigo;
  - **idempotência:** o mesmo pedido 3 vezes dá 201, 200, 200, com 1 pedido e **sem gastar número**; **8 envios simultâneos** dão um 201, o resto 200, um pedido, um número;
  - o id de um pedido não serve para outra comanda (409, sem vazar nada);
  - **produto esgotado ou fora do cardápio** recusa o pedido inteiro, com o nome na mensagem e a lista de ids, sem gastar número;
  - comanda `AGUARDANDO_PAGAMENTO`, `BLOQUEADA`, `FECHADA` e `CANCELADA`, e pedidos pausados: 409 com o código certo, e a página mostra o aviso; reabrir/despausar volta a aceitar;
  - reenviar um pedido que já existe funciona mesmo com a comanda fechada depois;
  - validação campo a campo (sem id, sem itens, quantidade 0 e 21, observação longa, 31 linhas, 64 unidades, produto sem id, JSON quebrado ou de tipo errado), sem criar nada; 20 unidades de um item vale;
  - limite de **20 pedidos por minuto** por comanda (429 com `Retry-After`), sem travar a leitura do cardápio;
  - **isolamento:** o token da barraca A não pede produto da B (409, sem vazar o nome), e o cardápio de A não mostra o de B;
  - **banco:** item com produto de outra barraca e pedido com comanda de outra barraca são rejeitados pelas FKs compostas.
- **Rota:** `/c/sw.js` serve o service worker como JavaScript.
- **E2E (celular):** a Ana abre o QR em outro "celular" e vê o cabeçalho certo e a água de coco **esgotada, sem botão**. A atendente devolve o item. A Ana pede 2 águas (sem gelo) e 1 pastel, vê **R$ 49,00** e recebe **"Pedido nº 1 enviado"**. Depois pede **sem sinal**: aparece o aviso, o sinal volta e sai o **nº 2**; um pedido seguinte é o **nº 3** (nada duplicado nem perdido). Sem erro no console e sem rolagem lateral.

### 5. Como validar (na entrega final)

Itens 49 a 58 de [`docs/testar-localmente.md`](../testar-localmente.md). Os dois que mais importam, do plano: **cronometrar do QR até o pedido confirmado e contar os toques** (item 53) e **o teste do modo avião** (itens 54 e 55).

---

## Checkpoint 3.3 — Meus pedidos, saldo, bloqueios e E2E multi-contexto

- **Data:** 2026-10-01
- **CI (rodado localmente, com as mesmas etapas):** ✅ ktlint, **252 testes de backend** (+8), imagem Docker, smoke do compose e **10 E2E** no tamanho de celular. O resultado no GitHub entra aqui depois do push.
- **Com este checkpoint a Fase 3 fica completa.**

### 1. O que foi feito

- **Saldo sempre visível:** "Sua conta: R$ 49,00" no cabeçalho, em todas as telas, atualizado a cada pedido enviado.
- **Aba "Meus pedidos":** do mais novo para o mais antigo, com número, hora, **status** (Aguardando pagamento, Recebido, Em preparo, Entregue, Cancelado), itens, observação e total. Atualiza sozinha a cada **15 s**; sem sinal, mostra a última lista com um aviso. Item cancelado aparece riscado e fora do total.
- **Limite de conta aberta:** com o limite atingido, o pedido novo é recusado ("Sua conta chegou a R$ 90,00. Para pedir mais, pague uma parte com um atendente."), e a página mostra o aviso e tira os botões. Vale por comanda e **não é furado por pedidos ao mesmo tempo**.
- **Modo "pagar a cada pedido":** o pedido nasce **"Aguardando pagamento"**, fora da fila, e a página diz quanto pagar e a quem. A confirmação de pagamento (Fase 5) é que o põe na fila.
- **Bloqueios com mensagem clara**, todos do servidor: pedidos pausados, conta fechando, comanda bloqueada, encerrada ou cancelada, limite de saldo.
- **E2E multi-contexto completo:** o dono configura a barraca e o limite, a atendente faz o check-in em outro celular, a Ana abre o QR em um terceiro e faz os pedidos (com e sem sinal), e a lista é conferida em "Meus pedidos".

### 2. Decisões

Ver o [ADR 0009](../adr/0009-saldo-limite-e-pagamento-por-pedido.md). As que merecem o seu olho:
- **"Limite atingido" é `saldo >= limite`, e o pedido que cruza o limite passa.** A exposição máxima de uma comanda é o limite mais um pedido. O contrário (recusar o que ultrapassaria) deixaria o cliente sem poder pedir nada perto do teto.
- **Pedido "aguardando pagamento" conta no saldo**, mesmo fora da fila, porque a conta existe desde o pedido.
- **A Fase 4 deve listar na fila só `RECEBIDO` e `EM_PREPARO`**, e não avisar o dono de pedido novo enquanto ele aguarda o pagamento. O evento `PedidoCriado` já leva o status.

### 3. Um erro que o teste pegou

Na V7 o `status` do pedido era `VARCHAR(12)`, e `AGUARDANDO_PAGAMENTO` tem 20 caracteres: o primeiro pedido no modo "a cada pedido" dava erro 500. O teste do modo pegou na hora, e a V8 alarga a coluna junto com a nova regra do status.

### 4. Ainda não existe (de propósito)

- A fila e os avisos do dono (Fase 4) e a confirmação de pagamento (Fase 5). Por isso, **hoje o status de um pedido só muda por SQL** (o roteiro mostra como), e a conta só baixa cancelando item.

### 5. Testes automatizados novos

- **API da conta (`ContaDoClienteApiTest`, 8 testes):**
  - Meus pedidos mostra só os da própria comanda (a Ana não vê o do Beto nem o de outra barraca), do mais novo para o mais antigo, com o saldo; token inexistente dá 404; sem pedidos, lista vazia e saldo zero;
  - o saldo ignora item cancelado e pedido cancelado, e a abertura da página mostra o mesmo valor;
  - limite atingido recusa pedido novo (409 `limite-saldo`, com o valor na mensagem), o reenvio de pedido existente continua valendo, e cancelar item libera de novo; sem limite, sem bloqueio;
  - o limite vale por comanda;
  - **6 pedidos simultâneos contra um limite que cabe 1: entra um, cinco recebem `limite-saldo`**;
  - modo "a cada pedido": status `AGUARDANDO_PAGAMENTO`, aparece na lista e no saldo, o reenvio devolve o mesmo, o pedido ganha número; ao voltar para "no final", o próximo nasce `RECEBIDO`;
  - por padrão, o pedido entra direto como `RECEBIDO`.
- **Apoio de testes:** `ClienteTestSupport` reúne a montagem de barraca, comanda e pedido, e o `PedidoClienteApiTest` passou a usá-lo.
- **E2E (celular):** o dono define o limite de R$ 90 no painel. A Ana vê "Sua conta: R$ 0,00", pede (R$ 49, R$ 25 e R$ 25, este último sem sinal) e a conta vai a R$ 99. O cardápio mostra o aviso do limite e **sem botões**; "Meus pedidos" lista os pedidos 3, 2 e 1 com o status "Recebido", as observações e o total da conta.

### 6. Como validar (na entrega final)

Itens 59 a 66 de [`docs/testar-localmente.md`](../testar-localmente.md). Os mais importantes: **o limite atingido (62 e 63)**, o **modo "a cada pedido" (64 e 65)** e **a página reagindo sozinha a uma mudança no banco (60 e 61)**.
