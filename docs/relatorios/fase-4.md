# Relatório — Fase 4: fila do dono e tempo real

- **Branch:** `fase-4-fila`
- A Fase 4 está dividida em 2 checkpoints, aprovados um por vez:
  - [ ] **4.1 — Fila, ações e tempo real (SSE com LISTEN/NOTIFY)** ← checkpoint atual
  - [ ] **4.2 — Web Push, alerta de atraso e "Pausar pedidos"**
- Decisões de arquitetura: [ADR 0010](../adr/0010-fila-e-tempo-real.md)

---

## Checkpoint 4.1 — Fila, ações e tempo real

- **Data:** 2026-10-01
- **CI (rodado localmente, com as mesmas etapas):** ✅ ktlint, **267 testes de backend** (+15), imagem Docker, smoke do compose e **10 E2E** no tamanho de celular. O resultado no GitHub entra aqui depois do push.

### 1. O que foi feito

- **Aba Fila no app do quiosque**, com a contagem de pedidos esperando em qualquer aba:
  - cartões **do mais antigo para o mais novo** (horário do servidor), com o **guarda-sol em destaque**, número, nome do cliente, itens e observações;
  - **tempo de espera com cor**: verde, amarelo e vermelho pelo tempo de alerta da barraca, e escrito "Atrasado";
  - ações de **1 toque**: **Em preparo**, **Entregue** e **Cancelar…** (pedido inteiro ou um item, com **motivo obrigatório**: Esgotou, Cliente desistiu ou Outro com detalhe);
  - **filtros**: Todos, Pendentes e por guarda-sol (ou balcão);
  - indicador **"Ao vivo" / "Reconectando…"**, **som e vibração** para pedido novo (com botão para desligar) e selo "Novo".
- **Tempo real (SSE)** alimentado por `LISTEN/NOTIFY` do PostgreSQL, com **batimento de 25 s** e reconexão automática. Avisa o app da barraca certa e a comanda certa.
- **O cliente vê ao vivo** o status do pedido (Recebido, Em preparo, Entregue, Cancelado), o saldo e **um aviso com o motivo** quando o quiosque cancela algo.
- **Banco (V9):** o pedido guarda quando entrou em preparo, foi entregue e cancelado, o motivo e quem atendeu.

### 2. Decisões

Ver o [ADR 0010](../adr/0010-fila-e-tempo-real.md). As que merecem o seu olho:
- **Dá para pular o "Em preparo"** (de Recebido direto para Entregue): no corre-corre, o atendente pode ir direto.
- **Entregue e cancelado são finais.** Errou ao marcar "Entregue"? Hoje não tem como desfazer pelo app. Se isso for um problema no piloto, é uma regra a mudar (e um motivo a registrar).
- **Cancelar a última linha cancela o pedido todo.**
- **Uma conexão SSE já aberta não é revalidada:** um atendente desativado recebe só ids (nada de dados) por até 30 minutos, e não consegue ver nem mudar nada, porque cada chamada é revalidada.

### 3. Ainda não existe (é o 4.2)

- **Web Push** com o app fechado e a tela bloqueada, o **alerta de pedido atrasado** e o botão **"Pausar pedidos"** (o servidor já recusa pedido de uma barraca pausada, e a página do cliente já mostra o aviso; falta o botão no app).

### 4. Testes automatizados novos

- **Fila (`FilaApiTest`, 10 testes):**
  - só o pendente aparece, **por ordem de chegada do servidor**: um pedido "antigo" que chegou por último não fura a fila, e o empate de horário é desempatado pelo número;
  - entregue, cancelado e aguardando pagamento ficam fora;
  - o pedido anda para frente, repetir é inofensivo, **voltar de entregue dá 409**, cancelado é final, só "em preparo" e "entregue" valem, e dá para pular direto para entregue;
  - **6 ações simultâneas no mesmo pedido, 5 vezes: nunca fica entregue e cancelado**;
  - cancelar item exige motivo, tira do saldo e guarda o texto para o cliente; cancelar a última linha cancela o pedido; o texto cabe em 80 letras; "Outro" leva o detalhe;
  - **isolamento:** a barraca B não vê a fila da A nem muda pedido ou item dela (404), nem com o id de item de outro pedido da mesma barraca;
  - sem login 401, e as ações sem CSRF dão 403.
- **Tempo real (`TempoRealApiTest`, 5 testes):**
  - **pedido novo avisa só o app da barraca certa e só a comanda certa** (a outra barraca e o outro cliente não recebem nada), e a mudança de status também avisa;
  - batimento e ressincronização chegam a quem está ouvindo;
  - a conexão que some é retirada no batimento seguinte;
  - token inexistente dá 404 sem corpo, e uso demais é freado (429);
  - o pedido da barraca A não chega à comanda da B.
- **E2E (celular):** o Bruno faz check-in e **pede em outro celular: o pedido aparece no fim da fila da atendente sem recarregar**, com "Novo" e a contagem na aba. Filtro por guarda-sol. "Em preparo" aparece **na hora** na tela da Ana. Cancelar um item **sem motivo** é recusado; com "Esgotou", a Ana recebe o aviso, vê a conta cair de R$ 99 para R$ 74 e o bloqueio do limite sumir. Entregue some da fila e aparece na Ana. Cancelar um pedido inteiro com "Outro" e detalhe chega à Ana. Sem erro no console e sem rolagem lateral.

### 5. Como validar (na entrega final)

Itens 67 a 75 de [`docs/testar-localmente.md`](../testar-localmente.md). Os mais importantes, do plano: **três celulares como clientes pedindo com 10 s de intervalo, conferindo a ordem na fila** (item 68), e **o aviso de atraso por cor** (item 69). A validação do push com a tela bloqueada é do 4.2.
