# Relatório — Fase 5: fechamento, Pix e pagamentos

- **Branch:** `fase-4-fila` (o 5.1 foi feito em cima da Fase 4, ainda no PR #5; ver a nota em "Antes de aprovar")
- A Fase 5 está dividida em 3 checkpoints, aprovados um por vez:
  - [ ] **5.1 — Fechar conta pelo cliente e Pix** ← checkpoint atual
  - [ ] 5.2 — Aviso "indo pagar", aba Pagamentos e confirmação
  - [ ] 5.3 — Relatório do dia e anonimização LGPD
- Decisões de arquitetura: [ADR 0012](../adr/0012-fechar-conta-e-pix.md)

---

## Checkpoint 5.1 — Fechar conta pelo cliente e Pix

- **Data:** 2026-10-01
- **Verificado localmente:** ktlint, **323 testes de backend** (303 do 4.2 + 20 novos), build do front e **10 E2E** no tamanho de celular, com a imagem Docker construída do zero. **O CI do GitHub ainda não rodou** (nada foi enviado): depende do push.

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

### 6. Antes de aprovar

- O **PR #5 (Fase 4)** continua em rascunho e o 5.1 está nele. Se quiser, separo o 5.1 numa branch `fase-5-pagamento` a partir da `fase-4-fila`.
- Nada foi enviado ao GitHub nesta sessão. Os commits estão só locais.
