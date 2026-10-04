# Revisão 111–115

Data: 2026-10-03. Escopo limitado aos cinco pontos solicitados. Sem nova migração, merge ou implantação.

## Alterações

111. O painel Regras explica que o limite considera saldo anterior, permite o pedido que ultrapassa o valor e bloqueia o próximo. Mostra exemplo R$ 140 + R$ 80 com limite R$ 150, estimativa conservadora com 60 unidades e maior preço, teto técnico de pedido e ausência de teto de saldo na exceção da equipe. A regra financeira foi preservada.

112. Aviso permanente no lançamento/reenvio da equipe. Registro transacional de uso efetivo de pausa e/ou limite em `historico_acao`, com autor, horário, saldo anterior e limite. Reenvio não duplica o registro. A confirmação informa quais exceções foram usadas. Auditoria por consulta operacional restrita documentada, com retenção existente de 90 dias; condições do servidor na recepção, inclusive para offline. Não foi criada tela de histórico.

113. **Pagamentos → Continuar conta** para pagamento parcial confirmado em conta aguardando pagamento de hoje. Preserva pagamentos/saldo, limpa intenção/aviso anterior, muda para ABERTA e publica atualização. Trava por comanda, autoria via histórico existente, isolamento e reenvio idempotente enquanto aberta. Conta bloqueada, encerrada, cancelada, de outro dia ou sem pagamento parcial elegível é recusada. Cliente recebe orientação direta; limite/pausa permanecem.

114. Ausência de estorno/devolução mantida. [Controle do piloto](../piloto-devolucoes.md), modelo CSV, conciliação, apuração semanal, denominador e critérios propostos de priorização. Nenhuma frequência real foi inventada e nenhum pagamento é alterado por esse controle.

115. [Preparação operacional](../preparacao-piloto.md) com responsáveis por função, critérios de conclusão/evidências, treinamento e [roteiro manual](../testar-localmente.md#revisão-111115). Por orientação expressa do usuário, SMTP/suporte/deploy/monitores reais foram adiados; exemplos fictícios identificados apenas na documentação. Treinamento e validação em aparelhos reais ainda precisam ser executados. Validações de produção existentes preservadas.

## Verificação

- Builds dos dois frontends: lint, TypeScript, 14 testes do cliente e 11 da equipe aprovados.
- Backend: 42 testes selecionados aprovados (`ContaDoClienteApiTest`, `LancarPedidoApiTest`, `PagamentosApiTest`), incluindo limite, concorrência, exceções, pagamento parcial, autoria e isolamento.
- Nova verificação após incluir a confirmação visível das exceções: `ktlintFormat`, `ktlintCheck` e 34 testes de `LancarPedidoApiTest`/`PagamentosApiTest` aprovados, com novo build da equipe (lint, TypeScript e 11 testes aprovados). `BUILD SUCCESSFUL` nas duas execuções; XMLs sem falhas/erros.
- `git diff --check` aprovado. Nenhuma alteração fora do escopo apareceu após a formatação.
- Roteiro manual e configurações externas não executados nesta revisão. A CI remota e a matriz E2E completa não foram executadas.

O Gradle/Testcontainers foi executado com acesso autorizado ao cache local e Docker, depois de o sandbox impedir a primeira tentativa. Sem alteração de firewall, SSH, DNS ou Cloudflare.
