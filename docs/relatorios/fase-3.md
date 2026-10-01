# Relatório — Fase 3: check-in, QR e pedido pelo cliente

- **Branch / PR:** `fase-3-cliente` → PR da Fase 3 (rascunho até o último checkpoint)
- A Fase 3 está dividida em 3 checkpoints, aprovados um por vez:
  - [ ] **3.1 — Check-in e QR pessoal** ← checkpoint atual
  - [ ] **3.2 — Página do cliente: cardápio, carrinho e pedido idempotente**
  - [ ] **3.3 — Meus pedidos, saldo, bloqueios e E2E multi-contexto**
- Decisões de arquitetura: [ADR 0007](../adr/0007-checkin-e-token-da-comanda.md)

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
