# ADR 0008 — Pedido do cliente: API por token, pedido idempotente e reenvio

- **Status:** aceito
- **Data:** 2026-09-30

## Contexto

No checkpoint 3.2 o cliente abre o link do QR (`/c/{token}`) e faz pedidos pelo celular. O sinal nas praias é o risco principal: o pedido precisa sair mesmo com 4G ruim, **e nunca pode virar dois**. O cliente não tem conta nem senha; o token do QR (ADR 0007) é a única credencial.

## Decisão

**API sem sessão, autenticada só pelo token (`/api/c/{token}`)**
- Cadeia de segurança própria (`clienteChain`): sem sessão, sem cookies e **sem CSRF**. O CSRF protege quem tem cookie de sessão, e aqui não há; quem não conhece o token não consegue agir em nome do cliente de qualquer forma.
- A comanda, e por ela a barraca, vem **sempre do token**. Nenhum endpoint recebe id de barraca ou de comanda. Respostas com `Cache-Control: no-store`.
- Token desconhecido dá **404 igual para qualquer valor**. Quem insiste é freado por IP (30 tokens inválidos em 10 minutos, depois 429).
- Limites por comanda: 90 leituras e 20 pedidos por minuto. Cobrem loops de reenvio e abuso sem atrapalhar o uso normal (o reenvio espera 2 s, 4 s… até 15 s).
- Dois endpoints: `GET /api/c/{token}` (quem é, onde está, se dá para pedir e o cardápio, **numa chamada só**, para gastar menos sinal) e `POST /api/c/{token}/pedidos`.

**Pedido idempotente**
- O `id` do pedido é um UUID gerado no celular e é a **chave primária** da tabela. Enviar o mesmo pedido de novo devolve o que já existe (200, em vez de 201), **mesmo que a comanda tenha mudado desde então**: a resposta perdida no caminho não vira erro.
- Dois envios ao mesmo tempo: o segundo bate na chave primária, a transação dele desfaz tudo (inclusive o número do pedido, que sai do contador na mesma transação) e o controller repete a chamada, que então encontra o primeiro. Testado com 8 envios simultâneos: um 201, sete 200, um pedido gravado, um número gasto.
- Um id que já pertence a **outra comanda** dá 409, sem revelar nada do pedido alheio.

**Reenvio automático no celular**
- Ao tocar "Enviar pedido", o pedido ganha o seu id e é **guardado no navegador** (`localStorage`, por comanda) antes da primeira tentativa. Se a aba fechar ou a página recarregar, o envio continua de onde parou quando o cliente reabre o link.
- Sem sinal, erro 5xx ou limite de uso (429): espera (2 s, 4 s… até 15 s, ou até o evento `online`) e repete **o mesmo id**. Recusa de verdade (400, 404, 409) encerra o envio e volta ao carrinho com o motivo.
- **Não há botão "cancelar envio".** Se o pedido já chegou ao servidor mas a resposta se perdeu, cancelar só no celular deixaria um pedido que o cliente acha que não existe, e um novo pedido o duplicaria. Quem não quiser esperar chama um atendente.
- O texto para o cliente é o definido no plano: "Sem sinal. Tentaremos de novo. Se preferir, chame um atendente."

**Validação no servidor (tudo ou nada)**
- Todo produto precisa ser **da barraca da comanda**, estar no cardápio e não estar esgotado. Um id de produto de outra barraca é tratado como inexistente, e o nome dele **não vaza** na mensagem. Se um item falha, **o pedido inteiro é recusado** (409 `indisponivel`, com a lista dos produtos) e a página tira do carrinho o que acabou e atualiza o cardápio.
- Limites: 30 linhas, 1 a 20 unidades por item, 60 unidades por pedido, observação de até 80 letras. O banco repete os limites de quantidade e de preço.
- **Snapshot:** o item guarda nome e preço da hora do pedido. Mudar o cardápio depois não mexe na conta, e reenviar um pedido antigo devolve o preço antigo.
- **Integridade no banco (V7):** FKs compostas `(comanda_id, barraca_id)` e `(produto_id, barraca_id)` impedem um pedido de apontar para a comanda ou o produto de outra barraca, mesmo com um bug no código. O número do pedido vem de `contador_diario` (tipo `PEDIDO`, por barraca e por dia) e o horário (`criado_em`) é o do **servidor**, que define a ordem da fila na Fase 4.

**Bloqueios já aplicados no 3.2:** comanda fora de `ABERTA` (conta fechando, bloqueada, fechada, cancelada), `pedidosPausados` da barraca e barraca inativa. A mensagem vem pronta do servidor (uma fonte só para a página e para o erro do pedido).

**Eventos:** o serviço publica `PedidoCriado` (Spring `ApplicationEvent`) ao gravar. A fila e as notificações da Fase 4 vão escutar.

**Página do cliente**
- TypeScript puro, sem framework, com o mesmo `h()` do app do quiosque (nada de `innerHTML`). Primeiro acesso de **~48 KB** comprimidos (JS 4,7 KB, CSS 1,8 KB, fonte 41 KB), contra a meta de 150 KB.
- Telas por `#hash` (cardápio, `#carrinho`, `#pedido`), para o botão "voltar" do celular fazer o esperado.
- **Uma linha por produto no carrinho**, com uma observação só (o servidor aceita várias linhas do mesmo produto, a tela não precisa).
- **Service worker em `/c/sw.js`** (escopo `/c/`, o mesmo do link do QR; um SW só controla a pasta dele): rede primeiro para a página e para `GET /api/c/{token}`, e sem sinal mostra o último cardápio visto. O `POST` nunca passa pelo cache. Antes de enviar, o servidor confere de novo, então um cardápio velho nunca vira pedido errado.
- `noindex` na página, `Disallow: /c/` no `robots.txt` e `Referrer-Policy` restrita, para o token não vazar.

## Fica para o 3.3

Limite de saldo em aberto, modo "pagar a cada pedido", "Meus pedidos" com status e o saldo sempre visível. **Enquanto o 3.3 não entra, uma barraca no modo "a cada pedido" ou com limite de saldo recebe os pedidos sem essas regras.** Por isso a Fase 3 só deve ir para a `main` completa.

## Consequências

- O reenvio indefinido depende de o cliente reabrir a página; sem push nem SMS, não dá para "puxar" o cliente de volta. O atendente é a rede de segurança.
- O contador de pedidos só avança quando o pedido é gravado (ele desfaz junto), então os números do dia saem sem buracos.
- Se um dia a barraca precisar de mais de um servidor, os limites de uso (em memória) precisam ir para o banco (ver ADR 0003).
