# 0023 — Idade da ação offline medida no aparelho

Data: 2026-10-04. Ajusta a decisão 5 da ADR 0022. Esquema inalterado (V21).

## Contexto

A ADR 0022 passou a recusar ações offline novas de outro dia usando `criadoNoAparelhoEm`, com até cinco minutos de adiantamento. Esse instante vem do relógio do celular. Como todo check-in e lançamento da equipe passa pela fila, mesmo com sinal, um celular adiantado mais de cinco minutos, ou com a data errada, teria **todas** as ações recusadas.

## Decisão

O app envia também `enviadoNoAparelhoEm`, lido do mesmo relógio no momento do envio (a cada tentativa). O servidor calcula a idade da ação (envio menos criação), aplica essa idade ao próprio relógio e confere o dia em São Paulo com o resultado. Um erro constante no relógio do aparelho se cancela na diferença.

- Idade negativa (relógio acertado entre criar e enviar) conta como zero: a ação é recente.
- Sem `enviadoNoAparelhoEm` (app ainda não atualizado), vale a regra anterior, com o instante informado e cinco minutos de tolerância.
- UUID já confirmado continua resolvido antes dessa regra.

## Consequências

Celular com relógio errado continua operando. Uma ação guardada antes da meia-noite e enviada depois segue recusada, como na ADR 0022. A regra continua sendo uma proteção operacional, não um controle contra fraude: o app da equipe é autenticado e o servidor confia nos dois instantes que ele informa. O horário financeiro e a ordenação continuam sendo os do servidor.
