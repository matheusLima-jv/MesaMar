# Revisão 61–80 — retenção, integridade e organização

Data: 2026-10-03. Alterações no backend Kotlin/PostgreSQL, deploy e nos dois frontends. Migração: `V18__integridade_historico_e_entregas.sql`.

## Correções aplicadas

| Item | Implementação |
|---|---|
| 61 | Limpeza periódica de convites, resets e entregas vencidas em lotes; histórico por 90 dias; logs com rotação; dumps locais e snapshots externos com o mesmo prazo (política atualizada na ADR 0020). Política documentada na ADR 0019. |
| 62 | Contas distintas de administração, migração e execução. Flyway em processo/serviço separado; grants atuais e futuros apenas DML para o app. Restauração preserva essa separação. |
| 63 | Validação antecipada do perfil prod: placeholders, origem HTTPS, domínios reservados, SMTP/remetente, par VAPID e privilégios do banco. SMTP pode falhar durante operação sem comprometer as transações. |
| 64 | Reset e e-mail pendente são confirmados juntos; worker envia SMTP depois do commit. Substituir ou usar um link remove seu envio pendente. Falha de envio não muda a resposta ao solicitante. |
| 65 | `CancelamentoFinanceiro` trata os pedidos que nunca entraram na fila. Fechamento deixa de chamar o cancelamento comum de `FilaService`, evitando liberação indireta durante a baixa financeira. |
| 66 | Mutação de cardápio serializada por barraca; unicidade diferida de posições; versão JPA e formulário para edições de produtos e nomes de categorias. Formulário antigo recebe recusa. |
| 67 | Trava transacional pelo identificador antes de qualquer criação do check-in. Repetição preserva comanda, cliente e token. Testes verificam seis envios concorrentes, disputa entre barracas e rollback sem órfãos. |
| 68 | FKs compostas protegem quem abriu a comanda, lançou ou atendeu pedido, sempre pela mesma barraca da operação. |
| 69 | Histórico transacional por triggers com cada alteração relevante, autor/contexto, campos alterados e antes/depois operacional. Valores sensíveis e texto livre ficam fora do histórico. |
| 70 | Contrato JSON comum: `erro`, `mensagem`, `campos`, `temporario`. Segurança, validação, conflito e falhas transitórias recebem formato consistente, preservando campos específicos dos endpoints. |
| 71 | Reconhecimento por SQLSTATE e nome de restrição. Retry de pedido restrito a `pedido_pkey`; integridade de FK/check não é tratada como colisão de identificador. |
| 72 | Disputa de e-mail retorna erro de cadastro após rollback; categoria duplicada retorna mensagem de negócio. Outros conflitos conhecidos têm tradução centralizada. |
| 73 | `ConsultaCliente` centraliza composição por tela. Cliente, fila, pagamentos, check-in e relatório usam snapshot transacional para leituras compostas; requests/páginas distintos podem ver instantes distintos. |
| 74 | Filtros de horário por intervalo no fuso da barraca; índices de pagamentos, fechamento, pedidos e limpeza. EXPLAIN com 12 mil pagamentos e script de medição em treino. Espera: pool 5 s, SQL 15 s, trava 3 s, transação 30 s. |
| 75 | Clientes e pagamentos paginados em 100, máximo 200, com navegação; totais completos calculados separadamente. Detalhes do relatório/CSV limitados e identificados. Encerramento em lotes de 100, até 10 mil por execução. |
| 76 | Outbox de e-mail e cada destino push persistida na transação de origem. Reservas exclusivas recuperáveis, retries e expiração. Reinício não perde a entrega já confirmada e ainda válida. |
| 77 | Trabalho de entrega limitado a lotes de 20 por worker; SSE com 8 tarefas simultâneas e fila de 128. Executor e conexões são encerrados no desligamento. Destinos lentos têm timeout. |
| 78 | Índice reverso de assinantes SSE para remoção localizada e heartbeat único por conexão. Saturação encerra a conexão para recuperar o estado ao reconectar. |
| 79 | DOM, QR, UUID e dinheiro em `web-shared/src`; ambos os builds, Gradle e Docker consomem esses módulos. |
| 80 | Bootstrap do cliente reduzido; estado por instância, navegação, sincronização e ações da conta com iniciar/parar. SSE/timers são encerrados no pagehide e retomados no retorno; respostas antigas são descartadas. |

## Garantias e prazos

A outbox oferece tentativas persistentes até a validade do aviso. Pode haver duplicidade se o serviço aceitar a mensagem e o processo cair antes da confirmação no banco; não há garantia de entrega exatamente uma vez ou de leitura no aparelho. SMTP fica fora de transações de domínio. SSE é um aviso recuperável por leitura/polling.

| Dados | Retenção |
|---|---|
| Convites | 14 dias de validade; remoção após vencer |
| Resets | 1 hora de validade; remoção após vencer |
| Outbox | Até sucesso ou validade original; e-mail também removido ao substituir/usar o reset |
| Histórico | 90 dias, sem valores sensíveis |
| Logs | Aplicação: rotação de 7 dias, teto 50 MB; banco: sete arquivos diários cíclicos |
| Backup local e snapshots externos | 14 dias por padrão; parciais interrompidos removidos após 1 dia |

A remoção no banco é eventual, em lotes de 500 a cada cinco minutos. Backups preservam snapshots durante sua própria retenção, sem renovar validade de links. Buckets remotos usam retenção lógica Restic, sem expirar objetos correntes por idade. Versões antigas, logs exportados e rotação do Nginx exigem a política correspondente no destino; não há credenciais desses serviços no projeto.

## Verificação

Suíte completa do backend: **466 testes aprovados**, sem falhas ou testes ignorados; `ktlintCheck` aprovado. Vinte testes novos cobrem a revisão (16 de integração e quatro de configuração).

- Builds de produção dos dois frontends, incluindo TypeScript, aprovados.
- Quatro testes de ciclo de vida do cliente aprovados: teardown de SSE/timers, coalescência de sincronização, descarte de resposta após parada e isolamento do estado.
- Onze testes Playwright em emulação de celular aprovados. O fluxo completo cobre cadastro, cardápio, check-in/QR, pedidos do cliente, fila, pagamentos e relatório, sem erros no console.
- Processo de migração carregado pelo `PropertiesLauncher` do JAR e aplicado com sucesso em PostgreSQL 16 descartável, com a conta de migração.
- Perfil prod iniciado com a conta de execução: health `UP`, sem Flyway no app e logs em arquivo JSON. SMTP usado nesse ensaio tem configuração sintática de teste; nenhum e-mail real foi enviado.
- Dump/restauração em banco descartável: 22 tabelas pertencendo à conta de migração; conta de execução conseguiu gravar e consultar. Teste de permissões também recusa CREATE TABLE e CREATE DATABASE.
- Sintaxe de quatro scripts Bash e configuração do compose de produção conferidas.

A consulta de um dia em 12 mil pagamentos usou `idx_pagamento_barraca_hora`, com quatro blocos em cache e nenhum bloco lido do disco no ensaio. O plano completo é gerado em `backend/build/reports/consultas-historico.json`. Os tempos são de dados sintéticos em ambiente local e não estimam capacidade de produção.

## Aplicação em produção

Antes de subir a nova imagem, siga `docs/deploy.md`: volumes existentes precisam da criação dos papéis e transferência de propriedade do schema público. Preencha credenciais distintas, SMTP e URL HTTPS; rode `migrate` antes do app. O guia inclui a restauração com a conta de migração e a retenção externa. Nenhum deploy foi executado.

Decisões e limites: `docs/adr/0019-retencao-concorrencia-e-entregas.md`. Roteiro manual: `docs/testar-localmente.md`.
