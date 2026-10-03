# Relatório — Fase 6: modo atendente offline

- **Branch:** `fase-6-offline` (criada a partir da `main`, que já tem as Fases 0 a 5 e 7)
- A Fase 6 está dividida em checkpoints, aprovados um por vez:
  - [ ] **6.1 — Lançar pedido para cliente (online) e token do QR gerado no aparelho** ← checkpoint atual
  - [ ] 6.2 — Fila de ações offline (IndexedDB), reenvio em ordem, indicador de conexão, check-in offline
  - [ ] 6.3 — Conflito entre aparelhos, última sincronização visível para o dono, E2E em modo offline

---

## Checkpoint 6.1 — Lançar pedido para cliente

- **Data:** 2026-10-02
- **CI:** ✅ verde no PR #7 (412 testes de backend, 11 E2E, incluindo o passo novo 14b).

### 1. O que foi feito

- **Backend:** `POST /api/staff/comandas/{id}/pedidos` (`StaffPedidosController`), que reaproveita o `PedidoService.criar` com um autor da equipe. Migração **V16**: o pedido guarda quem lançou, com constraint (origem `ATENDENTE` exige autor; `CLIENTE` não pode ter).
- **Check-in com token do aparelho:** `CheckinRequest.token` opcional, validado no servidor (tamanho, alfabeto, símbolos distintos), com conflito se já é de outra comanda.
- **App do quiosque:** botão **Pedir** na lista de Clientes abre a tela "Lançar pedido": cardápio com **+ / −**, total, e um id gerado ao abrir a tela (reenviar não duplica). Esgotado aparece sem botões.
- Decisões em [ADR 0017](../adr/0017-pedido-lancado-pela-equipe.md).

### 2. Decisão para você confirmar

**A equipe consegue lançar mesmo com os pedidos pausados e com o limite de saldo estourado.** Motivo: o pedido feito sem sinal chega tarde, e recusá-lo depois do cliente atendido seria pior. É uma linha para desfazer. Se preferir que o limite valha também para a equipe, diga e eu mudo antes do 6.2.

### 3. Testes novos (`LancarPedidoApiTest`, 11)

- Dono lança: status `RECEBIDO`, total, origem `ATENDENTE`, autor, e aparece na fila.
- Atendente lança: o autor é o atendente, não o dono.
- Reenviar o mesmo pedido: 201 e depois 200, um pedido só.
- Pausa e limite barram o cliente, mas não a equipe. Comanda bloqueada continua barrando a equipe.
- **Isolamento:** produto de outra barraca é recusado sem vazar o nome; dono de outra barraca recebe 404 e nada é criado; sem login, 401.
- JSON inválido e pedido vazio: 400.
- Check-in com token do aparelho: adotado e o QR já abre a página do cliente; mesmo id repetido não muda nada; token de outra comanda é 409; tokens fracos ou malformados são 400.

### 4. O que o teste automático não cobre

- O toque real nos botões em celular de verdade (o E2E cobre no Pixel 7 emulado). Itens 152 a 156 do roteiro.
- **Ainda não existe fila offline:** sem sinal, a tela tenta de novo 3 vezes e depois mostra o erro. É o 6.2.
- A observação por item ("sem gelo") ainda não existe nesta tela (o servidor aceita). Posso acrescentar no 6.2, se fizer falta.

### 5. Próximo passo

Aprovação do 6.1 para começar o 6.2.

---

## Correções da revisão de código (lote 1: itens 1 a 20)

- **Data:** 2026-10-02. Pedido do Matheus antes de seguir para o 6.2.
- **CI:** ✅ verde no PR #7 (commit `09623de`: 428 testes de backend, eram 412; 11 E2E; teste de restauração já com `pipefail`). Novos: `CorrecoesRevisaoTest` (14), `SenhaRulesTest` (1) e um caso em `MoneyTest`.
- **Os testes novos foram rodados contra o código antigo:** 14 falham lá, o que confirma os bugs. Os outros 2 já passavam e ficam como guarda contra regressão: o fechamento sem pagamento nenhum e o login com senha longa (este já respondia 401, então não precisou de correção no login).

| # | Problema | O que mudou | Teste |
|---|---|---|---|
| 1 | Resposta 2xx com o corpo cortado: a página do cliente descartava o id do pedido e podia duplicar | `api.ts` do cliente (e do app) trata corpo ilegível como falha de rede: o pedido continua pendente e é reenviado com o mesmo id | manual (roteiro 157) |
| 2 | Fechar sem receber ignorava a recusa de cancelamento e fechava a conta com pedidos esperando pagamento | Se o cliente já pagou parte dos pedidos que esperam pagamento, o fechamento é **recusado** (409 `pago-em-parte`, com o valor) e nada muda. Sem pagamento, cancela todos e fecha | `CorrecoesRevisaoTest` (2) |
| 3 | O cancelamento no fechamento liberava pedidos seguintes para a fila | `FilaService.cancelarNoEncerramento`: cancela sem chamar a liberação | idem |
| 4 | Pagamento sem resposta: fechar e reabrir o diálogo gerava outro id e podia cobrar duas vezes | O pagamento é guardado (memória + armazenamento do aparelho) **antes** de sair; reabrir mostra o aviso e reenvia o mesmo id e valores, travados. Recusa do servidor (4xx) libera | manual (roteiro 158) |
| 5 | `Money.parseReais` estourava o `Long` (184467440737095517 reais virava 84 centavos) | Limite de 24 caracteres antes da regex, até 12 dígitos de reais e aritmética com `Math.multiplyExact`/`addExact` | `MoneyTest` |
| 6 | Convite de uso único usado por cadastros simultâneos | O convite é lido com a linha travada (`travarPorHash`) | `CorrecoesRevisaoTest` (4 cadastros ao mesmo tempo, 1 conta) |
| 7 | Link de troca de senha usado duas vezes ao mesmo tempo; pedidos simultâneos geravam vários links válidos | Link lido com trava; o pedido de troca trava a linha do usuário | `CorrecoesRevisaoTest` (2) |
| 8 | O cabeçalho `Forwarded` passava pelo Nginx e tem prioridade sobre o `X-Forwarded-For` | `nginx-mesamar-proxy.conf` apaga `Forwarded`, `X-Forwarded-Port`, `-Prefix` e `-Ssl` | — (vale no deploy) |
| 9 | Banco de treino/teste configurado igual ao de produção levaria o `DROP DATABASE` ao banco real | `banco_descartavel` em `comum.sh` recusa o `DB_NAME`, `postgres` e `template*` | conferido à mão (`bash`) |
| 10 | No CI, o `tee` escondia a falha do teste de restauração | `set -o pipefail` no passo | CI |
| 11 | O limite de 72 da senha era em letras; o BCrypt conta bytes | A regra conta bytes UTF-8, com mensagem que explica acentos e emojis | `SenhaRulesTest` |
| 12 | Comanda bloqueada com saldo zero sumia de Pagamentos e não era encerrada de madrugada | Bloqueada aparece sempre em "Contas abertas"; o encerramento diário inclui as bloqueadas sem saldo | `CorrecoesRevisaoTest` |
| 13 | Conta fechada sem receber aparecia como "Conta paga" | Campo `encerradaComSaldo` na conta do cliente; a página diz "Conta encerrada pelo quiosque" e "Ficou sem pagar". O motivo continua só para o dono | `CorrecoesRevisaoTest` |
| 14 | O preço podia subir entre montar o carrinho e enviar, sem o cliente ver | O item leva o preço visto (`precoCentavos`, opcional); se mudou, 409 `preco-mudou`. A página recarrega o cardápio e mostra o total novo; o app do quiosque recarrega mantendo as quantidades | `CorrecoesRevisaoTest` (2) |
| 15 | Salvar dados, regras ou Pix sobrescrevia a pausa feita no app | Os três releem a barraca com a linha travada (`BarracaRepository.travar`), e a pausa também | `CorrecoesRevisaoTest` |
| 16 | Dois envios do cadastro da barraca disputavam o dono | Trava a linha do usuário; o segundo envio devolve a barraca já criada | `CorrecoesRevisaoTest` |
| 17 | Dois cadastros com o mesmo nome escolhiam o mesmo endereço | A escolha do endereço usa `pg_advisory_xact_lock` (um cadastro por vez) | `CorrecoesRevisaoTest` |
| 18 | Limites de guarda-sóis, categorias, produtos, atendentes e push contavam sem trava | Cada criação trava a barraca antes de contar | `CorrecoesRevisaoTest` (lotes simultâneos de guarda-sóis) |
| 19 | A aba Pagamentos não se atualizava quando um pedido era criado ou cancelado | O evento `fila` e a busca de reserva (sem tempo real) também atualizam Pagamentos | manual (roteiro 159) |
| 20 | Sem `localStorage`, o envio do cliente voltava sem fazer nada | O pedido pendente fica também na memória da página | manual (roteiro 160) |

### Decisão para você confirmar

**Item 2:** quando o cliente pagou parte de pedidos que ainda esperavam pagamento (modo "pagar a cada pedido") e foi embora, o dono **não consegue** fechar sem receber: o sistema não registra devolução, e cancelar esses pedidos deixaria a conta negativa. A comanda fica em "Contas abertas" até alguém receber o resto. É um caso raro. Se preferir outra saída (por exemplo, mandar esses pedidos para a fila mesmo assim), diga.

### O que o teste automático não cobre

- Os itens 1, 4, 19 e 20 são do front e não têm teste automático (os apps só têm E2E, e simular resposta cortada ou armazenamento bloqueado no Playwright é frágil). Roteiro 157 a 160.
- Item 8 só vale quando o `nginx-mesamar-proxy.conf` for copiado na VPS (no deploy, que ainda não aconteceu).
