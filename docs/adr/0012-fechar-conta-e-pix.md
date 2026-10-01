# ADR 0012 — Fechar conta pelo cliente e Pix estático

- **Status:** aceito
- **Data:** 2026-10-01

## Contexto

O cliente precisa fechar a conta sozinho, sem fila no balcão, e o dono precisa saber **quem vai pagar, quanto e como**. O sistema **nunca toca no dinheiro** (plano, seção de princípios): o Pix é um QR estático com a chave do barraqueiro, e quem confirma o recebimento é uma pessoa no quiosque. O checkpoint 5.1 entrega o lado do cliente e a base de dados dos pagamentos; o aviso ao dono e a confirmação ficam para o 5.2.

## Decisão

**Máquina de estados da comanda**
- `ABERTA → AGUARDANDO_PAGAMENTO` ao fechar a conta; de volta a `ABERTA` ("Voltar e pedir mais") **só enquanto nenhum pagamento foi confirmado**. `FECHADA` vem com a confirmação do quiosque (5.2). Fora de `ABERTA`, o servidor recusa pedido novo (já era assim desde o 3.3; a mensagem agora aponta o caminho de volta).
- Toda ação (fechar, "já paguei", voltar) **trava a linha da comanda** (`ComandaRepository.travar`), como o pedido: um toque duplo, ou o quiosque confirmando ao mesmo tempo, nunca se atropelam.
- **Fechar de novo troca a forma de pagamento** (Pix → dinheiro, por exemplo) sem passar por "voltar". Repetir a mesma ação é inofensivo (200).
- Recusas com 409 e um código (`conta-zerada`, `pix-indisponivel`, `comanda-bloqueada`, `comanda-fechada`, `comanda-cancelada`, `barraca-inativa`, `pagamento-confirmado`, `sem-pix`); troco inválido é 400. **Conta zerada não fecha**: não há o que pagar.

**Saldo passa a descontar os pagamentos**
- `PedidoService.saldoCentavos` = itens não cancelados **menos** pagamentos confirmados. O limite de saldo do dono (3.3) passa a valer sobre o que falta pagar, então "pagar uma parte" realmente libera novos pedidos.

**O que o cliente escolhe fica na comanda** (migração V11)
- `metodo_pagamento_pretendido` (Pix, dinheiro ou cartão), `troco_para_centavos` (só dinheiro, opcional, de R$ 0,01 até R$ 10.000,00 e **nunca menor que a conta**) e `aviso_pagamento_em`. Dinheiro e cartão avisam no próprio toque de fechar; **no Pix o aviso é o "já paguei"**, depois de pagar no banco. A aba Pagamentos do 5.2 ordena por essa hora.
- Tabela `pagamento` criada agora, ainda sem escrita pela aplicação (a confirmação é do 5.2): valor sempre positivo, dinheiro com `valor_recebido` (o troco é recebido − valor), quem confirmou (dono **ou** atendente, nunca os dois) e **FKs compostas** `(comanda, barraca)`, `(usuário, barraca)` e `(atendente, barraca)`: um pagamento nunca cruza barracas, nem no banco.

**BR Code Pix estático, nosso**
- Gerado no servidor (`BrCodePix`), sem biblioteca: campos EMV (id + tamanho + valor), CRC16-CCITT-FALSE (polinômio `0x1021`, início `0xFFFF`) calculado **incluindo o `6304`**, valor com ponto e duas casas, e as travas do padrão (nome até 25, cidade até 15, chave até 77, txid alfanumérico até 25).
- **Conferido contra o exemplo do manual do Banco Central:** chave `123e4567-e12b-12d1-a456-426655440000`, "Fulano de Tal", "BRASILIA", txid `***` dá exatamente `…63041D3D`. (Sessão de 2026-10-01: o payload completo foi conferido numa busca na web, que o atribui ao Manual de Padrões para Iniciação do Pix do BCB; o PDF do manual não foi aberto na íntegra.)
- **O valor do código é o saldo na hora**; o `txid` vem da comanda (os 25 primeiros caracteres hexadecimais do id), o que permite casar o extrato do banco com a conta. O código é gerado a cada consulta, então muda sozinho se o saldo mudar.
- Nome e cidade do recebedor já são guardados em ASCII pelo cadastro do Pix (2.1); aqui só se confere o que o padrão exige.
- **O QR é desenhado no celular do cliente** (SVG, mesma biblioteca do QR de check-in, ADR 0007), junto com o botão "Copiar código Pix" e um campo "Ver o código" para copiar à mão se o navegador bloquear a área de transferência.

**API do cliente** (sem sessão e sem cookies, como o resto de `/api/c/{token}`; a comanda vem só do token)
- `GET /conta` (extrato e situação), `POST /conta/fechar`, `POST /conta/pix-pago`, `POST /conta/reabrir`. Limite de 20 ações por minuto por comanda.

## Consequências

- **O "já paguei" é só um aviso.** Nada prova que o Pix saiu: por isso o 5.2 tem o aviso "confira no app do banco antes de confirmar". Um cliente pode apertar "já paguei" sem pagar; o custo é uma conferência a mais do dono.
- **A confirmação do pagamento e o fechamento (`FECHADA`) ainda não existem**: no 5.1 o app do quiosque **ainda não mostra** que a comanda está aguardando pagamento (a lista de Clientes não exibe o status). O aviso ao dono (SSE e push) vem no 5.2. Enquanto isso, a tela "Conta" do cliente consulta o servidor a cada 15 s.
- Se o quiosque não tem Pix configurado, a página só oferece dinheiro e cartão.
- O `valor` de um Pix estático pode ser editado pelo cliente no app do banco. Quem confere é o dono, olhando o valor recebido.
