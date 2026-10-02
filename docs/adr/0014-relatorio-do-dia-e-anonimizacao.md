# ADR 0014 — Relatório do dia e anonimização LGPD

- **Status:** aceito
- **Data:** 2026-10-01

## Contexto

O dono precisa fechar o dia: quanto entrou, quanto vendeu, o que mais saiu, o que foi cancelado e **quem ainda deve**. E a política de privacidade promete que nome e WhatsApp somem depois de 90 dias, mantendo os valores. O checkpoint 5.3 entrega as duas coisas.

## Decisão

**O relatório é calculado na hora, em SQL, das tabelas de pedido e pagamento.** Não existe total guardado em lugar nenhum, então ele não pode ficar fora de sincronia com o que o quiosque viu. O "dia" é o **dia de operação no fuso da barraca** (America/Sao_Paulo): o pagamento é do dia em que foi confirmado, no horário de Brasília (testado com dois pagamentos que são o mesmo dia em UTC e dias diferentes em Brasília), e o pedido é do dia em que nasceu (`pedido.dia`).

**O que o relatório mostra** (página em `/painel/relatorio` e o mesmo conteúdo em CSV):
- **Recebido no dia**, total e **por forma** (Pix, dinheiro, cartão, com a quantidade de pagamentos). É o valor que abateu contas: no dinheiro, o troco não entra.
- **Vendido no dia**: valor dos itens não cancelados dos pedidos do dia, **tenha sido pago ou não**.
- **Clientes** (comandas abertas no dia); **pedidos feitos pelo cliente e lançados pelo atendente** (a origem do pedido, que a Fase 6 vai preencher); cancelados; entregues.
- **Tempo médio do pedido à entrega**: da entrada na fila (`na_fila_em`, ou a criação) até a entrega, só dos entregues do dia.
- **Mais vendidos** (10 primeiros, por quantidade) e **cancelamentos por motivo** (Esgotou, Cliente desistiu, Outro: unidades e valor). O motivo "Outro: detalhe" é agrupado em "Outro".
- **Contas em aberto** e **não recebido**.

**A conta fecha, e isso é testado:** `vendido = recebido + em aberto + não recebido`. No teste do cenário gerado (quatro clientes: uma conta paga em duas formas, uma em aberto, uma com item cancelado e fechada sem receber, uma lançada pelo atendente), os R$ 149,00 vendidos são R$ 49,00 recebidos + R$ 75,00 em aberto + R$ 25,00 não recebidos. A igualdade só vale com o dia inteiro fechado; em outro dia, pagamentos e vendas se separam.

**"Contas em aberto" é de agora, não do dia escolhido.** Quem deve é uma foto do momento (a mesma lista da aba Pagamentos), não faz sentido "devia em 3 de outubro". A página diz isso. **"Não recebido"** é do dia em que o dono fechou a conta sem receber (ADR 0013).

**CSV** no mesmo padrão do `/admin` (ponto e vírgula, UTF-8 com BOM para o Excel abrir certo, vírgula decimal, e proteção contra célula que comece com `=`, `+`, `-` ou `@`): colunas Seção, Item, Quantidade e Valor (R$), uma seção por bloco da página. O nome do arquivo leva a data.

**Acesso e isolamento:** só o dono, no painel, como o resto. A barraca vem de quem está logado; a data é o único parâmetro (`?dia=AAAA-MM-DD`; inválida ou futura volta para hoje, a inválida com um aviso). Sem login, vai para o login. Testado: o dono de uma barraca não vê nada de outra, nem na página nem no CSV.

**Anonimização LGPD**
- Um `UPDATE` só, todo dia às **3h30 (Brasília)**: clientes com mais de **90 dias** (`criado_em`) ganham `nome = 'Cliente'`, `whatsapp_e164 = NULL` e `anonimizado_em = agora`. Pedidos, itens, pagamentos e comandas **não são tocados**, então o relatório do dia dá o mesmo número antes e depois (testado).
- **Com mais de um servidor não precisa de trava de tarefa agendada:** o primeiro anonimiza, os outros não encontram mais nada (`anonimizado_em IS NULL`), e rodar de novo não muda a data.
- **A regra é seca (decisão do dono do projeto, na aprovação do 5.3, em 2026-10-02).** Passou de 90 dias, o cliente é anonimizado, **mesmo com a comanda aberta, aguardando pagamento ou bloqueada**. A primeira versão deixava esses clientes de fora para o dono não perder o nome de quem deve; isso foi removido. Consequência: depois de 90 dias o dono vê "Cliente" nas contas em aberto (o valor devido continua lá). Quem deve precisa ser cobrado antes disso.
- Os dias são configuráveis (`mesamar.anonimizacao.dias`, padrão 90) e o horário também (`mesamar.anonimizacao.cron`).

## Consequências

- **Texto livre fica.** A observação de um item ("sem gelo") e o motivo de um cancelamento ("Outro: …") não são anonimizados: o produto não pede dado pessoal ali, mas o cliente poderia escrever um. Se virar problema, o caminho é limpar a observação junto com o nome.
- **O relatório recalcula a cada abertura.** Com dezenas de barracas e o volume de uma praia, são consultas por barraca e por dia, com os índices que já existem; não há cache. Se o piloto mostrar lentidão, o caminho é uma tabela de fechamento do dia.
- **O dia do pedido é o dia em que nasceu.** Um pedido feito às 23h50 e entregue às 00h10 conta no dia anterior; um pagamento confirmado depois da meia-noite conta no dia seguinte. É o que o dono vê no caixa, mas por isso "vendido" e "recebido" podem diferir num único dia.
