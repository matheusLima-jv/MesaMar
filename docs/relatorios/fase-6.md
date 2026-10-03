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
