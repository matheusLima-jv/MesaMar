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

---

## Correções da revisão de código (lote 2: itens 21 a 40)

- **Data:** 2026-10-02.
- **CI:** ✅ verde no PR #7 (commit `282fb6a`: 432 testes de backend, eram 428; 11 E2E). Novo: `CorrecoesRevisao2Test` (4). Quase todo o lote é dos apps, que não têm teste automático além do E2E: o roteiro manual ganhou os itens 165 a 177.

### A base que resolve vários itens de uma vez (app do quiosque)

- **`api.ts`:** toda chamada tem **prazo** (15 s leitura, 20 s ação) e pertence à **sessão local**. Sair (ou a sessão expirar) aborta tudo o que está em andamento, e a resposta que chegar depois é descartada (`descartada`), sem tocar em tela nem store. Ação que estoura o prazo vira o mesmo erro de "sem resposta" (status 0): quem chama reenvia com o mesmo id.
- **`dom.ts`:** `abrirTela(root)` diz se a tela que pediu os dados ainda está aberta (resposta atrasada de outra aba é descartada). `registrarSobreposicao` / `fecharSobreposicoes`: diálogos e o QR em tela cheia somem ao trocar de aba, sair ou a sessão acabar, sem rodar o "ao fechar" de quem abriu.
- **`busca.ts`:** `umaPorVez` (uma busca por vez; o que chega no meio vira uma busca a mais, depois) e `comVariacao` (±20% nas esperas).
- **`pendencias.ts`:** check-in e lançamento sem resposta ficam guardados (memória + armazenamento do aparelho) com o id, até o servidor responder. É a semente da fila offline do 6.2.

| # | O que mudou |
|---|---|
| 21 | Cada service worker apaga só os caches com o **próprio** prefixo (`mesamar-cliente-` / `mesamar-staff-`) |
| 22 | Clientes, Cardápio, Lançar pedido e Mais descartam respostas de telas fechadas (`abrirTela`) |
| 23 | `encerrarSessaoLocal()` ao sair: chamadas em andamento são abortadas e descartadas; os stores só aceitam resposta enquanto estão ligados |
| 24 | Fila e Pagamentos buscam com `umaPorVez`: uma resposta velha nunca sobrescreve uma nova, e rajadas de avisos viram uma busca |
| 25 | "Esgotou/Voltou": um envio por produto de cada vez; toques no meio só mudam o desejado, e o envio seguinte leva o último. Recusa volta ao último estado **confirmado** pelo servidor |
| 26 | Lançar pedido: + e − ficam travados enquanto o pedido viaja. Recusa definitiva gera um id novo para o próximo envio |
| 27 | Sair só volta ao login quando o servidor confirma (ou responde 401). Sem resposta, mostra o erro e deixa tentar de novo |
| 28 | O timer de reconexão do tempo real é guardado e cancelado ao sair; nada religa a conexão depois |
| 29 | Diálogos (receber, cancelar, pausar, QR) somem ao trocar de aba, sair e quando a sessão expira |
| 30 | Prazo nas chamadas dos dois apps (o cliente também: 15 s / 20 s, e o pedido segue reenviando com o mesmo id) |
| 31 | `serviceWorker.ready` com prazo de 8 s: ativar, testar e sair nunca ficam presos. Estado novo "app não pronto" |
| 32 | Check-in e lançamento sem resposta sobrevivem a trocar de aba e recarregar: a tela volta com os mesmos dados, travados, para reenviar |
| 33 | O cliente reenviando depois de chamar um atendente: o reenvio (`reenvio: true`) que bate com um pedido **lançado pela equipe** na última hora, mesmos produtos e quantidades, não cancelado, recebe o pedido da equipe em vez de criar outro. A primeira tentativa nunca é conciliada (o cliente pode querer repetir de propósito) |
| 34 | O build grava no `sw.js` a lista de arquivos da publicação (JS, CSS, fontes, ícones); a instalação guarda todos, então a primeira reabertura sem rede funciona |
| 35 | As gravações no cache usam `event.waitUntil` e engolem erro de quota |
| 36 | Versão do cache = hash da publicação (gravado no build); a página do cliente guarda no máximo 3 comandas |
| 37 | A cópia do cardápio guardada pelo service worker leva a hora (`X-MesaMar-Salvo-Em`); sem sinal, a página diz "Mostrando o cardápio salvo às HH:MM: preços e itens podem ter mudado" |
| 38 | Evento de tempo real `cardapio` (preço, esgotado, produto, categoria): a página do cliente, a aba Cardápio e a tela Lançar pedido recarregam (a de lançar mantém o que já foi escolhido) |
| 39 | O cliente respeita o `Retry-After` do 429 e varia a espera; o app não faz a consulta de reserva com a aba escondida e busca na hora ao voltar; reconexão com variação |
| 40 | Avisos de pedido: "sem conexão" (com Tentar de novo) é diferente de "servidor sem chaves" |

### Decisões que tomei (fáceis de desfazer)

- **Item 33, janela de 60 minutos** e "mesmos produtos e quantidades, observação não conta". Se o atendente lançou com uma quantidade diferente, os dois pedidos ficam (o quiosque cancela um na fila).
- **Item 32:** o check-in e o lançamento pendentes **não têm botão de descartar**. Descartar sem saber se chegou é o que gera duplicata; a saída é reenviar (o servidor devolve o que já existe) ou receber a recusa.
- **Item 36:** o `vite.config.ts` saiu do `tsc` dos dois apps (ele roda no Node e agora usa `node:fs`); o Vite continua lendo normalmente.

### Testes de backend (`CorrecoesRevisao2Test`)

- Reenvio igual ao pedido da equipe devolve o pedido da equipe (200, mesmo id, um pedido só), mesmo com os itens em outra ordem e quebrados em linhas.
- Pedido igual na primeira tentativa, ou reenvio com outra quantidade, viram pedidos novos.
- Pedido da equipe cancelado ou de outra comanda não é reconhecido.
- Marcar esgotado avisa o app e as páginas abertas da barraca, e não as de outra barraca.
- Os 2 primeiros testam comportamento que não existia; os 2 do meio também passariam no código anterior (são guardas).
