# Relatório — Revisão 116 (correções da validação geral e checkpoint 6.3)

- **Data:** 2026-10-03. **Branch:** `revisao-116` (a partir da `main` com o PR #8).
- **Escopo:** commitar o lote 111–115, corrigir o que a validação geral achou e fechar o checkpoint 6.3 da Fase 6 (última sincronização por aparelho e E2E offline obrigatórios). Sem merge, push de imagem ou implantação.

## 1. O que foi feito

- **Lote 111–115 (commitado):** limite de saldo explicado no painel, exceções da equipe (pausa e limite) avisadas e registradas em `historico_acao`, **Pagamentos → Continuar conta** após pagamento parcial, controle de devoluções e preparação do piloto. Detalhes em [111–115](revisao-111-115.md). Ajuste de estilo: imports no lugar de nomes completos e resposta do lançamento com view tipada (`PedidoLancadoView`).
- **Fila offline da equipe:**
  - o limite de 200 ações passa a valer **por barraca/autor**, como a ADR 0020 já dizia (antes contava todos os contextos do aparelho);
  - registro corrompido no IndexedDB é descartado sem impedir a próxima ação;
  - reenvio com **espera crescente** (15 s, dobrando até 2 min, ±20%), zerada quando o sinal volta.
- **6.3, última sincronização (item 4):** o app informa a cada rodada quantas ações ainda estão no aparelho; o painel **Equipe → Aparelhos da equipe** mostra quem, tipo do aparelho, última sincronização e pendências, com destaque para ação recusada ou parada há mais de 15 minutos. Migração **V20**, retenção de 30 dias.
- **6.3, E2E obrigatórios (item 5):** `e2e/tests/offline-convergencia.spec.ts` — 10 itens lançados sem sinal chegam exatamente uma vez; dois aparelhos sem sinal na mesma comanda convergem sem duplicar (com queda e volta no meio); o dono vê os aparelhos.
- **Documentação:** `ESTADO_DO_PROJETO.md` voltou a ter a seção 1 de regras que o CLAUDE.md cita; README e deploy atualizados para V20; checklist da Fase 6 corrigido; roteiro manual em [testar localmente](../testar-localmente.md#revisão-116-checkpoint-63).

## 2. Decisões

[ADR 0021](../adr/0021-sincronizacao-dos-aparelhos-e-reenvio.md): informe por aparelho, espera crescente, limite por sessão e **IndexedDB direto em vez de Dexie** (a especificação citava Dexie; a troca está registrada).

**Para você confirmar:**
1. A troca de Dexie por IndexedDB direto.
2. **Continuar conta** (111–115) permite voltar de `AGUARDANDO_PAGAMENTO` para `ABERTA` depois de pagamento parcial, o que a seção B3 do plano não previa (ela só permitia sem pagamento confirmado). Registrado na ADR 0009.

## 3. Como rodar e testar

```powershell
$env:JAVA_HOME='C:/Program Files/Amazon Corretto/jdk21.0.12_8'; $env:GRADLE_USER_HOME='C:/Users/james/.gradle'
./gradlew.bat ktlintCheck :backend:test -PskipFrontendBuild --console=plain
cd web-staff; npm run lint; npm test; npx tsc --noEmit
# E2E contra a imagem (docker build -t mesamar-revisao:116 .)
$env:E2E_BASE_URL='http://localhost:18082'; $env:E2E_ADMIN_PASSWORD='<senha do container descartável>'
./e2e/node_modules/.bin/playwright.cmd test --config=e2e/playwright.config.ts
```

## 4. Resultado dos testes

- Backend completo antes da 6.3: **477 testes, zero falhas** e ktlint aprovado; depois da 6.3, os testes novos (`SincroniaAparelhoTest`, 5, incluindo isolamento, validação, retenção) e os afetados (`EquipeTest`, upgrade V17 → V20) aprovados.
- Frontends: lint, TypeScript e testes unitários aprovados; a equipe ganhou 3 testes (limite por sessão, registro corrompido, espera crescente).
- E2E: 52 de 53 na primeira passada e a falha passa isolada (seção 7). **A CI remota ainda não rodou nesta branch.**

## 5. Roteiro de validação manual

[Revisão 116 em testar localmente](../testar-localmente.md#revisão-116-checkpoint-63).

## 6. Limitações e riscos

- O dono vê só a **quantidade** de ações guardadas no aparelho, não o conteúdo.
- Um aparelho que nunca alcança o servidor depois do login não aparece na lista.
- O "primeiro evento terminal vence" depende das regras de estado já existentes no servidor (entregue/cancelado são finais); não foi criado teste novo de dois aparelhos disputando entrega/cancelamento.
- A **migração V20** vai no primeiro deploy: backup validado antes ([deploy](../deploy.md)).
- Pendências externas (S3, monitores, SMTP, aparelhos físicos, publicação) continuam como em [ESTADO_DO_PROJETO.md](../../ESTADO_DO_PROJETO.md).

## 7. Resultado da matriz E2E

Matriz completa contra a imagem local `mesamar-revisao:116` (banco migrado para V20): **53 cenários, 52 aprovados e 1 falha** (log `backend/build/e2e-matriz-116.log`). Os 6 cenários novos de `offline-convergencia` (2 por projeto) aprovaram.

A falha foi `painel.spec.ts:162` no projeto iPhone/WebKit, na **preparação** (`criarAna`: o clique em "Adicionar Água de coco" não registrou e o teste esperou até o limite de tempo), antes de qualquer fluxo alterado. Reexecutado isolado 3 vezes, **passou 3 de 3**. Trato como intermitente do WebKit; a CI repete uma vez (`retries: 1`). Se voltar a aparecer na CI, vira item de correção.

## 8. Proposta para a próxima fase

Aprovar o 6.3 e seguir para a preparação do piloto ([lista de execução](../preparacao-piloto.md)). Antes, vale um teste de dois aparelhos disputando entrega e cancelamento do mesmo pedido.

⏸️ Aguardando validação. Responda "APROVADO FASE 6.3" ou "AJUSTAR FASE 6.3: ..."
