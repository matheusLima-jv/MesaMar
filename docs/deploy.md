# Operação e recuperação

A execução final do piloto está organizada em [preparação operacional](preparacao-piloto.md), com responsáveis, evidências, treinamento e exemplos fictícios explicitamente identificados. SMTP, suporte real, implantação e monitores reais foram adiados por orientação do usuário na revisão 111–115; os procedimentos abaixo continuam necessários antes da produção.

Esta revisão prepara arquivos e procedimentos; não altera a VPS, SSH, firewall, DNS ou Cloudflare. Use uma janela de manutenção e um backup validado para mudar permissões ou esquema. Requisitos no host: Docker Compose, Bash, `flock`, Python 3, `realpath`, `sha256sum`, `timeout`, curl e Restic. Segredos e dumps ficam com permissões restritas, fora do Git.

## Configuração e imagem validada

Copie `deploy/docker-compose.prod.yml`, `deploy/prod.env.example`, `deploy/db`, `deploy/backup`, `deploy/lgpd`, `deploy/monitorar.py`, `deploy/implantar.sh`, `deploy/atualizar-imagem.py` e `deploy/compatibilidade.json` para `/opt/mesamar`, preservando os caminhos. Renomeie o exemplo para `.env`, preencha e aplique `chmod 600 .env`. Configure `NGINX_NETWORK` para a rede existente do proxy. O banco não publica uma porta externa.

Preencha `MESAMAR_IMAGE` com o endereço completo `ghcr.io/<organização>/<imagem>@sha256:<digest>` do artefato `versao-validada.json` da CI. Não use `latest`. A CI constrói uma vez, testa essa imagem e publica o mesmo ID. `/actuator/info` registra o commit; guarde o artefato com cada implantação.

Banco exige três usuários e senhas diferentes: administrador proprietário do banco, migrador proprietário das tabelas, execução com permissões DML. A aplicação não executa Flyway em produção. URL pública HTTPS, placeholders, SMTP completo e combinação de chaves VAPID são validados ao iniciar. VAPID completamente vazio desabilita push; configuração parcial falha. Prepare SMTP antes de produção; recuperação registra a entrega após a transação e reenvia pelo outbox.

Em volume novo, o init do PostgreSQL cria os papéis. Em volume existente, conservar `DB_ADMIN_USER`/senha do proprietário atual; alterar `POSTGRES_USER` não renomeia usuários. Faça backup validado e pare o app antes de:

```bash
cd /opt/mesamar
docker compose -f docker-compose.prod.yml --env-file .env up -d db
docker compose -f docker-compose.prod.yml --env-file .env exec db bash /opt/mesamar-db/01-contas.sh
docker compose -f docker-compose.prod.yml --env-file .env exec db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /opt/mesamar-db/permissoes.sql'
```

O bootstrap pressupõe papéis novos. Se já existem, administre as credenciais e execute somente o SQL de permissões. Referências legadas de autoria incompatíveis devem ser corrigidas com base na operação real; não remova as restrições da V18. Ensaiar upgrades de volumes antigos em cópia antes de ativar.

## Implantação e rollback

```bash
export MESAMAR_COMPOSE_FILE=/opt/mesamar/docker-compose.prod.yml
export MESAMAR_ENV_FILE=/opt/mesamar/.env
bash /opt/mesamar/implantar.sh 'ghcr.io/<organização>/<imagem>@sha256:<digest>'
```

O script lê da imagem seu manifesto de compatibilidade, recusa esquema de origem incompatível, executa migração, para o app antes da migração, confere o destino e sobe com `--wait`. Para esta publicação: origem mínima 17 (ou banco vazio), destino 21, aplicação compatível apenas com esquema 21. A V21 preserva os informes existentes e separa pendências por autor, com a idade da ação mais antiga ([ADR 0022](adr/0022-correcoes-auditoria-geral.md)); faça o backup validado antes. Primeiro atualize volumes mais antigos com uma versão intermediária validada. Testes automatizados ensaiam V17 → V20 → V21 com contas, pedidos, pagamentos e pendências existentes.

Depois de a aplicação ficar saudável, o script persiste o digest no `MESAMAR_ENV_FILE` (ou `.env` do diretório atual), por troca atômica com modo 600. Falhas de migração, compatibilidade ou subida preservam o arquivo original. Antes de um Compose independente ou restauração, execute `unset MESAMAR_IMAGE` para evitar um override antigo do shell. Use o mesmo arquivo de ambiente em todas as rotinas, inclusive rollback. Se o host cair entre saúde e persistência, confira o digest do container e a `.env` antes de retomar.

Cada nova migração exige atualizar o manifesto e testar a versão anterior contra o esquema resultante. A reversão usa o digest anterior somente se seu manifesto aceitar o esquema atual. Caso contrário, mantenha manutenção e restaure o backup anterior com a imagem correspondente. Reverter imagem por si só não desfaz migrações. Não execute migrações irreversíveis sem ensaio de recuperação e avaliação da perda de gravações posteriores.

## Backup externo S3, criptografia e retenção

Metas aprovadas: **RPO de 24 horas e RTO de 2 horas**. Execute backup a cada 12 horas, copiando cada resultado para S3, para deixar margem de reação. Um dump local não protege contra perda da VPS. Padrão de retenção: 14 dias, inteiro entre 1 e 90; configuração inválida bloqueia a rotação.

A extensão `x-operacao` do mesmo Compose lê `.env` para diretório, retenção, S3, segredos, registro LGPD e monitores. Banco e administrador vêm do container em execução. Não execute `.env` como script. Use os mesmos `MESAMAR_COMPOSE_FILE`/`MESAMAR_ENV_FILE` em todas as rotinas.

Prepare bucket privado em outro domínio de falha. Configure `RESTIC_REPOSITORY=s3:https://<endpoint>/<bucket>/mesamar`, `RESTIC_PASSWORD_FILE` e `MESAMAR_AWS_ARQUIVO` (JSON com `accessKeyId` e `secretAccessKey`). Arquivos de segredo: modo 600, pasta 700. Preserve a senha Restic e credenciais de recuperação fora da VPS, com acesso restrito e recuperação testada. Sem a senha, a cópia cifrada não pode ser restaurada.

A credencial da rotina deve acessar somente o prefixo do repositório: listar, ler, gravar e excluir objetos para locks e prune. Não conceder administração da conta/bucket. Use credencial de leitura separada para recuperação quando possível. Inicialize uma única vez com `bash /opt/mesamar/backup/inicializar-externo.sh`, que carrega a mesma configuração e credenciais. Não cole chaves na linha de comando, relatórios ou histórico da shell.

Restic cifra o conteúdo antes de enviá-lo. A rotina verifica o dump restaurando em banco descartável, envia dump/manifestações/registro LGPD, verifica uma fração do repositório e só então registra sucesso e gira arquivos locais. A trava é compartilhada com restauração e ensaios. Nomes incluem segundos e UUID; parciais são exclusivos. Checksums, hashes canônicos de todas as colunas e somas financeiras detectam diferenças que a contagem de linhas não detectaria.

**Não aplique lifecycle por idade aos objetos correntes do repositório Restic:** chunks antigos podem ser referenciados por snapshots recentes. A retenção lógica é `restic forget --tag mesamar --group-by "" --keep-within 14d --prune`, executada pela rotina e verificada depois do prune. Todos os hosts/caminhos dessa tag compartilham o prazo, inclusive após trocar a VPS. No bucket, limite versões não correntes e uploads multipart abandonados de acordo com a política do provedor. Object Lock pode impedir prune; não o habilite sem adaptar e ensaiar essa política. Inclua exportações manuais, pastas de recuperação e bancos de resgate na limpeza operacional após validação; não retenha cópias esquecidas indefinidamente.

Exemplo de cron, em UTC, mantendo as duas variáveis de caminho acima no crontab e o PATH com Restic/Python:

```cron
0 */12 * * * bash /opt/mesamar/backup/backup.sh >> /opt/mesamar/backup.log 2>&1
*/5 * * * * python3 /opt/mesamar/monitorar.py >> /opt/mesamar/monitor.log 2>&1
30 3 * * 0 bash /opt/mesamar/backup/verificar-externo.sh >> /opt/mesamar/backup.log 2>&1
0 4 1 * * bash /opt/mesamar/backup/recuperar-externo.sh >> /opt/mesamar/backup.log 2>&1
```

Configure logrotate diário: logs da operação por 14 dias, modo 600, compressão; Docker já limita tamanho/quantidade por container. Auditoria no banco é retida por 90 dias; convites 14 dias e resets uma hora. Metadados/outbox expirados são removidos em lotes. O registro de exclusões LGPD deve sobreviver enquanto existirem backups que possam reintroduzir dados; as exclusões são reaplicadas na restauração.

## Restauração e falha durante a troca

```bash
bash /opt/mesamar/backup/testar-restauracao.sh /opt/mesamar/backups/<arquivo>.dump
bash /opt/mesamar/backup/recuperar-externo.sh latest
bash /opt/mesamar/backup/restaurar.sh /caminho/recuperado/<arquivo>.dump --producao
```

A primeira rotina mede duração e registra `ultimo-ensaio.json`. A externa baixa snapshot cifrado com verificação, une exclusões LGPD e ensaia a restauração. Em perda total, primeiro reponha host, segredos e volume PostgreSQL; selecione a imagem compatível com o backup. O RTO de 2 horas inclui essa preparação, download, restauração, exclusões, migrações, subida e verificações de negócio. O tempo local não comprova esse prazo na rede/bucket reais.

`restaurar.sh` valida checksums, formato e TODOS os valores em banco separado **antes de parar produção**. Após confirmação digitada `RESTAURAR`, troca os nomes dos bancos, mantendo o anterior como resgate; reaplica LGPD/permissões, migra e aguarda saúde. Se falhar durante ativação, tenta restabelecer o banco anterior e sobe a aplicação correspondente ao ambiente atual. Caso o processo seja interrompido com SIGKILL ou haja falha de host, a trap não roda: mantenha manutenção, identifique os bancos de estágio/resgate no log, recoloque o anterior com administração PostgreSQL e use seu digest compatível; não apague bancos até verificar contas e totais.

Após recuperação bem-sucedida, confira acesso, pedidos abertos, pagamentos e totais com os manifestos. Registre início/fim, idade do snapshot, perda efetiva, digest, operador e resultado. Apague bancos de resgate e downloads temporários quando houver cópia externa validada e revisão concluída, dentro dos 14 dias de retenção. Faça ensaio mensal desde a cópia externa e após mudanças de schema/segredos/provedor. Falha ou tempo superior a 2 horas exige ajuste antes de anunciar cumprimento do RTO.

## Alertas e diagnóstico

Use dois checks externos distintos, com avisos habilitados: disponibilidade e ausência de backup. O monitor roda na VPS e envia heartbeat ou `/fail`; o monitor externo também precisa alertar se o heartbeat da VPS parar. Configure `MESAMAR_DISPONIBILIDADE_PING_URL` e `MESAMAR_BACKUP_MONITOR_PING_URL` com endpoints compatíveis com Healthchecks (resposta `OK`). `MESAMAR_BACKUP_PING_URL` registra sucesso somente depois da cópia. O prazo do backup é 24 horas, sem tolerância adicional por padrão. O marcador `ultima-copia-externa.txt` guarda o instante dos dados copiados (logo antes do dump), não o fim da verificação e do envio: o monitor mede a idade dos dados, e um backup demorado não estica o RPO. Testes locais comprovam sinais separados; a recepção de e-mail/push de alerta no provedor deve ser ensaiada na conta real.

Teste operacional separado: indisponibilidade mantendo backup recente deve disparar só disponibilidade; depois simule marcador de backup antigo com serviço saudável e verifique só backup. Restaure o marcador e confirme recuperação dos alertas. Não exponha URLs secretas dos checks.

`unhealthy` não implica reinício por `restart: unless-stopped`. Verifique `docker compose ps`, saúde, logs com request ID, memória/disco e PostgreSQL; use `docker compose restart app` para recuperar container travado. Se não resolver, mantenha manutenção e suba o digest anterior compatível. Automatizar reinício só após limitar tentativas e distinguir travamento de dependência indisponível.

`/admin/operacao` informa última execução e falhas de anonimização, encerramento, listener/eventos e notificações. `/actuator/metrics/**` exige administrador e apresenta erros, latência, requests/conexões e pool Hikari. Erros carregam `X-Request-ID` correlacionável; não registrar tokens, corpos ou dados pessoais. Notificações têm outbox e reenvio; observe atraso e falhas de entrega, não apenas processo vivo. O ensaio de consultas em `deploy/db/medir-consultas.sql` usa intervalos de timestamps, barraca e EXPLAIN ANALYZE numa cópia; SQL 15 s, lock 3 s e aquisição de conexão 5 s.

Referências: [Restic/S3](https://restic.readthedocs.io/en/stable/030_preparing_a_new_repo.html), [verificação e retenção Restic](https://restic.readthedocs.io/en/stable/045_working_with_repos.html), [sinais Healthchecks](https://healthchecks.io/docs/http_api/).

## Nginx: log de acesso sem credenciais

`deploy/nginx-mesamar.conf` grava no log só o caminho de cada acesso. O token de `/c/` e `/api/c/` sai como `***`, e qualquer query string (convite, troca de senha) vira `?***`. Os servidores das portas 80 e 443 usam o mesmo formato. Para aplicar uma versão nova do arquivo no proxy existente, sem mexer em firewall, DNS ou Cloudflare:

```bash
# copie o arquivo para a pasta conf.d do container do Nginx (o caminho depende da sua instalação)
docker exec <container-do-nginx> nginx -t          # confere a sintaxe antes
docker exec <container-do-nginx> nginx -s reload   # aplica sem derrubar conexões
# confira: abra um link de comanda e veja que o log mostra /c/*** e nada depois de ?
docker exec <container-do-nginx> tail -n 3 /var/log/nginx/mesamar.access.log
```

O log de erros do Nginx não aceita formato. Se precisar compartilhá-lo, procure `/c/` antes e mascare à mão.
