# Relatório — Fase 7: preparação do piloto

- **Branch:** `fase-7-piloto` (criada a partir da `fase-4-fila`, que tem as Fases 4 e 5 no PR #5 ainda não mergeado)
- A Fase 7 está dividida em checkpoints, aprovados um por vez:
  - [ ] **7.1 — Backup diário, teste de restauração, health check e logs** ← checkpoint atual
  - [ ] 7.2 — Barraca de demonstração e materiais impressos (guia de 1 página e plaquinha de balcão)
  - [ ] 7.3 — Botão "reportar problema" nos dois apps
  - [ ] 7.4 — Revisão final por um agente independente
- O que **só você** faz (deploy, treino, operação no fim de semana, Portão B) está no `ESTADO_DO_PROJETO.md`, seção 8.

---

## Checkpoint 7.1 — Backup diário, restauração, health check e logs

- **Data:** 2026-10-01
- **CI:** ✅ verde no GitHub (PR #6), **incluindo o novo passo de restauração**: "RESTAURAÇÃO OK: 19 tabelas, 70 linhas, esquema V12, idêntico ao banco original" (o banco que acabou de passar pelo E2E). Além disso, os quatro scripts foram rodados à mão contra o PostgreSQL de verdade no Docker, inclusive a recuperação de desastre, com a aplicação subindo saudável sobre o banco restaurado.

### 1. O que foi feito

- **`deploy/backup/backup.sh`:** `pg_dump` comprimido, **conferido na hora** (`pg_restore --list`: um arquivo truncado ou ilegível faz o script falhar e não é guardado), com permissão só do seu usuário e **rotação de 14 dias** (só apaga arquivos `mesamar-*.dump`, nunca outra coisa que esteja na pasta). Se você definir `MESAMAR_BACKUP_PING_URL`, avisa um monitor do tipo "healthcheck" ao terminar, para você receber um alerta quando o backup **parar** de rodar.
- **`deploy/backup/testar-restauracao.sh`: o teste de restauração.** Gera um dump (ou usa um que você passe), restaura num **banco descartável** e compara **tabela por tabela** a quantidade de linhas e a versão do esquema com o banco de verdade; não altera o banco de produção. Qualquer diferença termina com `ERRO` e código 1: "esse backup não serve".
- **`deploy/backup/restaurar.sh`:** sem opção, restaura num banco de **treino** ao lado do de produção (`--limpar` apaga). Com `--producao`, é a **recuperação de desastre**: pede que você digite `RESTAURAR`, para a aplicação, apaga e recria o banco, restaura e sobe a aplicação de novo.
- **Cron e passo a passo** em `docs/deploy.md` (seção "Backup diário, restauração e monitoramento"): agendar às 3h15, copiar para fora da VPS, restaurar de verdade e o que fazer se o servidor inteiro se perder.
- **Health check externo:** o `GET /actuator/health` já responde sem detalhes (seguro de deixar público). O guia explica o monitor no UptimeRobot (palavra-chave `"status":"UP"`, a cada 5 min) e a diferença para o monitor do backup.
- **Log de erros:** em produção o log já é JSON; agora o Docker **limita os logs a 5 arquivos de 10 MB por serviço** (o disco da VPS não enche), e o guia traz os `grep` de "erros das últimas 24 h" e quais mensagens merecem olhar.
- **CI:** um passo novo roda o teste de restauração no banco que acabou de passar pelo E2E, a cada push. O script nunca fica sem teste.

### 2. Decisões

- **Scripts de shell dentro do container do banco, sem biblioteca nem agente:** nada para instalar na VPS, nenhuma porta nova, nada no firewall. O custo é depender do `docker compose` na VPS, que você já usa.
- **Formato `custom` do `pg_dump`** (comprimido e restaurável por partes), não SQL puro, e o restore para no primeiro erro (`--exit-on-error`): ele nunca deixa o banco pela metade sem avisar.
- **O backup fica na mesma VPS.** Protege de erro de operação (apagou uma barraca, mexeu errado), **não** de perder o servidor. A cópia para fora é **manual de propósito**: depende de uma conta sua (um armazenamento, o seu computador). Está no guia como tarefa semanal.
- **Rotação por idade (14 dias), não por quantidade.** Se o cron parar por 3 semanas, os backups antigos somem sem que apareçam novos. É por isso que o monitor do backup (`MESAMAR_BACKUP_PING_URL`) existe e está recomendado.
- **O teste de restauração é estrito:** em produção, movimento do app entre o dump e a conferência pode diferir em poucas linhas e o teste acusa. Rode fora do horário de movimento.

### 3. O que precisa de você

- **Instalar o cron na VPS e rodar o teste de restauração uma vez** (itens 119 a 122 do roteiro). Só assim o backup está provado no servidor de verdade e não só na minha máquina.
- **Criar o monitor do UptimeRobot** (item 123) e, de preferência, **o monitor do backup** (item 124): são contas suas.
- **Decidir onde guardar a cópia fora da VPS** (item 125).

### 4. Testes

- **CI:** `testar-restauracao.sh` roda a cada push contra o banco do E2E.
- **Manual, nesta sessão (contra o PostgreSQL 16 do compose):**
  - `testar-restauracao.sh` → `RESTAURAÇÃO OK: 19 tabelas, 290 linhas, esquema V12, idêntico ao banco original`;
  - `backup.sh` com arquivos antigos na pasta: apaga o de 20 dias, **mantém o de 13 dias e o arquivo que não é nosso**;
  - **dump truncado** → o teste falha com "ESTE BACKUP NÃO SERVE" e código 1;
  - `restaurar.sh` no banco de treino e `--limpar`; **sem confirmar, recusa**; **com `--producao`**, restaura e a aplicação **sobe saudável** sobre o banco restaurado.
  - Dois defeitos achados e corrigidos nessa rodada: o `docker compose exec` consumia a entrada do `while read` (o teste conferia só 1 tabela) e a do `read` da confirmação.

### 5. Como validar (na entrega final)

Itens 119 a 125 de [`docs/testar-localmente.md`](../testar-localmente.md) (seção "Backup e restauração na VPS").

### 6. Antes de aprovar

- A Fase 7 está na branch `fase-7-piloto`. O PR dela é o #6, em rascunho, **apontando para a `fase-4-fila`** (o PR #5 ainda não foi mergeado). Se você mergear o #5 primeiro, eu reaponto este para a `main`.
