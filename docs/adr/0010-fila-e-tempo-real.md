# ADR 0010 — Fila do quiosque e tempo real (SSE com LISTEN/NOTIFY)

- **Status:** aceito
- **Data:** 2026-10-01

## Contexto

O checkpoint 4.1 entrega a fila do dono e o tempo real: o quiosque vê o pedido chegar sem recarregar, atende na ordem certa, e o cliente vê o status mudar na própria tela. O risco é o mesmo de sempre, o sinal ruim, mais um novo: **dois atendentes tocando no mesmo pedido** ao mesmo tempo.

## Decisão

**A fila**
- Mostra os pedidos `RECEBIDO` e `EM_PREPARO`, **o mais antigo em cima, pelo horário do servidor** (`criado_em`); no empate, o número do pedido desempata. Um pedido que ficou preso no celular do cliente e chegou tarde cai no fim: o relógio do celular nunca conta. O que aguarda pagamento (`AGUARDANDO_PAGAMENTO`, ADR 0009), o entregue e o cancelado ficam de fora (índice parcial no banco para a consulta ser barata).
- A **espera** é calculada contra o horário do servidor: a resposta da fila traz `agora`, e o app guarda a diferença para o relógio do celular. Cor por tempo de alerta da barraca: **verde** até a metade, **amarelo** até o tempo, **vermelho** depois, e escrito "Atrasado" (para quem não distingue cor).

**Status só para frente, com travas**
- `RECEBIDO → EM_PREPARO → ENTREGUE`, com atalho `RECEBIDO → ENTREGUE` (dá para pular o preparo). **Entregue e cancelado são finais.** Repetir a mesma ação é inofensivo (200), o que prepara o terreno para o modo offline da Fase 6.
- Cada ação **trava a linha do pedido** (`SELECT … FOR UPDATE`): dois atendentes ao mesmo tempo entram um por vez, e quem perde a corrida recebe 409 com "Esse pedido já foi entregue." Testado com 6 ações simultâneas contra o mesmo pedido, 5 vezes: nunca fica entregue e cancelado.
- O pedido guarda quando entrou em preparo, foi entregue ou cancelado, e **quem** fez a última ação (dono ou atendente, nunca os dois). Migração V9.

**Cancelar com motivo obrigatório**
- Motivos: **Esgotou**, **Cliente desistiu** e **Outro** (com detalhe livre, até 80 letras no total). Sem motivo, 400.
- Dá para cancelar **um item** ("acabou o pastel") ou o **pedido inteiro**. Cancelar a última linha cancela o pedido todo. O item cancelado sai do total e do saldo (ADR 0009), e o limite de conta pode liberar sozinho.
- O texto do motivo vai para o cliente, que vê um aviso vermelho com ele ("O quiosque cancelou 1× Pastel do pedido nº 1: Esgotou.") e o item riscado em "Meus pedidos". O aviso aparece uma vez só: os ids lidos ficam no navegador do cliente ("Entendi"). Cancelamentos que aconteceram com a página fechada também viram aviso.

**Tempo real: SSE alimentado por LISTEN/NOTIFY do PostgreSQL**
- Quem muda um pedido chama `pg_notify` **dentro da própria transação**. O PostgreSQL só entrega o aviso no **commit**: ninguém é avisado de uma mudança que foi desfeita. Com mais de um servidor, todos recebem (é o que a decisão de usar o banco já previa, ver ADR 0003).
- Uma conexão **própria** (fora do pool, porque fica presa para sempre) faz `LISTEN` e repassa cada aviso ao registro de assinantes. Se cair, tenta de novo (1 s, 2 s… até 30 s) e, ao voltar, manda todo mundo **buscar o estado de novo** (`ressincronizar`), porque avisos podem ter se perdido.
- O aviso leva **só ids**; quem recebe busca o estado pela API, que confere a barraca. Assim a tela sempre mostra o que o servidor tem, e um aviso perdido é corrigido pela próxima busca.
- Assinantes por **barraca** (app do quiosque, `/api/staff/eventos`, exige login) e por **comanda** (página do cliente, `/api/c/{token}/eventos`, o token é a credencial). Um evento de uma barraca só chega a assinantes dela, e um evento de uma comanda só à dela (testado).
- **Batimento a cada 25 s** (um comentário SSE): mantém a conexão viva atrás do Nginx (`proxy_read_timeout` de 60 s) e da Cloudflare, e faz o servidor notar o celular que sumiu. A conexão acaba depois de 30 minutos e o navegador reconecta sozinho. Limites: 40 conexões por barraca e 4 por comanda (a mais antiga cai). O cabeçalho `X-Accel-Buffering: no` impede o Nginx de segurar os avisos.
- Cada envio roda numa thread virtual: um celular lento não atrasa a entrega para os outros.
- **Cliente:** token inexistente responde 404 e uso demais responde 429, **sem corpo**, para o navegador parar de insistir. O polling de 15 s da lista de pedidos só roda quando o tempo real está fora do ar (é o plano B).
- **Quiosque:** se a conexão cair, a tela mostra "Reconectando…", e a fila busca a cada 15 s até voltar. Ao reconectar, busca de novo na hora.

**No app aberto:** pedido novo toca dois bips e vibra (o navegador só libera som depois de um toque na tela, e o login já conta), o cartão ganha o selo "Novo" por 20 s, e a aba mostra quantos pedidos esperam em qualquer tela. O som tem um botão para desligar, que o app lembra. **O push com o app fechado é o 4.2.**

## Consequências

- Uma conexão SSE já aberta **não é revalidada**: um atendente desativado continua recebendo avisos (só ids, nada de nome nem valor) até a conexão acabar, no máximo 30 minutos. Toda busca e toda ação continuam revalidadas a cada chamada, então ele não vê nem muda nada.
- O `NOTIFY` limita a carga a 8 KB, e usamos 120 bytes. Se um dia o volume de avisos incomodar o banco, o canal pode ser quebrado por barraca.
- O registro de assinantes é em memória, por servidor. Com dois servidores, cada um serve os seus clientes, e o `NOTIFY` leva o aviso a ambos.
