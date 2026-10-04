# Correções da revisão externa

Data: 2026-10-04. Branch `correcoes-revisao-externa`, a partir de `correcoes-auditoria-geral` (PR #27). Banco continua em V21.

## 1. O que foi feito

Uma segunda revisão por outro modelo listou 7 achados e uma falha de cobertura na CI. Cada um foi conferido no código: 6 procedem e 1 foi descartado. Esta branch corrige os três mais graves e um bug encontrado na validação da auditoria geral:

| Achado | Resultado |
|---|---|
| Perfil `prod` exigia esquema V19 fixo | O esquema exigido passa a ser a maior migração embutida na imagem. Com V20/V21 o app não iniciava, e o `implantar.sh` migraria o banco antes de descobrir isso |
| Trocar PIN durante uma desativação reativava o atendente | Trocar PIN, ativar/desativar, revogar sessões e desbloquear travam a linha (`findForUpdate`), como o login já fazia |
| Pagamento incerto apagado em qualquer 4xx | Só é esquecido quando o servidor avaliou e recusou. Sessão vencida (401), CSRF, 408 e 429 mantêm o id para repetir o mesmo pagamento |
| Celular com relógio errado tinha todas as ações recusadas | O servidor usa a idade da ação medida no aparelho ([ADR 0023](../adr/0023-idade-da-acao-no-aparelho.md)) |

## 2. Decisões

- [ADR 0023](../adr/0023-idade-da-acao-no-aparelho.md): o app envia `enviadoNoAparelhoEm`. O servidor confere o dia pela idade da ação (envio menos criação), aplicada ao relógio dele. Apps sem o campo seguem a regra da ADR 0022.
- O esquema exigido em produção vem das migrações da própria imagem. Um teste garante que ele coincide com `deploy/compatibilidade.json`.

## 3. Como rodar e testar

Na raiz, com Docker ativo e JDK 21:

```powershell
$env:JAVA_HOME='C:/Program Files/Amazon Corretto/jdk21.0.12_8'
./gradlew.bat ktlintCheck build
```

No diretório `web-staff`: `npm run build` (lint, testes, TypeScript e bundle).

## 4. Resultados automatizados

- `ktlintCheck` e backend: **497 testes**, zero falhas, erros ou ignorados (eram 492; 5 novos).
- `web-staff`: **19 testes** (eram 18), lint, TypeScript e bundle aprovados.
- Os testes novos foram conferidos ao contrário: com cada correção desfeita temporariamente, os quatro testes de backend correspondentes **falharam**; com as correções, passaram.
- Playwright e ensaios Bash não foram repetidos nesta branch.

Testes novos: `ProducaoValidationTest` (esquema da imagem igual à última migração e ao manifesto), `FlywayMigrationTest` (banco migrado passa na conferência do perfil `prod`), `EquipeTest` (troca de PIN espera a desativação em andamento e não a desfaz), `FluxosAuditoriaTest` (idade da ação, relógio adiantado, data errada e pedido de ontem pela API) e `web-staff/tests/api-erros.test.cjs` (quais erros esquecem o pagamento incerto).

## 5. Validação manual

[Roteiro de cinco passos](../testar-localmente.md#correções-da-revisão-externa). Os testes físicos ficam para a entrega final, conforme as regras do projeto.

## 6. Limitações e riscos

Achados que procedem, mas ficaram para depois por baixo risco:

- **Push pendente após trocar de barraca (P3).** A entrega aponta para o id da inscrição, que é reaproveitado quando o mesmo aparelho entra em outra barraca. Um aviso de A gerado pouco antes (guarda-sol, valor, nº do pedido; sem nome; validade de até 10 minutos, ou 60 no aviso genérico de problema) pode chegar ao aparelho já em B. É o mesmo aparelho que recebia os avisos de A; desativar ou revogar o atendente apaga a inscrição.
- **Duplicação após a janela de conciliação (P3).** A associação entre o id do cliente e o pedido lançado pela equipe não é gravada. Um reenvio do cliente mais de 60 minutos depois cria outro pedido. O caso mais provável é o primeiro envio nunca ter chegado e o cliente reabrir a página depois de uma hora.
- **QR antigo após o prazo do extrato (P3).** A consulta excepcional pelo link anterior não aplica os 7 dias. Exige o token antigo e o UUID do pedido, que só o aparelho que fez o pedido conhece.

Achado descartado: contar itens cancelados na conciliação é o comportamento correto. Sem eles, coco e pastel deixariam de bater com o pedido da equipe que teve o pastel cancelado, e o servidor criaria um pedido novo, cobrando o coco duas vezes.

A CI ainda roda o E2E no perfil `local`. O teste novo cobre a conferência de esquema do perfil `prod` contra um banco migrado, mas não as demais checagens de produção (contas sem privilégio, por exemplo).

## 7. Próximo passo

Validar este checkpoint junto com o PR #27. Depois, decidir se os três itens P3 entram antes do piloto.

⏸️ Aguardando validação. Responda "APROVADO CORREÇÕES DA REVISÃO EXTERNA" ou "AJUSTAR CORREÇÕES DA REVISÃO EXTERNA: ...".
