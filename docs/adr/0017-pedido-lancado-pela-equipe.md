# ADR 0017 — Pedido lançado pela equipe e token do QR gerado no aparelho

- **Status:** aceito (aguardando a aprovação do 6.1)
- **Data:** 2026-10-02

## Contexto

A Fase 6 faz a barraca funcionar quando o cliente não tem celular, bateria ou sinal. O primeiro passo é a equipe poder **lançar o pedido pela comanda**, e o check-in poder acontecer sem o servidor (o aparelho já mostra o QR). O 6.2 coloca isso numa fila offline; aqui ficam as regras do servidor, que o 6.2 vai herdar.

## Decisões

**1. `POST /api/staff/comandas/{id}/pedidos`, com `origem = ATENDENTE`.**
- O `id` da URL é o da **comanda**; comanda de outra barraca dá 404, igual a uma que não existe. O `id` do pedido nasce no aparelho e torna o envio idempotente (a mesma regra do pedido do cliente: reenviar devolve 200 e o mesmo pedido).
- Reaproveita o `PedidoService.criar` (mesma validação, mesmo snapshot de nome e preço, mesmo número de pedido, mesma trava da comanda, mesmos avisos em tempo real e push). O pedido entra na mesma fila.
- V16: o pedido guarda **quem lançou** (`criado_por_usuario_id` ou `criado_por_atendente_id`, nunca os dois). Uma constraint do banco exige autor quando a origem é `ATENDENTE` e proíbe quando é `CLIENTE`.

**2. Pausa e limite de saldo não barram a equipe.** *(fácil de desfazer: um `&& !pelaEquipe` em `PedidoService.bloqueio`)*
- "Pausar pedidos" fala com o **cliente** (o quiosque está sem capacidade), e o limite de saldo diz "pague uma parte com um atendente": quem está no balcão é esse atendente e decide na hora.
- Principal razão: um pedido lançado **sem sinal** só chega ao servidor minutos ou horas depois. Recusá-lo então, com o cliente já atendido, seria pior que aceitá-lo. Pela mesma razão, o 6.2 não vai recusar por limite no reenvio.
- Continuam valendo para a equipe: barraca inativa, comanda bloqueada, aguardando pagamento, fechada, cancelada ou **de outro dia**, e produto esgotado ou fora do cardápio.

**3. O token do QR pode vir do aparelho.**
- `CheckinRequest.token` (opcional): 22 letras Base64 URL-safe (128 bits). O servidor o adota, e o QR já funciona assim que o check-in chega. Sem o campo, o servidor gera o token como antes.
- Não dá para provar a aleatoriedade de um token que veio de fora. O servidor recusa o que claramente não é: tamanho errado, letras fora do alfabeto, bits finais sobrando, menos de 8 símbolos diferentes. **Quem tem login de equipe poderia escolher um token fraco para a comanda de um cliente da própria barraca**; isso não dá acesso a nenhuma outra barraca, e o risco é aceito (a equipe já vê o QR e os pedidos de todas as comandas da barraca).
- Token já usado por **outra** comanda: 409, e o aparelho tenta com outro. Repetir o check-in com o **mesmo id** continua devolvendo a mesma comanda (200), com ou sem token.

## O que ainda vem (6.2 e 6.3)

- Fila de ações offline (IndexedDB), reenvio em ordem com espera crescente, indicador 🟢/🟠/🔴, check-in offline.
- Regras de conflito entre dois aparelhos (o primeiro estado final vence) e a hora da última sincronização de cada aparelho, visível para o dono.

## Consequências

### Complemento da revisão 111–115 (2026-10-03)

A tela de lançamento e a de reenvio exibem aviso permanente sobre a autorização da equipe. Quando pausa ou limite já atingido forem ignorados, a mesma transação grava `EXCECAO` em `historico_acao`, com autor, horário, saldo anterior, limite e os dois indicadores separadamente. Não há migração nova: utiliza o histórico V18, com retenção de 90 dias. A confirmação do lançamento informa quais exceções foram usadas; o reenvio lê o mesmo registro, sem duplicar. Pedidos sem uso efetivo da exceção continuam com origem/autor e não recebem `EXCECAO`. As condições são as do servidor no recebimento, inclusive ao sincronizar offline. Consulta restrita documentada em [preparação operacional](../preparacao-piloto.md); ainda não há tela de histórico para o dono. Os parágrafos abaixo preservam o estado original da Fase 6.1.

- O `RelatorioTest` precisou gravar o autor ao marcar um pedido como `ATENDENTE` por SQL: a constraint nova é o que garante que a origem nunca fique sem autor.
- A fila e o relatório ainda **não mostram** a origem do pedido na tela. Se o dono quiser separar "pedido do cliente" de "lançado pela equipe", é uma mudança pequena de tela.
