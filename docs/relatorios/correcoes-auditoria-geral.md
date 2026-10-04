# Correções da auditoria geral

Data: 2026-10-03. Branch `correcoes-auditoria-geral`; base `main` (`13f9d28`, já com a revisão 116). Implementação local sujeita à validação do checkpoint. Banco resultante: V21.

## 1. O que foi feito

Correções dos dez bugs confirmados pela [auditoria](auditoria-geral-2026-10-03.md), com os dois fluxos adicionais explicitamente autorizados:

| Achado | Resultado |
|---|---|
| 1. Digest só no processo de deploy | Imagem saudável persistida atomicamente no arquivo de ambiente; falha preserva o original |
| 2. QR revogado durante a escrita | Token original conferido com a comanda travada; nenhuma nova escrita com link revogado |
| 3. Resposta velha substituindo aba | Todas as abas invalidam respostas da geração anterior |
| 4. Troca de operador apagando informe | Chave inclui autor; cada fila conserva seu informe no mesmo aparelho |
| 5. Heartbeat escondendo pendência antiga | Atenção usa a criação da pendência mais antiga, separada do último contato |
| 6. Produto repetido ultrapassando 20 unidades | API recusa linhas duplicadas antes de gravar |
| 7. Lançamento com 31 produtos travado | Tela limita 30 produtos/60 unidades e recupera controles em falha síncrona |
| 8. Guia de recuperação obsoleto | Arquivo antes da opção, treino com nome único, recusa de flags inválidas antes do banco |
| 9. Medições presas no cache | Script tem URL com hash nos dois fronts e service workers; URL simples revalida |
| 10. Cadastro concorrente retornando 500 | Upsert atômico preserva um lead, id, criação e origem |
| 11. Ação offline chegando no dia seguinte | UUID novo de outro dia vira conflito sem novos registros; reenvio confirmado converge |
| 12. Pedido não pago sem revisão na equipe | Pagamentos abre itens e cancela item/pedido com motivo, autoria e saldo recalculado |

A investigação do toque perdido encontrou uma troca de DOM entre `pointerdown` e `pointerup`. Atualizações automáticas agora aguardam o fim do toque e redesenham com o carrinho atual; callbacks são descartados ao encerrar a página.

## 2. Decisões

[ADR 0022](../adr/0022-correcoes-auditoria-geral.md): V21 preserva dados V20 e inicializa a idade legada com o último contato. Novas ações offline usam o instante original do aparelho e o dia em São Paulo; horário financeiro permanece do servidor. Requisições legadas sem instante continuam compatíveis: atualizar o app é necessário para a nova proteção. Devoluções/estornos de pagamentos continuam conforme o [controle do piloto](../piloto-devolucoes.md).

## 3. Como rodar e testar

Na raiz, com Docker ativo e JDK 21:

```powershell
$env:JAVA_HOME='C:/Program Files/Amazon Corretto/jdk21.0.12_8'
./gradlew.bat ktlintCheck build
python .github/scripts/testar-imagem.py
python .github/scripts/testar-alertas.py
```

Nos diretórios `web-staff` e `web-customer`: `npm run build` (lint, testes, TypeScript e bundle). Para E2E, subir a aplicação local com banco descartável e, no diretório `e2e`:

```powershell
$env:E2E_BASE_URL='http://localhost:8089'
$env:E2E_ADMIN_PASSWORD='correcoes-local-ficticia'
npx playwright test
```

A senha acima é **fictícia**, usada apenas no ambiente descartável desta validação. O app local usa a mesma senha administrativa. Nenhuma credencial de produção foi usada.

Scripts de operação, em Bash/Linux:

```bash
python3 .github/scripts/testar-imagem.py
MESAMAR_OPERACAO_TESTE=1 bash .github/scripts/testar-implantacao.sh
# SOMENTE dentro de um PostgreSQL descartável com os arquivos em /workspace:
MESAMAR_OPERACAO_TESTE=1 bash /workspace/.github/scripts/testar-backup-no-banco.sh /workspace
```

O ensaio de implantação usa adapter sem acesso ao Docker real. O ensaio de backup usa `pg_dump`, `pg_restore` e PostgreSQL reais num container descartável.

## 4. Resultados automatizados

- `ktlintCheck build`: **aprovado**; **492 testes de backend**, zero falhas/erros/ignorados. Inclui revogação, concorrência de leads, isolamento, cancelamento/saldo, dia offline, idade das filas e upgrade V17 → V20 → V21 com dados existentes.
- Frontends: **18 testes da equipe + 16 do cliente**, todos aprovados; lint, TypeScript e bundles aprovados.
- Python: **3 testes da persistência da imagem + 1 teste dos alertas independentes**, aprovados.
- Bash: ensaio de quatro caminhos da implantação (saúde, falha de migração, subida e esquema), Compose independente e argumentos inválidos de restauração, aprovado. Ensaio com PostgreSQL real: hashes, totais financeiros, checksums, retenção, exclusão mútua e retorno ao banco anterior em falha, aprovado.
- Playwright: **59 cenários aprovados**, zero falhas, ignorados ou retries; Android/Chromium, iPhone/WebKit e teclado. Inclui as quatro corridas de navegação em cada projeto, revisão/cancelamento de item/pedido não pago, Pix e recuperação offline. Execução final: 12,8 minutos contra cópia fixa do mesmo JAR validado no build. A execução intermediária afetada pela substituição do JAR em uso foi interrompida e não compõe este resultado.

Evidências locais em `backend/build/correcoes-*.log`, XMLs em `backend/build/test-results/test`, e relatório Playwright em `e2e/playwright-report`. Build e runtime conferidos pelo mesmo SHA-256: `526a86df886b24b4850b17a494ba47472195b72408de086309f224ec1303c458`. O processo do app e o container/volume PostgreSQL descartáveis foram encerrados após os testes. Logs permanecem fora do Git.

## 5. Validação manual

[Roteiro de nove passos](../testar-localmente.md#correções-da-auditoria-geral): revogação, corrida de abas, limites, aparelho compartilhado, virada do dia, revisão antes de receber, cache/toque, implantação/restauração e cadastro concorrente. Os testes físicos ficam para a entrega final conforme as regras do projeto.

## 6. Limitações e riscos

- V21 precisa de backup validado e ensaio antes de produção. Informe pendente do banco V20 tem idade inicialmente aproximada pelo último contato; o app atualizado informa a idade original.
- O servidor só conhece a última fila informada. Ações criadas totalmente offline ficam visíveis ao dono depois de um informe chegar. Filas locais expiram em três dias; informes do painel têm retenção de 30 dias.
- SMTP, suporte real, deploy, S3 e monitores reais continuam adiados por orientação do usuário, com dados fictícios identificados. Os ensaios locais não comprovam entrega real de alerta/e-mail, nem RPO/RTO do provedor.
- Safari offline, instalação e acessibilidade em celulares físicos permanecem na validação final. O Playwright WebKit não oferece service workers equivalentes ao Safari físico.
- Alterações locais; sem merge em `main` ou implantação. CI remoto e publicação não foram acionados.

## 7. Próximo passo

Validar este checkpoint. Depois, substituir dados fictícios e executar a [preparação operacional](../preparacao-piloto.md), sem iniciar o piloto antes das evidências reais.

⏸️ Aguardando validação. Responda "APROVADO CORREÇÕES DA AUDITORIA" ou "AJUSTAR CORREÇÕES DA AUDITORIA: ...", conforme a regra de checkpoints em `ESTADO_DO_PROJETO.md`.
