# Relatório — Fase 5: fechamento, Pix e pagamentos

- **Branch:** `fase-4-fila` (o 5.1 foi feito em cima da Fase 4, ainda no PR #5; ver a nota em "Antes de aprovar")
- A Fase 5 está dividida em 3 checkpoints, aprovados um por vez:
  - [x] **5.1 — Fechar conta pelo cliente e Pix** (aprovado; CI verde no PR #5)
  - [x] **5.2 — Aviso "indo pagar", aba Pagamentos e confirmação** (aprovado; CI verde no PR #5)
  - [ ] **5.3 — Relatório do dia e anonimização LGPD** ← checkpoint atual
- Decisões de arquitetura: [ADR 0012](../adr/0012-fechar-conta-e-pix.md) (fechar conta e Pix) e [ADR 0013](../adr/0013-receber-pagamentos-e-fechar-conta.md) (receber, bloquear e encerrar) e [ADR 0014](../adr/0014-relatorio-do-dia-e-anonimizacao.md) (relatório e LGPD)

---

## Checkpoint 5.1 — Fechar conta pelo cliente e Pix

- **Data:** 2026-10-01
- **CI:** ✅ verde no GitHub (PR #5): ktlint, **323 testes de backend**, imagem Docker, smoke do compose e **10 E2E** no tamanho de celular.

### 1. O que foi feito

- **Aba "Conta" na página do cliente**, ao lado de Cardápio e Meus pedidos (e um botão "Fechar conta" em Meus pedidos):
  - **extrato** com todos os pedidos, itens cancelados riscados, pagamentos já feitos (com troco, no dinheiro) e "Falta pagar";
  - **escolha da forma**: Pix (só se o quiosque tem Pix), Dinheiro (com "vou pagar com R$ ___", opcional) ou Cartão;
  - **Aguardando confirmação do quiosque**: no Pix, o **QR e o "Copiar código Pix"** com o valor da conta, o recebedor e o botão **"Já paguei"**; no dinheiro e no cartão, "Vá ao quiosque pagar…" (com o troco, se informado);
  - **"Voltar e pedir mais"** enquanto nenhum pagamento foi confirmado, e **"Trocar a forma de pagamento"**;
  - tela "Conta paga" pronta para quando o quiosque confirmar (5.2).
- **Máquina de estados da comanda** `ABERTA ⇄ AGUARDANDO_PAGAMENTO`, com trava da linha da comanda. Com a conta fechando, o cardápio não deixa pedir e diz como voltar.
- **O saldo agora desconta os pagamentos confirmados**, e o limite de saldo do dono passa a valer sobre o que falta pagar.
- **Gerador de BR Code Pix** (`BrCodePix`), com CRC16 conferido contra o exemplo do manual do Banco Central (`…63041D3D`).
- **Banco (V11):** forma, troco e hora do aviso na comanda; **tabela `pagamento`** (ainda sem escrita: a confirmação é do 5.2), com FKs compostas que impedem pagamento de outra barraca.

### 2. Decisões

Ver o [ADR 0012](../adr/0012-fechar-conta-e-pix.md). As que merecem o seu olho:
- **"Já paguei" é só um aviso**, não prova o Pix. O 5.2 põe "confira no app do banco antes de confirmar" na confirmação.
- **Conta zerada não fecha**, e **o troco nunca pode ser menor que a conta** (até R$ 10.000,00).
- **Fechar de novo troca a forma de pagamento**, sem precisar "voltar" antes.
- **No 5.1 o app do quiosque ainda não mostra comandas aguardando pagamento** e o dono não é avisado: isso é o 5.2 (aviso SSE e push, aba Pagamentos). Eu quis manter o 5.1 só no lado do cliente e no que ele grava; se preferir o aviso já agora, é trocar a ordem.
- A tela "Conta" consulta o servidor a cada 15 s (o tempo real da conta chega com o 5.2).

### 3. O que precisa de você

- **Conferir o BR Code num banco de verdade.** O CRC bate com o exemplo do manual e o código passa nos testes de estrutura, mas só um app de banco confirma que ele é aceito: gere o Pix, leia o QR ou cole o código e veja se o banco mostra o **nome e a cidade da barraca e o valor**. Roteiro nos itens 86 a 96.
- **Sobre a fonte do exemplo do BCB:** o payload do exemplo foi conferido numa busca na web (aparece como exemplo oficial do Manual de Padrões para Iniciação do Pix). **Não abri o PDF do manual inteiro.**

### 4. Testes automatizados novos

- **BR Code (`BrCodePixTest`, 8 testes):** o exemplo do manual, byte a byte; CRC16 contra o valor de referência `123456789`; campos na ordem do padrão com valor e txid; valor mínimo e máximo, e alterar uma letra quebra o CRC; recusa do que o padrão não aceita; cada tipo de chave do cadastro cabe no campo; nome e cidade do cadastro entram como o banco mostra.
- **Fechar conta (`FecharContaApiTest`, 12 testes):**
  - extrato com cancelados e saldo, e o **pagamento confirmado abatendo o saldo** (no extrato, em Meus pedidos e na página de abertura);
  - fechar no Pix: **código com o saldo, a chave, o nome do recebedor e CRC válido**, comanda aguardando e **pedido novo recusado**;
  - "já paguei" marca a hora do aviso e **repetir não muda a hora**; só vale com um Pix aguardando;
  - dinheiro com troco e cartão avisam na hora; **troco menor que a conta é recusado e a conta não fecha**; troco é ignorado no cartão;
  - trocar a forma fechando de novo;
  - recusas: sem forma, forma desconhecida, JSON inválido, conta zerada, **Pix sem chave no quiosque**;
  - **comanda bloqueada, encerrada, cancelada ou de barraca desligada não fecha**;
  - voltar e pedir mais reabre e limpa a escolha; **depois de um pagamento confirmado não dá para voltar**; comanda encerrada não reabre;
  - **isolamento:** fechar uma comanda não mexe na outra da mesma barraca nem na de outra barraca; o extrato de uma não mostra itens da outra; os pagamentos de uma barraca não abatem o saldo de outra; token desconhecido dá 404 em todas as rotas novas.
- **E2E (celular):** a Ana abre a aba Conta, erra o troco, fecha em dinheiro (troco para R$ 100,00), vê o cardápio bloqueado, **volta e pede mais**, escolhe o Pix, vê o **QR e o código** (`000201…6304XXXX`) com R$ 49,00 e toca em "Já paguei". Sem rolagem lateral e sem erro de console.

### 5. Como validar (na entrega final)

Itens 86 a 96 de [`docs/testar-localmente.md`](../testar-localmente.md). Os mais importantes: **ler o QR do Pix num app de banco** (item 91) e **a conta fechando bloquear pedidos e o "voltar" liberar** (itens 88 e 89).

---

## Checkpoint 5.2 — Receber pagamentos, aviso "indo pagar" e fechar a conta

- **Data:** 2026-10-01
- **CI:** ✅ verde no GitHub (PR #5): ktlint, **347 testes de backend** (323 do 5.1 + 24 novos), imagem Docker, smoke do compose e **10 E2E** no tamanho de celular (o do painel ganhou o passo do 5.2).

### 1. O que foi feito

- **Aba Pagamentos no app do quiosque** (a 3ª aba, com o número de contas esperando):
  - em cima, quem tocou em **"Fechar conta"**, por ordem de aviso: guarda-sol, cliente, **quanto falta**, forma, "avisou há N min", o **troco a devolver** (dinheiro) e, no Pix, **"Confira no app do banco antes de confirmar"**;
  - embaixo, **contas abertas com saldo** (as maiores primeiro, com o total em aberto), para enxergar quem está devendo;
  - **ao vivo**: a conta aparece sozinha, com **som próprio** (três notas) e um aviso com o nome ("Guarda-sol 12 · Ana está indo pagar · R$ 87,50 · Dinheiro (troco para R$ 100,00)").
- **Receber**: forma (Pix, dinheiro, cartão), **valor recebido agora** (total ou uma parte, para **conta dividida**) e, no dinheiro, "o cliente entregou R$ …" com o **troco calculado na hora**. Confirmar não cobra duas vezes se o toque se repetir ou o sinal cair.
- **Conta encerra sozinha** quando o saldo chega a zero (se o cliente tinha fechado a conta). O cliente vê, **sem recarregar**, o abate e depois **"Conta paga"** com o extrato.
- **Bloquear / desbloquear** uma comanda (o cliente deixa de pedir), e **Encerrar conta** (sem saldo).
- **Fechar sem receber** (**só o dono**): motivo obrigatório, grava o valor que ficou sem receber, quem fechou e a hora.
- **Push "indo pagar"** (app fechado, tela bloqueada): "Guarda-sol 12 · indo pagar" / "R$ 87,50 · Dinheiro (troco para R$ 100,00)"; no Pix, "Pix enviado" / "confira no app do banco". **Sem o nome do cliente** no push.
- **"Pagar a cada pedido"**: o pagamento **coloca o pedido na fila** (e dispara o aviso de pedido novo), na ordem em que foram feitos, e o tempo de espera passa a contar **desde a liberação**.
- **O extrato vence em 7 dias** depois da conta paga (o link dá 404, como um token que nunca existiu).

### 2. Decisões

Ver o [ADR 0013](../adr/0013-receber-pagamentos-e-fechar-conta.md). As que merecem o seu olho:
- **Só o dono fecha sem receber.** É abrir mão de dinheiro. Atendentes recebem, bloqueiam e encerram contas sem saldo. Se quiser que atendentes também fechem sem receber, é uma condição.
- **O push não leva o nome do cliente** (igual aos outros avisos); o app aberto mostra o nome.
- **Os pagamentos cobrem os pedidos na ordem em que foram feitos** (modo "pagar a cada pedido"): não dá para pagar só o do meio.
- **Pedido que nunca foi para a fila não é calote:** ao fechar sem receber, os pedidos que esperavam pagamento são cancelados antes de contar o que ficou sem pagar.
- **Cancelar um item depois de pagar** deixa a conta "a favor do cliente" e **não há devolução registrada**: o dono resolve no balcão. Fica para o piloto dizer se vale registrar.
- Conta **aberta** que chega a zero **continua aberta** (o cliente pode pedir mais); só a que estava fechando se encerra sozinha.

### 3. O que precisa de você

- **Testar o aviso com o app fechado e a tela bloqueada**, em HTTPS (itens 100 e 101 do roteiro): os testes automatizados cobrem o servidor inteiro, mas a entrega pelo Google ou pela Apple só se vê num aparelho.
- **Conferir o troco e a conta dividida na mão** com uma conta de verdade (itens 104 e 105): é onde um erro custa dinheiro.

### 4. Testes automatizados novos

- **Pagamentos (`PagamentosApiTest`, 21 testes):**
  - a lista: **por ordem de aviso**, só as contas da própria barraca, abertas com saldo (e sem a conta zerada);
  - **receber o total em dinheiro com troco** encerra a conta, grava quem confirmou e o cliente vê a conta paga; dinheiro exato sem troco; **parcial** (abate o saldo, mantém a conta e **libera o limite de saldo**); **dividido** em Pix, cartão e dinheiro (só encerra no último, e o **Pix do cliente passa a cobrar só o que falta**);
  - recusas: valor zero, negativo, acima do saldo, forma desconhecida, dinheiro entregue menor que o valor ou absurdo, id ausente, JSON inválido, conta zerada, conta encerrada ou cancelada;
  - **reenvio da mesma confirmação não cobra duas vezes**, e o id de outra comanda não serve; **5 confirmações ao mesmo tempo nunca passam do saldo** (2 entram, 3 são recusadas);
  - **"pagar a cada pedido"**: pagar R$ 10,00 de um pedido de R$ 24,00 não libera nada; pagar o resto libera **o primeiro e só ele**, com `na_fila_em` e o tempo de espera da fila contado dali; o segundo espera o próprio pagamento;
  - bloquear impede pedidos, desbloquear libera, **dá para receber de uma comanda bloqueada**, a atendente também bloqueia, e não dá para bloquear conta encerrada;
  - encerrar só sem saldo; **fechar sem receber** grava motivo, valor, quem fechou e hora, recusa motivo curto, **só o dono** (403 para a atendente, que recebe normalmente), e **não conta como calote o pedido que nunca foi para a fila**;
  - **isolamento:** o dono de outra barraca recebe 404 em **todas** as ações (receber, bloquear, desbloquear, encerrar, fechar sem receber, id inexistente) e nada muda; sem login, 401; sem o token de segurança, 403;
  - **o extrato abre por 6 dias e 23 h e dá 404 depois de 7 dias** (em todas as rotas do cliente, com resposta igual à de um token inexistente); conta aberta velha não expira;
  - **tempo real:** fechar a conta e receber avisam só o app da barraca e só a comanda certa; a de outra barraca e a de outro cliente não recebem nada.
- **Push (`PushEnvioTest`, 3 testes novos):** o aviso de "indo pagar" leva valor, forma e troco, **sem o nome**, só aos aparelhos da barraca, e **repetir o toque não avisa de novo**; no Pix, o aviso sai no "já paguei" (e não ao escolher Pix); o **pagamento que libera o pedido dispara o aviso de pedido novo**.
- **E2E (celular):** a atendente vê a conta da Ana chegar à aba com o número na aba, recebe **R$ 20,00 no Pix** (a Ana vê o abate sem recarregar) e o resto **em dinheiro com troco de R$ 21,00**; a Ana vê **"Conta paga"**. Sem rolagem lateral e sem erro no console.

### 5. Como validar (na entrega final)

Itens 97 a 110 de [`docs/testar-localmente.md`](../testar-localmente.md). Os mais importantes: **o aviso chegar com a tela bloqueada** (item 100), **conta dividida com troco** (itens 104 e 105) e **o cliente bloqueado não conseguir pedir** (item 108).

---

## Checkpoint 5.3 — Relatório do dia e anonimização LGPD

- **Data:** 2026-10-01
- **Verificado localmente:** ktlint, **359 testes de backend** (347 do 5.2 + 12 novos), build dos dois fronts e **10 E2E** (o do painel ganhou o passo do relatório), com a imagem Docker construída do zero. **O CI do GitHub ainda não rodou para o 5.3** (os commits ainda não foram enviados).

### 1. O que foi feito

- **Relatório do dia** no painel do dono (`/painel/relatorio`, aba "Relatório"), com seletor de data e "dia anterior / dia seguinte":
  - **Recebido no dia**, total e **por forma** (Pix, dinheiro, cartão);
  - **Vendido no dia**; **clientes** do dia; **pedidos feitos pelo cliente × lançados pelo atendente**; cancelados e entregues;
  - **tempo médio do pedido à entrega**;
  - **itens mais vendidos** (10 primeiros) e **cancelamentos por motivo**, com quantidade e valor;
  - **contas em aberto agora** (as maiores primeiro, com o total) e **não recebido** (contas encerradas sem receber, com o motivo).
- **Exportação em CSV** do mesmo conteúdo (abre direto no Excel: ponto e vírgula, acentos e vírgula decimal certos).
- **Anonimização LGPD** todo dia às 3h30: depois de **90 dias**, nome vira "Cliente" e o WhatsApp é apagado. Pedidos, pagamentos e valores **não mudam**, e o relatório dá o mesmo número antes e depois.
- A **política de privacidade** foi ajustada para dizer o que realmente acontece (extrato por 7 dias; quem ainda deve só é anonimizado depois de encerrar a conta).

### 2. Decisões

Ver o [ADR 0014](../adr/0014-relatorio-do-dia-e-anonimizacao.md). As que merecem o seu olho:
- **O relatório é calculado na hora, direto dos pedidos e pagamentos.** Não há total guardado, então não tem como ficar errado em relação ao quiosque. **A conta fecha:** vendido = recebido + em aberto + não recebido (testado num cenário gerado).
- **Dia = dia em Brasília.** Um pagamento às 23h30 conta no dia certo, mesmo já sendo o dia seguinte em UTC (testado).
- **"Contas em aberto" é de agora**, não do dia escolhido: quem deve é uma foto do momento. A página explica.
- **Quem ainda deve não é anonimizado** enquanto a comanda estiver aberta, aguardando pagamento ou bloqueada, para o dono não perder o nome de quem deve. É uma exceção à regra seca de 90 dias; se preferir a regra seca, é remover uma condição do SQL.
- **O texto livre não é anonimizado** (observação de item e motivo de cancelamento): o produto não pede dado pessoal ali, mas o cliente poderia escrever um.

### 3. O que precisa de você

- **Conferir o relatório contra o seu caixa** num dia de teste (itens 111 a 114 do roteiro): é onde você vai notar qualquer diferença com o jeito que a barraca conta o dia.
- **Abrir o CSV no seu Excel ou Google Planilhas** (item 115): os acentos e a vírgula decimal dependem do programa.

### 4. Testes automatizados novos

- **Relatório (`RelatorioTest`, 7 testes)**, num cenário gerado com quatro clientes (uma conta paga em duas formas, uma em aberto, uma com item cancelado e fechada sem receber, uma lançada pelo atendente e entregue):
  - **os números batem:** recebido por forma, vendido, clientes, pedidos do cliente × do atendente, entregues, **tempo médio de 7 min 30 s**, mais vendidos, cancelamento por motivo, contas em aberto e não recebido; e **vendido = recebido + em aberto + não recebido**;
  - a página mostra os valores formatados e o **CSV traz as mesmas linhas** (cabeçalho, BOM, nome do arquivo, tipo);
  - **outro dia não mistura os números**, mas as contas em aberto são as de agora;
  - **o dia do pagamento é o de Brasília, não o do UTC**;
  - data inválida mostra hoje com aviso, e data no futuro volta para hoje (página e CSV);
  - sem entrar no painel não há relatório nem CSV (redireciona para o login);
  - **isolamento:** o dono de outra barraca não vê nenhum cliente, valor ou motivo da primeira, nem na página nem no CSV.
- **Anonimização (`AnonimizacaoTest`, 5 testes):** com 91 dias, nome vira "Cliente" e o WhatsApp some, **e o relatório, os pagamentos e os itens ficam iguais**, e a página do cliente já mostra "Cliente"; com 89 dias nada muda; **quem tem comanda aberta, fechando ou bloqueada fica de fora** e só é anonimizado depois de encerrar; rodar de novo não repete nem muda a data; o job agendado roda de madrugada no fuso de Brasília.
- **E2E (celular):** depois de a atendente receber a conta da Ana, o dono abre o relatório e vê **R$ 49,00 recebidos** (R$ 20,00 no Pix e R$ 29,00 em dinheiro), o **Bruno em aberto com R$ 25,00**, o cancelamento "Esgotou" e o link do CSV. Sem rolagem lateral e sem erro no console.

### 5. Como validar (na entrega final)

Itens 111 a 118 de [`docs/testar-localmente.md`](../testar-localmente.md). Os mais importantes: **o relatório bater com o seu caixa** (itens 112 a 114) e **o CSV abrir certo na sua planilha** (item 115).

### 6. Fim da Fase 5

Com o 5.3, a Fase 5 está completa (fechar conta e Pix, receber, bloquear e fechar a conta, relatório e LGPD). O próximo é a **Fase 6** (modo atendente offline), que o plano deixa para depois do piloto se o sinal nas praias for bom, e a **Fase 7** (preparação do piloto). **Antes de começar a Fase 6, quero a sua decisão** sobre a ordem (ver "O que precisa de você" no resumo).

### 7. Antes de aprovar

- O 5.3 está na `fase-4-fila`, no PR #5 (rascunho). Os commits do 5.3 ainda são locais.
