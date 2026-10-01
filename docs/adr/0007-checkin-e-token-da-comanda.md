# ADR 0007 — Check-in do cliente e token da comanda

- **Status:** aceito
- **Data:** 2026-09-30

## Contexto

O checkpoint 3.1 cria o check-in no app do quiosque: a equipe cadastra o cliente (nome, guarda-sol, WhatsApp opcional e consentimento) e mostra um **QR pessoal**. O QR leva ao link `/c/{token}`, e esse token é a **única credencial do cliente**: quem tem o link vê a comanda e, a partir do 3.2, faz pedidos nela.

## Decisão

- **Token com 128 bits aleatórios** (`SecureRandom`, 16 bytes em Base64 URL-safe = 22 caracteres). Não dá para adivinhar nem enumerar. Com 22 caracteres, a URL inteira cabe num QR versão 4 com correção "M", que é pouco denso e fácil de ler no sol.
- **O token fica em claro no banco, com índice único** (e não só o hash, como fazemos com convite e troca de senha). Motivo: a equipe precisa **mostrar o QR de novo** ("o cliente fechou a aba", "o celular dele apagou"), e com só o hash isso seria impossível. O custo aparece num vazamento do banco: o token permitiria ver a comanda e pedir em nome do cliente **enquanto ela estiver aberta**. Esse risco é limitado, porque o token deixa de aceitar pedidos quando a comanda sai de `ABERTA` e expira 7 dias depois do fechamento (Fase 5). Além disso, quem lê o banco já vê os pedidos de qualquer jeito.
- **O link só aparece para a equipe da própria barraca** (`/api/staff/clientes`), nunca em listagens de outras barracas. A política de referrer `strict-origin-when-cross-origin` impede que o token vaze para outros sites pela URL.
- **Número de chegada sequencial por barraca e por dia**, no fuso `America/Sao_Paulo`, com um contador no banco (`contador_diario`, `INSERT … ON CONFLICT DO UPDATE … RETURNING`). O incremento trava a linha até o fim da transação, então dois check-ins simultâneos nunca pegam o mesmo número, e um check-in que falha devolve o número. A mesma tabela vai numerar os pedidos no 3.2.
- **Check-in idempotente.** O `id` da comanda nasce no celular, e reenviar o mesmo check-in (sinal ruim, toque duplo) devolve a mesma comanda, sem gastar outro número de chegada. O app tenta de novo sozinho mais 2 vezes quando a rede falha. Já prepara o terreno para o modo offline da Fase 6.
- **Guarda-sol opcional** (o modelo já previa `guardaSolId?`): quem está no balcão ou em pé também abre comanda. **Mais de um cliente pode usar o mesmo guarda-sol** (família, grupo), cada um com a sua comanda. O seletor marca com um ponto os guarda-sóis que já têm cliente.
- **Integridade no banco:** FKs compostas `(cliente_id, barraca_id)` e `(guarda_sol_id, barraca_id)` impedem uma comanda de apontar para o cliente ou o guarda-sol de outra barraca, mesmo com um bug no código. A comanda guarda **quem fez o check-in** (dono ou atendente, exatamente um dos dois).
- **QR gerado no navegador** com a biblioteca `qrcode-generator` (MIT, sem dependências, mantida há mais de 10 anos), desenhado como SVG pelo DOM, sem `innerHTML` nem mudança na CSP. O JS do app foi de 4,8 KB para 14 KB com gzip. Para um app instalado e com os arquivos no cache do service worker, o custo é aceitável.
- **Clientes virou a aba inicial do app**, porque o check-in é a ação mais comum do dia.

## Consequências

- Se um dia for preciso guardar o token só como hash, a equipe perde o "mostrar o QR de novo" (a alternativa seria gerar um token novo e invalidar o antigo).
- O `/c/{token}` já abre a página do cliente (ainda o placeholder). O cardápio e o pedido chegam no 3.2.
