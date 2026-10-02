# Relatório — Fase 7: preparação do piloto

- **Branch:** `fase-7-piloto` (criada a partir da `fase-4-fila`, que tem as Fases 4 e 5 no PR #5 ainda não mergeado)
- A Fase 7 está dividida em checkpoints, aprovados um por vez:
  - [x] **7.1 — Backup diário, teste de restauração, health check e logs** (aprovado; CI verde no PR #6)
  - [ ] **7.2 — Barraca de demonstração e materiais impressos (guia de 1 página e plaquinha de balcão)** ← checkpoint atual
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

---

## Checkpoint 7.2 — Barraca de demonstração e materiais impressos

- **Data:** 2026-10-02
- **Verificado localmente:** ktlint, **371 testes de backend** (359 do 5.3 + 12 novos), build dos dois fronts e **11 E2E** (um novo, da demonstração), com a imagem Docker construída do zero. **O CI do GitHub ainda não rodou para o 7.2** (os commits ainda não foram enviados).

### 1. O que foi feito

- **Barraca de demonstração** (V13, coluna `barraca.demo`), criada e reiniciada pelo **/admin** com um botão. Mostra **uma vez** o e-mail e a senha do dono, o código da barraca e o PIN da atendente.
  - Traz **cardápio** (6 produtos), **20 guarda-sóis**, a atendente "Carla (treino)" e **dois clientes de exemplo com pedido**, para a fila e a aba Pagamentos não começarem vazias.
  - **Reiniciar** apaga o que o treino gerou, volta as regras ao padrão e **troca a senha e o PIN**. Não toca em nenhuma outra barraca.
  - **A chave Pix é fictícia:** o QR é válido para treinar, mas não leva dinheiro a lugar nenhum.
- **Faixa de treino amarela** no painel do dono, no app do quiosque e na página do cliente ("Barraca de treino: nada aqui é de verdade. Não pague.").
- **Materiais impressos** no painel do dono (menu **Materiais**), prontos para imprimir em A4 ou "Salvar como PDF", com o nome e o código da barraca de quem está logado:
  - **Guia rápido da equipe** (1 página): entrar no app, check-in, fila, receber o pagamento (com "confira o Pix no app do banco ANTES de confirmar") e quando algo não parece certo;
  - **Plaquinha do balcão**: "Peça pelo celular · Faça seu cadastro aqui e receba seu QR", em três passos.

### 2. Decisões

Ver o [ADR 0015](../adr/0015-barraca-de-demonstracao-e-materiais.md). As que merecem o seu olho:
- **A demonstração é uma barraca de verdade, marcada no banco**, e não um "modo de mentira": o treino usa exatamente as telas do piloto.
- **Só o projeto (/admin) cria e reinicia**, porque reiniciar troca a senha e o PIN.
- **A plaquinha diz "faça seu cadastro aqui" no sentido de "no balcão"**: o cadastro é feito pela equipe. O autocadastro do cliente por QR fixo é da Fase 8 e não existe ainda; se um dia existir, a plaquinha ganha o QR.
- **Os materiais são páginas do painel, não PDF gerado**: nenhuma biblioteca nova, o texto acompanha o produto, e a CSP continua restrita.
- **O guia ainda não fala do "reportar problema"** (checkpoint 7.3); a linha entra junto com o botão.

### 3. O que precisa de você

- **Criar a barraca de demonstração em produção** (itens 126 e 127 do roteiro) e **treinar o dono e os atendentes nela** antes da sexta-feira do treino.
- **Imprimir o guia e a plaquinha** da barraca de verdade (itens 131 e 132) e ver se cabem e se leem bem no balcão, ao sol. Sobra espaço no rodapé do guia para você escrever o seu telefone de suporte.

### 4. Testes automatizados novos

- **Demonstração (`DemoBarracaTest`, 6 testes):**
  - **só o admin cria** (sem login, vai para o login);
  - cria com cardápio, guarda-sóis, equipe e clientes de exemplo, **tudo marcado como treino** (painel, app e página do cliente), com a **chave Pix fictícia**, e o dono e a atendente **entram** com o que foi mostrado;
  - o **QR Pix** da demonstração leva à chave fictícia;
  - **reiniciar apaga o que o treino gerou** (comanda, pagamento, pausa, regras alteradas), **troca senha e PIN** (as antigas deixam de valer) e **não mexe numa barraca de verdade ao lado** (mesmas contagens de comanda, pedido, item, cliente e produto, e a página dela diz `demo:false`);
  - **senha só como hash**; uma barraca de verdade **não** tem faixa de treino.
- **Materiais (`MateriaisTest`, 6 testes):** sem login, nada abre (vai para o login); quem ainda não cadastrou a barraca volta ao cadastro; o guia traz o **nome e o código** da barraca e as regras que importam (inclusive o aviso do Pix e "só o dono"); a plaquinha traz a chamada do plano; **cada dono vê só a própria barraca**; o menu tem "Materiais"; **nenhuma página usa script ou estilo dentro dela** (CSP), e a folha de impressão A4 e o botão existem como arquivos próprios.
- **E2E (celular):** o admin cria a demonstração, anota as credenciais (que **somem ao recarregar**); o dono entra, vê a faixa de treino e abre o guia e a plaquinha; a atendente entra com o PIN mostrado, vê a faixa e **os dois pedidos de exemplo na fila**. Sem rolagem lateral e sem erro no console.
- **Impressão conferida na mão:** o guia e a plaquinha foram renderizados com a folha de impressão A4 e olhados na imagem: o guia cabe em uma página, com folga; a plaquinha fica com a chamada grande e legível.

### 5. Como validar (na entrega final)

Itens 126 a 135 de [`docs/testar-localmente.md`](../testar-localmente.md) (seção "Barraca de demonstração e materiais").

### 6. Antes de aprovar

- O 7.2 está na `fase-7-piloto`, no PR #6 (rascunho, apontando para a `fase-4-fila`). Os commits do 7.2 ainda são locais.
