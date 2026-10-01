# Relatório — Fase 4: fila do dono e tempo real

- **Branch:** `fase-4-fila`
- A Fase 4 está dividida em 2 checkpoints, aprovados um por vez:
  - [x] **4.1 — Fila, ações e tempo real (SSE com LISTEN/NOTIFY)** (aprovado; CI verde no PR #5)
  - [ ] **4.2 — Web Push, alerta de atraso e "Pausar pedidos"** ← checkpoint atual
- Decisões de arquitetura: [ADR 0010](../adr/0010-fila-e-tempo-real.md) (fila e tempo real) e [ADR 0011](../adr/0011-web-push-atraso-e-pausa.md) (Web Push, atraso e pausa)

---

## Checkpoint 4.1 — Fila, ações e tempo real

- **Data:** 2026-10-01
- **CI:** ✅ verde no GitHub (PR #5): ktlint, **267 testes de backend**, imagem Docker, smoke do compose e **10 E2E** no tamanho de celular.

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

---

## Checkpoint 4.2 — Web Push, alerta de atraso e "Pausar pedidos"

- **Data:** 2026-10-01
- **CI (rodado localmente, com as mesmas etapas):** ✅ ktlint, **303 testes de backend** (+36), imagem Docker, smoke do compose e **10 E2E** no tamanho de celular. O resultado no GitHub entra aqui depois do push.
- **Com este checkpoint a Fase 4 fica completa.**

### 1. O que foi feito

- **Avisos de pedido com o app fechado (Web Push):** pedido novo chega como notificação no celular bloqueado ("Pedido nº 23 · Guarda-sol 12" e "3 itens"), com vibração, e **tocar abre a fila**. O aviso vai para o dono e para os atendentes da barraca, cifrado (RFC 8291) e assinado pelo servidor (VAPID).
- **Aba Mais → "Avisos de pedido":** ativar e desativar neste celular, **"Mandar um aviso de teste"** e um texto claro para cada situação (servidor sem chaves, iPhone sem instalar, permissão negada, navegador sem suporte).
- **Alerta de pedido atrasado:** passou do tempo configurado na barraca (`Regras → Alerta de pedido parado`), sai **um aviso só** por pedido: notificação no celular e, com o app aberto, três bips graves e um aviso na tela.
- **"Pausar pedidos":** botão na Fila, com recado opcional para os clientes. Os clientes veem na hora ("O quiosque pausou os pedidos: Voltamos em 10 minutos") e não conseguem pedir; "Voltar a receber pedidos" libera.
- **Gerador de chaves:** `./gradlew --console=plain -q :backend:gerarVapid`. Passo a passo no `docs/deploy.md`.

### 2. Decisões

Ver o [ADR 0011](../adr/0011-web-push-atraso-e-pausa.md). As que merecem o seu olho:
- **Sem biblioteca de Web Push:** a criptografia é do próprio JDK, e o corpo cifrado do exemplo da RFC 8291 sai **idêntico byte a byte**. É código nosso, com teste contra a especificação.
- **Sem ShedLock:** `FOR UPDATE SKIP LOCKED` já impede o aviso em dobro com dois servidores (testado), com uma dependência a menos.
- **Sem a tabela `Notificacao`** do modelo do plano: não há caixa de notificações no MVP.
- **O aviso não leva o nome do cliente**, só número do pedido, guarda-sol e quantidade de itens.
- **O servidor só envia para Google, Mozilla, Apple e Windows**, por https: o endereço vem do navegador de alguém logado, e sem essa trava daria para fazê-lo acessar endereços internos.
- **Quem sai do app ("Sair deste celular") deixa de receber** os avisos naquele aparelho, e **atendente desativado também**.
- **Dono e atendentes podem pausar** os pedidos. Se preferir só o dono, é uma linha.

### 3. O que precisa de você

- **Gerar as chaves VAPID e colocar no `.env` do servidor** (3 linhas; passo a passo no `docs/deploy.md`). **A chave privada é segredo.** Sem elas, o app mostra "Os avisos ainda não foram configurados no servidor", e o resto funciona.
- **No iPhone**, o aviso só funciona com o app instalado na tela inicial (iOS 16.4+). O onboarding já explica.
- **Testar em celular de verdade**, em HTTPS (o push não funciona em `http://IP-DO-PC`): os testes automatizados cobrem o servidor inteiro, mas a entrega pela Google ou Apple só se vê com um aparelho. Roteiro nos itens 76 a 85.

### 4. Um problema que o Docker pegou

Ao criar o gerador de chaves, o projeto passou a ter **duas funções `main`**, e o `bootJar` do Spring Boot não sabe qual é a do app. Os testes não mostram isso (eles não geram o JAR); a imagem Docker sim. Corrigido declarando a classe principal no `build.gradle.kts`.

### 5. Testes automatizados novos

- **Criptografia (`WebPushCriptoTest`, 9 testes):** as chaves da RFC; **cada valor intermediário** do Apêndice A (ECDH, PRK_key, IKM, PRK, CEK, nonce); **o corpo cifrado idêntico ao exemplo da RFC byte a byte**; o aparelho decifra o exemplo; ida e volta com chaves novas (salt e chave efêmera mudam a cada envio); mensagem grande demais recusada; ponto fora da curva e chaves mal formadas recusados; JWT VAPID `ES256` com a origem do serviço e **assinatura conferida com a chave pública**.
- **Destino (`DestinoPushTest`, 3 testes):** só https nos serviços conhecidos; recusa `localhost`, IPs, redes internas, metadados de nuvem, nomes parecidos, credenciais na URL, porta diferente e URL gigante.
- **Inscrição (`PushApiTest`, 8 testes):** configuração, inscrever sem duplicar, destinos inválidos, chaves inválidas, **uma barraca não cancela nem toma a inscrição de outra**, desativar, limite de 40 aparelhos, sem login 401 e sem CSRF 403.
- **Envio e atraso (`PushEnvioTest`, 11 testes), com um "serviço de push" falso na máquina:**
  - pedido novo avisa só os aparelhos da barraca, com o **texto decifrado**, sem o nome do cliente, e os cabeçalhos e a **assinatura VAPID** conferem;
  - "Balcão" e "1 item"; pedido que aguarda pagamento não avisa;
  - dono e atendente recebem, e **o atendente desativado não recebe e a inscrição dele é removida**;
  - **410 e 404 removem a inscrição; 503 e rede fora do ar não**, e um serviço de push fora do ar não atrasa o pedido;
  - o aviso de teste vai só para o aparelho que pediu, e nem o dono de outra barraca consegue disparar o de um aparelho alheio;
  - **atraso: avisa uma vez só**, no push e no app aberto; só o que está na fila e passou do tempo **da própria barraca**; **6 verificadores ao mesmo tempo sobre 3 pedidos: 3 avisos, nenhum em dobro**; só chega aos aparelhos da própria barraca.
- **Pausa (`PausaApiTest`, 5 testes):** pausar com recado e voltar a receber; recado padrão e limite de 80 letras; **avisa na hora o app e só as comandas da própria barraca**, e repetir não avisa de novo; uma barraca não pausa a outra; validação, login e CSRF.
- **E2E (celular):** a aba Mais mostra o estado dos avisos sem botão enganoso; a atendente pausa os pedidos com um recado e **a Ana, em outro celular, vê o recado na hora**; ao voltar a receber, ele some.

### 6. Como validar (na entrega final)

Itens 76 a 85 de [`docs/testar-localmente.md`](../testar-localmente.md). Os mais importantes, do plano: **app fechado e tela bloqueada, um pedido novo gera a notificação** (item 79), **deixar um pedido parado além do tempo e ver o alerta** (item 81) e **pausar os pedidos e ver a mensagem no celular do cliente** (item 83).

