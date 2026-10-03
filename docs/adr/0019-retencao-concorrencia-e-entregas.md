# Revisão 61–80: retenção, concorrência e entregas

Data: 2026-10-03. As mudanças deste lote seguem uma migração aditiva (V18).

## Retenção e operação

| Armazenamento | Prazo e remoção |
|---|---|
| Convites | Validade existente de 14 dias; metadados removidos após expiração |
| Recuperação de senha | Validade existente de 1 hora; metadados removidos após expiração |
| Entrega de e-mail | Até a expiração do reset; conteúdo removido no sucesso, substituição ou uso do link |
| Push pendente | Validade original do aviso, incluindo o tempo na fila; removido no sucesso/expiração |
| Histórico de ações | 90 dias; sem texto livre, tokens, nomes de pessoas, senha ou chaves Pix |
| Logs da aplicação em produção | 7 dias, teto de 50 MB; rotação do Logback com limpeza no início |
| Dumps locais e snapshots externos Restic | 14 dias por padrão, mesmo `MESAMAR_BACKUP_DIAS`; parciais locais de mais de 1 dia removidos |
| Storage externo remoto | Retenção lógica por `restic forget --keep-within 14d --prune`; nunca expirar objetos correntes por idade. Limitar versões não correntes e multipart no provedor; ver ADR 0020 |

A limpeza de metadados roda a cada 5 minutos, em lotes de até 500 linhas por tabela. Valores financeiros anonimizados seguem a política já existente. Relatórios de CI continuam com retenção de 7 dias. PostgreSQL usa sete arquivos por dia da semana, sobrescritos na rotação semanal; o serviço de migração não mantém logs persistentes no Docker. Exportações externas de logs devem ter retenção de 7 dias. A política do provedor remoto depende de configuração no destino, não de acesso da aplicação.

## Banco e configuração de produção

`DB_ADMIN_USER` administra/restaura o banco. `DB_MIGRATION_USER` é proprietário dos objetos que cria, sem superusuário/criação de bancos ou papéis. `DB_USER` possui apenas uso do schema e DML, sem propriedade do banco nem criação de objetos. O serviço `migrate` termina antes do app; o app recebe somente a credencial de execução e não roda Flyway. O bootstrap automático só vale para volumes novos; o procedimento para volumes existentes está em `docs/deploy.md`.

Produção exige origem HTTPS pública sem caminho, placeholders substituídos, SMTP completo e remetente válido. Push pode estar explicitamente desligado (ambas as chaves vazias); configuração parcial, par inválido e destinos de teste são recusados. Espera do pool: 5 s; SQL: 15 s; travas: 3 s; transações: 30 s.

## Concorrência e integridade

- O check-in trava o identificador antes de consultar/criar qualquer registro. Reenvio devolve a mesma comanda, token e cliente. Disputa entre barracas não transfere o registro. Falhas de restrição revertem também o cliente criado.
- Toda mutação de cardápio trava a barraca antes de ler as linhas. Posições possuem unicidade diferida, permitindo uma troca dentro da transação. Os formulários de produto e nome de categoria levam a versão JPA: um formulário antigo recebe uma recusa de negócio.
- As FKs compostas também protegem autores de check-in, atendimento e lançamento. Fechamento financeiro tem serviço próprio de cancelamento, sem depender do cancelamento comum/liberação da fila.
- Apenas `pedido_pkey` permite o retry de criação de pedido. Token duplicado de check-in é conflito de negócio. Cadastro concorrente reconhece `uk_usuario_email` depois do rollback.
- Triggers registram cada mudança relevante, campos alterados e autor/contexto na mesma transação. Os valores registrados usam uma lista explícita de campos operacionais; demais alterações guardam apenas o nome do campo.

## Consultas, lotes e consistência

`ConsultaCliente` centraliza consultas por tela, incluindo a validade do link. Cada resposta composta e o relatório compartilham uma transação `REPEATABLE_READ`. Respostas distintas da API representam snapshots distintos: não há garantia de snapshot entre páginas/requests. Relatório usa intervalos `[início, fim)` no fuso de São Paulo, com índices por barraca/data/horário.

Clientes e pagamentos usam páginas de 100 (máximo de 200 na API), com navegação no app. Busca de clientes é identificada como busca na página. Totais financeiros e contador de contas aguardando incluem todos os registros. Listas detalhadas do relatório/CSV mostram até 100 contas; totais incluem todas. Encerramento diário seleciona até 100 contas elegíveis por transação com `SKIP LOCKED`, até 10.000 por execução; o restante segue na próxima execução. O script `deploy/db/medir-consultas.sql` registra planos, tempo e buffers em histórico representativo de treino.

## Entregas e tempo real

E-mail e cada destino push são registrados na outbox junto da transação de origem. Reservas exclusivas usam `SKIP LOCKED`, lotes de 20 e prazo recuperável de 10 minutos. O worker envia fora da transação e repete falhas com espera de até 5 minutos até expirar. A garantia é **ao menos uma tentativa persistente até a expiração**, com possibilidade de entrega duplicada se o processo cair depois do serviço aceitar e antes da confirmação no banco. As tags push reduzem avisos duplicados; não há promessa de leitura no aparelho nem de entrega exatamente uma vez.

SSE continua sendo aviso de atualização, recuperável por releitura/polling. O índice reverso permite remover somente os destinos da conexão; heartbeat é único por conexão. O executor limita 8 tarefas simultâneas e 128 na fila. Saturação ou assinante preso encerra a conexão e força ressincronização ao reconectar. O executor fecha no shutdown.

## Frontends

`web-shared/src` contém DOM, QR, UUID e dinheiro, consumidos pelos dois builds e copiados pelo Docker. O bootstrap do cliente cria uma instância de aplicação; conta e sincronização possuem iniciar/parar explícitos. `pagehide` encerra SSE/timers; retorno do cache de navegação reinicia o acompanhamento. Respostas de gerações antigas são descartadas.

Referências: [eventos transacionais do Spring](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html) e [bloqueios e SKIP LOCKED no PostgreSQL 16](https://www.postgresql.org/docs/16/sql-select.html).
