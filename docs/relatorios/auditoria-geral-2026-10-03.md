# Auditoria geral — 3 de outubro de 2026

Base analisada: branch `revisao-116`, commit `37f2003`, esquema V20. Este documento preserva os achados e resultados da análise original. As correções posteriormente autorizadas estão no [relatório das correções](correcoes-auditoria-geral.md), com esquema V21; a aprovação do checkpoint continua pendente.

Foram encontrados **10 problemas confirmados**, sendo um de prioridade P1 e nove P2. Há também uma falha intermitente de E2E ainda sem causa determinada e dois gaps de fluxo que precisam de decisão do produto. As reproduções usaram dados fictícios e bancos locais descartáveis.

P1 significa corrigir antes de depender do procedimento em produção; P2 significa defeito funcional que merece correção e cobertura de regressão. Nenhum achado abaixo afirma que ocorreu um incidente em produção.

## Achados prioritários

| # | Prioridade | Problema | Consequência |
| --- | --- | --- | --- |
| 1 | P1 | Digest implantado não vira configuração persistente | Uma operação posterior pode recriar a aplicação com outra versão, incompatível com o banco migrado |
| 2 | P2 | QR revogado não é reconferido depois da trava da comanda | Requisição iniciada antes da troca pode gravar um pedido novo depois da revogação |
| 3 | P2 | Resposta antiga sobrescreve a aba nova | A navegação indica Fila, mas o conteúdo volta para Clientes |
| 4 | P2 | Troca de operador apaga o acompanhamento das pendências anteriores | O dono vê zero enquanto ainda há ações guardadas no aparelho |
| 5 | P2 | Heartbeat reinicia o prazo de atenção das pendências | Uma ação parada por horas pode continuar sem destaque |
| 6 | P2 | Limite de 20 por produto pode ser ultrapassado | A API aceita 60 unidades do mesmo produto divididas em três linhas |
| 7 | P2 | Selecionar 31 produtos trava o lançamento da equipe | Não há envio nem mensagem tratada; o botão fica em “Lançando…” |
| 8 | P2 | Roteiro de restauração usa interface antiga/incorreta | O comando de recuperação falha e o treino aponta banco/opção inexistentes |
| 9 | P2 | JavaScript sem hash recebe cache imutável de um ano | Atualizações do script de medições podem não chegar aos aparelhos |
| 10 | P2 | Cadastro simultâneo do mesmo WhatsApp falha | Uma inscrição válida pode terminar em erro de integridade/HTTP 500 |

### 1. Persistir a versão efetivamente implantada

**Local:** `deploy/implantar.sh:25`, `deploy/implantar.sh:35`, `deploy/docker-compose.prod.yml:40` e `deploy/docker-compose.prod.yml:56`.

`implantar.sh` recebe o digest por argumento e exporta `MESAMAR_IMAGE` apenas no próprio processo. A `.env` não é atualizada. O digest é registrado em `imagem-implantada.txt`, mas nenhum procedimento lê esse arquivo para selecionar a imagem. A restauração usa novamente o Compose e, portanto, a imagem persistida na `.env`.

**Reprodução:** configurar digest A na `.env` e passar B ao script. Durante a execução, o Compose escolhe B; no próximo comando independente, escolhe A. A seleção A → B → A foi conferida com `docker compose config` e valores fictícios, sem baixar nem executar essas imagens.

**Impacto:** se B migrou o banco para um esquema não aceito por A, uma recriação do serviço na recuperação ou manutenção pode impedir a aplicação de iniciar. O script de compatibilidade da implantação não protege esses comandos independentes.

**Correção proposta:** manter uma fonte persistente da imagem ativa, atualizada após implantação saudável e consumida pelos procedimentos de operação/recuperação. Se a opção for exigir edição manual da `.env`, torná-la uma etapa explícita e verificável do procedimento. Testar implantação de B seguida de uma operação independente, incluindo recuperação com a imagem compatível com o backup.

### 2. Validar a credencial dentro da transação de escrita

**Local:** `backend/src/main/kotlin/br/com/mesamar/cliente/ClienteController.kt:359` e `backend/src/main/kotlin/br/com/mesamar/pedido/PedidoService.kt:138`. O mesmo padrão aparece em `pagamento/FechamentoService.kt:100`, `:139` e `:158`.

O controller resolve o token em uma consulta e entrega a entidade lida ao serviço. O serviço trava a comanda por ID e reconfere estado/saldo, mas não verifica se o token que autorizou a chamada ainda é o token atual.

**Reprodução confirmada:** ler a comanda pelo QR antigo; trocar o QR e concluir essa transação; conferir que o token antigo já não é encontrado; executar a criação com a entidade anteriormente lida e um ID de pedido novo. O serviço aceita e grava o novo pedido. Isso reproduz a ordem possível quando a requisição espera a trava enquanto a equipe troca o QR.

**Limite do achado:** chamadas iniciadas com o token antigo depois da troca são recusadas normalmente. A exceção deliberada para consultar o resultado de um pedido já enviado pelo link anterior deve continuar funcionando; o problema é permitir uma nova escrita com autorização vencida.

**Correção proposta:** conferir a credencial original contra a comanda travada na mesma transação que escreve. Cobrir troca de QR concorrente com criação, fechamento e reabertura. A criação foi reproduzida; os demais métodos têm o mesmo padrão identificado por leitura do código.

### 3. Invalidar telas antigas em toda troca de aba

**Local:** `web-staff/src/main.ts:119`, `web-staff/src/views/clientes.ts:47`, `web-staff/src/views/fila.ts:23` e `web-staff/src/views/pagamentos.ts:19`.

Clientes chama `abrirTela` e só aplica a resposta se a geração continuar atual. Fila e Pagamentos não avançam essa geração ao abrir. Por isso, a proteção da resposta antiga ainda retorna verdadeiro após navegar para essas abas.

**Reprodução confirmada no Chromium:** segurar a resposta de `/api/staff/clientes`; abrir Fila e conferir seu título; liberar a resposta. O título muda para Clientes, embora o URL continue em `#/fila`. Não depende de WebKit nem de uma condição particular do teste original.

**Evidência adicional:** a matriz E2E falhou no cenário de fila do iPhone. O título Fila chegou a aparecer, mas o snapshot posterior mostrava Clientes e o teste não encontrava “Ao vivo”.

**Correção proposta:** invalidar a geração anterior no ponto central de navegação ou garantir isso em todas as abas. Cobrir respostas atrasadas de Clientes/Cardápio ao abrir Fila/Pagamentos, além de callbacks de check-in e lançamento que terminem após a navegação.

### 4. Preservar o acompanhamento ao trocar de operador

**Local:** `web-staff/src/api.ts:288`, `web-staff/src/api.ts:302`, `web-staff/src/fila-offline.ts:50` e `backend/src/main/kotlin/br/com/mesamar/sincronia/SincroniaAparelho.kt:82`.

O identificador do aparelho é compartilhado pelos operadores. As filas pertencem à combinação barraca/autor e o informe conta apenas a fila do operador atual. No servidor, a chave é barraca/aparelho e o upsert substitui autor e contagens.

**Reprodução confirmada:** A tem uma ação guardada; B entra no mesmo aparelho e sua fila está vazia. A fila de A continua existindo no IndexedDB, mas B informa zero com o mesmo ID de aparelho e substitui a linha de A no painel. Foram conferidos tanto o isolamento das filas locais quanto a substituição da linha no PostgreSQL.

**Impacto:** o acompanhamento não permite perceber que há check-ins/pedidos de outro operador ainda sem envio. Se A não voltar a abrir a sessão, essas ações podem atingir o prazo já definido de três dias e expirar.

**Correção proposta:** preservar o estado por aparelho/autor ou informar separadamente a existência de ações de outras sessões, mantendo a separação de acesso aos dados. Não reenviar uma ação de A como se fosse de B.

### 5. Separar contato com o servidor de progresso da fila

**Local:** `web-staff/src/api.ts:411`, `web-staff/src/fila-offline.ts:105` e `backend/src/main/kotlin/br/com/mesamar/sincronia/SincroniaAparelho.kt:93` e `:120`.

O destaque de atraso usa a última hora do informe. Se o GET de sessão funciona e o POST da ação recebe uma falha temporária, a drenagem retorna mantendo a ação, mas a rodada ainda envia um novo informe e atualiza a hora.

**Reprodução confirmada:** manter uma ação pendente por duas horas e informar novamente a mesma contagem. Antes do informe ela pede atenção; depois dele, `atencao` fica falso, sem que a ação tenha sido enviada. A reprodução local da fila confirmou que um 503 temporário preserva a ação sem propagar erro de drenagem.

**Correção proposta:** registrar separadamente o último contato, a idade da pendência mais antiga e/ou a última confirmação de uma ação. O destaque de pendência parada deve usar a idade/progresso da fila, e não apenas o heartbeat. Testar GET disponível e POST 429/503 por mais de 15 minutos.

### 6. Alinhar o limite por produto entre painel, frontend e API

**Local:** `backend/src/main/kotlin/br/com/mesamar/pedido/PedidoService.kt:381`, `web-shared/src/validacao.ts` e `backend/src/main/resources/templates/painel/regras.html:34`.

O painel anuncia até 20 unidades por produto. O contrato dos frontends recusa IDs de produto duplicados, mas o backend aplica o limite por linha, sem unicidade nem soma por produto.

**Reprodução confirmada via HTTP:** enviar três linhas com o mesmo produto, quantidade 20 em cada uma. A API retorna 201 e total correspondente a 60 unidades; no cenário de teste, 60 águas de coco de R$ 12 resultaram em R$ 720.

**Limite do achado:** o teto global de 60 unidades continua funcionando e a estimativa conservadora de exposição com 60 vezes o maior preço continua válida. A inconsistência está no limite anunciado por produto.

**Correção proposta:** recusar produtos duplicados ou agregar as quantidades antes de conferir o teto por produto. Se múltiplas linhas forem uma decisão deliberada, ajustar a regra e o texto de forma coerente; atualmente os frontends e a API discordam.

### 7. Tratar o limite de linhas antes de travar o formulário

**Local:** `web-staff/src/views/lancar.ts:139`, `:172` e `:57`; `web-staff/src/pendencias.ts:33`.

A tela permite selecionar mais de 30 produtos. Ao clicar, marca `enviando = true` e desabilita os controles; em seguida, `pendencias.guardar` valida o máximo de 30 linhas e lança uma exceção síncrona. Essa exceção acontece antes da Promise com o tratamento de erros.

**Reprodução confirmada com os módulos reais da tela e de validação:** selecionar 31 produtos distintos e clicar em lançar. Há exceção, zero chamadas de lançamento e o botão permanece desabilitado com “Lançando…”. A reprodução usou um DOM simulado; o caso não foi executado com 31 produtos num aparelho físico.

**Correção proposta:** aplicar os limites de 30 linhas/60 unidades durante a montagem, explicar como dividir o pedido e tratar erros locais antes de entrar no estado de envio. O limite de 60 unidades já gera recusa tratada no servidor; a exceção sem tratamento aqui é a de 31 linhas.

### 8. Atualizar o roteiro de recuperação para a interface atual

**Local:** `docs/deploy.md:68`, `docs/testar-localmente.md:301` e `deploy/backup/restaurar.sh:6`.

O guia chama `restaurar.sh --producao ARQUIVO.dump`, mas o script exige `restaurar.sh ARQUIVO.dump --producao`. A primeira posição é sempre interpretada como arquivo: o comando do guia tenta validar `--producao` como dump e falha antes da ativação.

O roteiro manual ainda manda abrir o banco fixo `mesamar_restauracao` e executar `--limpar`. O script atual cria `mesamar_recuperacao_<id>` e não implementa `--limpar`; ele trata essa opção como outro nome de arquivo. O texto que diz que o script apaga o banco também ficou antigo: a implementação atual preserva o banco anterior como resgate.

**Correção proposta:** alinhar comandos, nomes gerados e limpeza com a implementação e executar literalmente o roteiro atualizado num ambiente descartável. Esse é um defeito dos arquivos locais, distinto da validação de infraestrutura real que foi adiada. A divergência foi confirmada por leitura da interface; não foi executada uma recuperação de produção nesta auditoria.

### 9. Não entregar URLs sem versão com cache imutável

**Local:** `backend/src/main/kotlin/br/com/mesamar/config/StaticCacheConfig.kt:28`, `web-staff/index.html:17`, `web-customer/index.html:13` e os precaches de ambos os service workers.

O resource handler aplica `public, max-age=31536000, immutable` a toda a pasta `js`, inclusive URLs sem hash. O resolver aceita essas URLs. Os dois apps e seus service workers usam literalmente `/js/medicoes.js`.

**Reprodução confirmada via HTTP:** GET `/js/medicoes.js` retorna 200 com cache imutável de um ano. Como o URL continua igual, uma nova publicação não garante que o navegador obtenha o conteúdo atualizado; o precache também pode receber a cópia ainda válida do cache HTTP.

**Correção proposta:** versionar esse URL e o precache pelo conteúdo, ou dar revalidação/cache curto às URLs sem hash. Manter cache imutável apenas para nomes que mudam junto do arquivo. Isso afeta a entrega de atualizações do script compartilhado; não significa que os bundles Vite com hash estejam todos presos na versão antiga.

### 10. Tornar atômico o cadastro repetido na lista de espera

**Local:** `backend/src/main/kotlin/br/com/mesamar/lead/LeadService.kt:44` e `:66`; `backend/src/main/kotlin/br/com/mesamar/landing/LandingController.kt:75`.

A atualização por WhatsApp usa consulta seguida de inserção. Duas transações podem consultar antes de a primeira inserção ser visível. A segunda então colide com a restrição única, sem tratamento de concorrência no controller.

**Reprodução confirmada no PostgreSQL:** manter a primeira inserção sem commit; iniciar o segundo cadastro, conferir que aguarda a inserção após ler ausência; concluir o primeiro commit. O segundo cadastro termina com `DataIntegrityViolationException`. O controller não converte esse caso em sucesso/atualização e a requisição pode retornar HTTP 500.

**Correção proposta:** fazer upsert atômico ou serializar a operação pela chave normalizada. Testar dois envios válidos simultâneos do mesmo número e confirmar uma linha e respostas tratadas. Não há duplicação no banco: a restrição única está funcionando.

## Gaps de fluxo para decisão do produto

1. **Virada do dia com check-in ainda só no aparelho.** A fila vale por três dias, mas `CheckinRequest` não leva a data original da ação e `CheckinService` atribui o dia na chegada ao servidor (`CheckinService.kt:110`). Uma comanda que já existia ontem é bloqueada para novos pedidos; uma que nasceu offline ontem e só sincroniza hoje pode nascer no servidor como sendo de hoje, seguida dos lançamentos antigos. Falta uma regra explícita de operação: aceitar no dia da sincronização, exigir reconciliação ou recusar ações de outro dia. É uma decisão ainda não explicitada, não uma falha reproduzida nesta auditoria com troca de relógio.
2. **Revisão individual de pedidos ainda não pagos em `POR_PEDIDO`.** Esses pedidos ficam deliberadamente fora da fila operacional (`FilaService.kt:263`). As telas de pagamentos/clientes permitem receber, continuar/encerrar conta e lançar, mas não oferecem uma lista com os itens dos pedidos que aguardam pagamento para corrigir/cancelar um pedido específico antes de receber. Avaliar esse caminho no piloto, por exemplo quando um item esgota entre o pedido e o pagamento. Isso é distinto de criar devolução/estorno de valor já recebido.

## Falha intermitente de navegador ainda em investigação

Na matriz completa, o cenário WebKit “Pix, pagamento parcial, troco e relatório” atingiu 120 segundos durante `criarBruno`, esperando o link “Ver pedido” após clicar em adicionar Pastel (`e2e/tests/quiosque.ts:186`). Não chegou à parte que recebe o Pix.

A repetição isolada passou **duas vezes**, em aproximadamente 20 segundos por execução. Não há evidência suficiente para atribuir esse timeout a um defeito financeiro. A matriz continua tendo uma falha observada, que não deve ser apagada do resultado nem considerada resolvida apenas pelas repetições. Preservar o trace e investigar interação/redesenho durante a montagem do carrinho no WebKit.

## Validação executada

| Verificação | Resultado |
| --- | --- |
| `gradlew.bat ktlintCheck build` com JDK 21 | Passou; 482 testes backend, zero falhas/erros/skips |
| Testes unitários do app da equipe | 14/14 passaram |
| Testes unitários do app do cliente | 14/14 passaram |
| Teste local dos sinais de monitoramento | 1/1 passou |
| Matriz Playwright: celular Chromium, iPhone WebKit e teclado | 51/53 passaram; as duas falhas estão descritas acima |
| Repetição isolada do cenário WebKit de Pix | 2/2 passaram; a falha original ocorreu no preparo do cenário |
| Diagnósticos adicionais backend/PostgreSQL | 6/6 reproduziram o comportamento defeituoso esperado nos achados 2, 4, 5, 6, 9 e 10 |
| Diagnósticos adicionais TypeScript/IndexedDB | Reproduziram o travamento de 31 linhas, isolamento da fila anterior e manutenção após 503 |
| Diagnóstico adicional de navegação no Chromium | Reproduziu Clientes sobrescrevendo Fila com atraso controlado |
| Seleção de imagem com Compose e valores fictícios | Confirmou configuração A → override B → configuração A |

Os testes diagnósticos **afirmam o comportamento atual defeituoso** para provar os achados. “Passaram” não significa que esses problemas foram corrigidos. Foram criados em diretórios `build/` ignorados, sem alterar a suíte versionada. Logs e reproduções ficam em `backend/build/auditoria-*` e `e2e/build/`; um `clean` pode removê-los. As etapas e resultados importantes estão preservados neste relatório.

## Cobertura e limites

Foram revisados backend e contratos HTTP, modelos/migrações e restrições do banco, autenticação/separação de sessões, escopo por barraca, cadastro/recuperação de senha, cardápio, check-in/QR, pedidos e idempotência, estados/cancelamentos, saldo/pagamentos/fechamento/relatórios, trilhas de autoria, retenção/LGPD, SSE/push/outbox, PWAs e caches, fila offline/sincronização, configuração de produção, CI, backup/recuperação, monitores e documentação operacional.

A suíte existente cobre isolamento entre barracas, CSRF, revogação de sessões, concorrência e invariantes financeiros. Nesta revisão não foi reproduzido acesso cruzado entre barracas nem saldo negativo por pagamento/cancelamento. Isso não é uma garantia de ausência de outros defeitos. O passe do build também não cobre os novos casos acima.

Não foram executados scans remotos de vulnerabilidade, publicação em CI/VPS, envio SMTP real, recuperação S3 real, alerta para suporte real ou validação em aparelhos físicos. A configuração dos workflows de segurança foi revisada; isso não equivale a uma auditoria atualizada das dependências nem a execução desses scanners. Os ensaios de restore/Restic da CI foram lidos, mas não reexecutados nesta rodada; o monitor foi testado localmente. O ambiente temporário desta auditoria foi encerrado ao fim dos testes.

## Decisões e pendências que não são bugs novos

- Permitir o pedido que cruza o limite de saldo é deliberado. A estimativa de exposição continua válida; a divergência por produto está no achado 6.
- A equipe ignorar pausa/limite é deliberado. O aviso da tela e o registro de autoria/condições no servidor existem; não foi proposta mudança dessa regra.
- A ação “Continuar conta” após pagamento parcial existe na tela da equipe e preserva os pagamentos.
- Não haver devoluções/estornos registrados é deliberado. O registro de frequência no piloto continua sendo o caminho definido; o gap de pedido não pago acima não é um estorno.
- SMTP, contato real de suporte, deploy e monitores reais foram adiados pelo usuário. Dados fictícios identificados nos documentos não foram classificados como defeito.
- IndexedDB direto, validade de três dias e troca de PIN sem encerrar automaticamente sessões são decisões já documentadas. Os achados de acompanhamento não propõem desfazê-las.

## Ordem sugerida para a correção

Corrigir primeiro a seleção persistente de imagem e o roteiro de recuperação (1 e 8); depois as corridas de autorização/navegação (2 e 3) e o acompanhamento das filas (4 e 5). Fechar validação de pedidos/tela (6 e 7), cache do script (9) e cadastro concorrente (10). Cada correção deve ganhar um caso de regressão que verifique o resultado desejado, seguido da repetição dos cenários afetados. Os dois gaps de produto precisam de regra explícita antes de acrescentar comportamento.
