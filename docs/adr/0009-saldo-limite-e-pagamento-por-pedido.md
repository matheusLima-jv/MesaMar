# ADR 0009 — Saldo, limite de conta e "pagar a cada pedido"

- **Status:** aceito
- **Data:** 2026-10-01

## Contexto

O checkpoint 3.3 fecha a Fase 3: o cliente acompanha os pedidos e a conta, e as regras de calote definidas no plano (limite de saldo em aberto e modo de pagamento) passam a valer. Ainda não existe pagamento (Fase 5) nem fila do dono (Fase 4), então algumas regras precisam de uma versão provisória que não atrapalhe o que vem depois.

## Decisão

**Saldo**
- Saldo da comanda = soma dos itens **não cancelados** dos pedidos **não cancelados** (`preço congelado × quantidade`), incluindo os pedidos que aguardam pagamento. Quando os pagamentos existirem (Fase 5), o saldo passa a ser esse total menos o que foi pago. O cálculo fica numa consulta só (`ItemPedidoRepository.totalCentavos`), usada pela página, pela lista e pela regra do limite.
- O saldo aparece **sempre no cabeçalho** da página do cliente, em todas as telas, e é atualizado depois de cada pedido enviado.

**Limite de saldo em aberto**
- O pedido novo é recusado quando o saldo atual **já atingiu** o limite (`saldo >= limite`), com o código `limite-saldo` e a mensagem "Sua conta chegou a R$ X. Para pedir mais, pague uma parte com um atendente." É o que o painel já promete ao dono ("ao chegar nesse valor, o cliente precisa pagar antes de pedir mais").
- Consequência assumida: **o pedido que cruza o limite passa**, então a exposição máxima de uma comanda é o limite mais um pedido (no máximo 60 unidades). A alternativa, recusar o pedido que *ultrapassaria* o limite, deixaria o cliente sem conseguir pedir nada perto do teto, e o dono nem sempre quer isso.
- O limite vale **por comanda**. Reenviar um pedido que já existe continua dando certo mesmo com o limite atingido (a resposta perdida não vira erro).
- A página recebe o bloqueio na abertura (`bloqueio.codigo = limite-saldo`): mostra o aviso, tira os botões e esconde a barra do pedido. O cardápio é conferido de novo quando o cliente volta a ele depois de 10 s, para a conta que baixou (item cancelado pelo quiosque) liberar o pedido sem recarregar.

**Pedidos ao mesmo tempo não furam o limite**
- `PedidoService.criar` **trava a linha da comanda** (`SELECT … FOR UPDATE`) antes de conferir o status e o saldo, e só então grava. Dois pedidos da mesma comanda entram um por vez, e o segundo já enxerga o saldo do primeiro. Testado com 6 pedidos simultâneos contra um limite que cabe 1: um entra, cinco recebem `limite-saldo`.
- A trava vem **antes** da checagem de reenvio, então um reenvio simultâneo espera o original terminar e o encontra, em vez de bater na chave primária. A repetição pelo controller (ADR 0008) continua como rede de segurança.
- Ordem das travas: comanda e depois o contador de pedidos. Pedidos de comandas diferentes só disputam o contador (como antes), então não há ciclo e não há deadlock.
- O status da comanda também é lido já com a trava: um pedido não passa se a comanda foi bloqueada ou fechada um instante antes.

**Modo "pagar a cada pedido" (`POR_PEDIDO`)**
- O pedido nasce **`AGUARDANDO_PAGAMENTO`** (status novo, V8) em vez de `RECEBIDO`. Ele ganha número, conta no saldo e aparece em "Meus pedidos" como "Aguardando pagamento", com o valor a pagar. **Fica fora da fila.**
- Quem passa o pedido para `RECEBIDO` é a confirmação de pagamento do dono (Fase 5.2). **A fila e os avisos da Fase 4 devem listar só `RECEBIDO` e `EM_PREPARO`.** O evento `PedidoCriado` leva o status, para a notificação de "pedido novo" não disparar enquanto ele aguarda o pagamento.
- É fácil de desfazer (um status e uma regra): decidido aqui em vez de perguntar. Se a Fase 5 pedir outra coisa, troca-se o ponto em que o status muda.
- No cardápio e no carrinho, a página avisa "Neste quiosque você paga a cada pedido", e a confirmação diz "Para o pedido entrar na fila, pague R$ X a um atendente".

**Meus pedidos**
- `GET /api/c/{token}/pedidos` devolve o saldo e os pedidos da comanda do token (do mais novo para o mais antigo), com status e itens, **inclusive os cancelados**, que aparecem riscados e fora do total. Só os pedidos da própria comanda: o token é a credencial.
- A página consulta a cada **15 s** enquanto a aba "Meus pedidos" está aberta e visível, e pede de novo na hora ao voltar para ela. Sem sinal, mostra a última lista com um aviso. O tempo real (SSE) vem na Fase 4.
- A leitura usa o mesmo limite de uso por comanda (90 por minuto), sobra muito para 4 consultas por minuto.
- O service worker continua guardando só `GET /api/c/{token}` (sem subcaminho): a lista de pedidos nunca vem do cache.

## Consequências

### Complemento da revisão 111–115 (2026-10-03)

O painel agora explica o pedido que cruza o limite com exemplo financeiro, estimativa conservadora `limite + 60 × maior preço` e teto técnico de R$ 600.000 por pedido. Essa estimativa cobre pedidos do cliente com limite configurado; pedidos da equipe ignoram a regra e não têm teto de saldo por ela. Pagamentos parciais já são suportados: em conta aguardando pagamento, a equipe pode usar **Pagamentos → Continuar conta**, preservando valores e histórico, limpando intenção/aviso de pagamento e retomando apenas contas de hoje. Pausa e limite continuam valendo para o cliente. Os parágrafos abaixo registram o estado histórico da Fase 3.

- Mudar o limite ou o modo no painel vale para os próximos pedidos, e a comanda aberta sente na hora (a página lê a regra do banco a cada abertura).
- Sem a Fase 5, o saldo só sobe: o jeito de baixá-lo hoje é cancelar itens. Pagamento parcial entra com a confirmação de pagamento.
- A trava por comanda serializa só os pedidos da mesma comanda, que são raros. Se algum dia virar gargalo (não deve), o saldo pode ir para uma coluna na comanda.
