# ADR 0013 — Receber pagamentos, bloquear e fechar a conta pelo quiosque

- **Status:** aceito
- **Data:** 2026-10-01

## Contexto

O checkpoint 5.1 deixou o cliente fechar a conta e escolher a forma de pagar. O 5.2 fecha o ciclo no quiosque: o dono precisa **saber na hora** quem vai pagar, **registrar** o que recebeu (inteiro, em partes ou em várias formas), **proteger-se de calote** e **encerrar** contas. O sistema continua sem tocar no dinheiro: o que se grava é o que a pessoa no quiosque conferiu.

## Decisão

**Aviso "indo pagar"**
- Dinheiro e cartão avisam no toque de "Fechar a conta"; no Pix, o aviso é o "já paguei" (o cliente escolher Pix ainda não avisa ninguém). Repetir o mesmo toque (o celular reenviou) **não avisa de novo**: só avisa quando a forma ou o troco mudam.
- Dois canais, os mesmos da Fase 4: **tempo real** (evento `conta` na conexão SSE da barraca e da comanda, só com ids, via `pg_notify` na transação) e **push** depois do commit.
- **O push não leva o nome do cliente** (como nos outros avisos, ADR 0011): "Guarda-sol 12 · indo pagar" e "R$ 87,50 · Dinheiro (troco para R$ 100,00)"; no Pix, "Guarda-sol 12 · Pix enviado" e "R$ 87,50 · confira no app do banco". Com o app aberto, o toast mostra o nome. Som próprio (três notas subindo), diferente do pedido novo e do atraso.

**Aba Pagamentos**
- Em cima: contas que tocaram em "fechar a conta", **por ordem de aviso** (quem ainda não avisou, no fim). Embaixo: **contas abertas ou bloqueadas com saldo**, as maiores primeiro, com o total em aberto (proteção contra calote). O número na aba conta as que esperam recebimento.
- Cada cartão mostra guarda-sol, cliente, saldo, forma, "avisou há N min" (horário do **servidor**, como na fila), o troco a devolver e, no Pix, "confira no app do banco antes de confirmar".

**Confirmar pagamento** (`POST /api/staff/comandas/{id}/pagamentos`)
- **Total, parcial ou dividido:** vale de R$ 0,01 até o que falta (valor maior que o saldo é recusado). Pode haver vários pagamentos, de formas diferentes. Dono **e atendentes** podem receber.
- **Idempotente:** o `id` do pagamento nasce no celular de quem confirma (como o do pedido). Reenviar o mesmo `id` devolve o pagamento que já existe (200), sem cobrar duas vezes; um `id` que já é de outra comanda dá 409. O diálogo guarda o `id` até fechar, então toque duplo ou queda de sinal nunca duplicam.
- **Dinheiro:** "o cliente entregou R$ …" (opcional; sem informar, é o valor exato) e o troco é calculado: entregue − valor. Nas outras formas, não há troco.
- **Trava da comanda** (`SELECT … FOR UPDATE`): confirmações simultâneas entram uma por vez e **nunca passam do saldo** (testado: 5 ao mesmo tempo sobre uma conta de R$ 50,00, com R$ 20,00 cada, registram só 2).
- Aceita conta `ABERTA`, `AGUARDANDO_PAGAMENTO` ou `BLOQUEADA` (pagar uma parte é o que **libera o limite de saldo** do 3.3, e o dono precisa poder receber de quem estava bloqueado). Recusa `FECHADA` e `CANCELADA`.
- **A conta se encerra sozinha** quando estava `AGUARDANDO_PAGAMENTO` e o saldo chega a zero. Conta `ABERTA` que zera continua aberta: o cliente pode pedir mais.

**"Pagar a cada pedido": o pagamento libera o pedido para a fila**
- Os pagamentos cobrem os pedidos **na ordem em que foram feitos**: um pedido que esperava pagamento vai para `RECEBIDO` quando o total pago cobre a soma dele e de todos os anteriores. Pagar R$ 10,00 de um pedido de R$ 24,00 não libera nada.
- O pedido liberado recebe `na_fila_em` (V12): a **fila, o tempo de espera e o alerta de atraso passam a contar desde a liberação**, e não desde que o cliente pediu. Sem isso, um pedido pago 10 minutos depois nasceria "atrasado".
- Reaproveita o aviso de pedido novo: publica o mesmo `PedidoCriado` e o `pg_notify` do pedido, então o push "Pedido nº X" e a fila ao vivo funcionam sem código novo (era a pendência anotada no 4.2).

**Bloquear e desbloquear**
- `ABERTA` ou `AGUARDANDO_PAGAMENTO` → `BLOQUEADA`: o cliente não pede mais (a mensagem já existia desde o 3.3) e o aviso de pagamento some. Desbloquear volta a `ABERTA`. Dono e atendentes podem; ambas as ações são "definir", não "alternar", então repetir é inofensivo.

**Encerrar e fechar sem receber**
- **Encerrar** (dono e atendentes): só com saldo zero (pagou tudo, ou nunca pediu). Com saldo pendente, recusa (409) e aponta o caminho.
- **Fechamento forçado** (só o **dono**; o atendente recebe 403): o cliente foi embora sem pagar. **Motivo obrigatório** (3 a 120 letras), gravado com o **valor que ficou sem pagar**, quem fechou e a hora (`fechamento_forcado_motivo`, `saldo_perdoado_centavos`, `fechada_por_usuario_id`, V12; as três colunas andam juntas por uma restrição do banco, e quem fechou é um dono **da mesma barraca**, por FK composta). É o dado que o relatório do dia (5.3) vai mostrar como "não recebido". Decidi que só o dono faz isso porque é abrir mão de dinheiro; se preferir que atendentes também possam, é trocar uma condição.
- Os pedidos que **nunca foram para a fila** (ainda aguardando pagamento) são **cancelados** ("Conta encerrada sem pagamento") **antes** de contar o saldo: o que o quiosque nunca entregou não é calote. Se, depois disso, não sobra nada a pagar, a conta se encerra normalmente, sem registro de calote.

**Isolamento**
- O id na URL é o da **comanda**, nunca o da barraca, e a busca é sempre por `(comanda, barraca de quem está logado)`: o dono de outra barraca recebe 404, igual a uma comanda que não existe, em todas as ações. A tabela `pagamento` já tinha FKs compostas (V11).

**O extrato expira**
- Depois de **7 dias da conta paga**, o link do QR dá o mesmo 404 de um token que nunca existiu (em todas as rotas do cliente, inclusive o tempo real e a rota de pedido). Contas abertas ou aguardando **não expiram**, por mais velhas que sejam. Cumpre o que o ADR 0007 prometia ("expira 7 dias depois do fechamento").

## Consequências

- **O "aviso de pagamento" depende de quem está com o app aberto ou com push ativo.** Sem nenhum dos dois, o dono só descobre ao abrir a aba Pagamentos. É o mesmo limite dos avisos de pedido.
- **Cobrir os pedidos "na ordem"** é uma regra simples e previsível, mas não deixa o dono pagar só o pedido do meio. Se algum dia precisar, o caminho é pagar por pedido (uma ação por pedido), não por comanda.
- **Cancelar item depois de pagar** deixa o pagamento maior que o saldo (a conta fica "a favor do cliente"). Não há devolução registrada no sistema: o dono resolve no quiosque. Fica para o piloto dizer se vale registrar.
- O app mostra a aba **Pagamentos** para todos da equipe; só o botão "Fechar sem receber" é exclusivo do dono.
