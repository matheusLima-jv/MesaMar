# Deploy do MesaMar na VPS (passo a passo manual)

> Este guia é para **você executar**. Nenhum passo mexe em firewall (UFW), porta de SSH ou nas configurações de segurança da Cloudflare: a aplicação não abre porta nova no servidor, ela conversa só com o seu Nginx por uma rede Docker interna.

**Tempo estimado:** 30–40 minutos na primeira vez; atualizações depois levam 1 minuto.

## Visão geral

```
Visitante ──HTTPS──> Cloudflare ──HTTPS──> Nginx (container, já existe)
                                              │  rede Docker "web"
                                              ▼
                                        mesamar-app:8080 ──rede interna──> PostgreSQL
```

- A imagem é publicada pelo CI em `ghcr.io/matheuslima-jv/mesamar` a cada merge em `main`.
- O banco roda num container próprio, com volume `mesamar_db-data`.
- O Nginx repassa o **IP real** do visitante (vindo da Cloudflare) para a aplicação. Isso é o que faz o limite de envios por aparelho funcionar (ver ADR 0003).

## Antes de começar

- [ ] O PR da Fase 1 foi aprovado e **mergeado em `main`**, e o job "Imagem Docker" do CI ficou verde (é ele que publica a imagem).
- [ ] O domínio **`0mega.fun` está na sua conta da Cloudflare** (nameservers apontando para a Cloudflare). O endereço do site será **`mesamar.0mega.fun`**, já configurado em `deploy/`.
- [ ] Você tem um **token do GitHub** com permissão `read:packages` (Settings → Developer settings → Personal access tokens). O pacote da imagem é privado porque o repositório é privado.

## 1. DNS na Cloudflare

No painel da Cloudflare, na zona **`0mega.fun`**, em **DNS → Records**, crie:

| Tipo | Nome | Conteúdo | Proxy |
|---|---|---|---|
| A | `mesamar` | IP da VPS (o mesmo que o `r4d4r.online` usa hoje) | **Ativado** (nuvem laranja) |

Na zona `0mega.fun`, deixe **SSL/TLS em Full (Strict)** (igual ao que você usa no `r4d4r.online`). Se o domínio for novo na Cloudflare, confira também se o **Bot Fight Mode** está como você prefere: ele pode barrar chamadas automáticas, mas navegador de verdade passa normalmente.

## 2. Certificado

Use o **mesmo método do Certbot que você já usa** para o `r4d4r.online`, agora para `mesamar.0mega.fun`. Por exemplo, se for webroot:

```bash
sudo certbot certonly --webroot -w /caminho/do/webroot -d mesamar.0mega.fun
```

Os arquivos devem aparecer em `/etc/letsencrypt/live/mesamar.0mega.fun/`. Se o caminho montado no seu container do Nginx for outro, ajuste no `nginx-mesamar.conf`.

## 3. Rede Docker compartilhada com o Nginx

```bash
# cria a rede (se já existir, o Docker só avisa e segue)
docker network create web

# descobre o nome do container do Nginx
docker ps --format '{{.Names}}' | grep -i nginx

# conecta o Nginx nela agora, sem reiniciar
docker network connect web <nome-do-container-nginx>
```

Para a conexão sobreviver a um `docker compose up` do Nginx, adicione no `docker-compose.yml` **do Nginx**:

```yaml
services:
  nginx:
    networks: [default, web]

networks:
  web:
    external: true
```

## 4. Arquivos da aplicação

```bash
sudo mkdir -p /opt/mesamar && sudo chown "$USER" /opt/mesamar
cd /opt/mesamar

# do seu computador, envie os dois arquivos (porta do SSH: a sua, 2222)
#   scp -P 2222 deploy/docker-compose.prod.yml deploy/prod.env.example usuario@IP-DA-VPS:/opt/mesamar/

cp prod.env.example .env
chmod 600 .env

# gere as duas senhas e cole no .env (DB_PASSWORD e MESAMAR_ADMIN_PASSWORD)
openssl rand -base64 24
openssl rand -base64 24

nano .env   # preencha também MESAMAR_WHATSAPP e, se quiser, Turnstile e Analytics
```

**E-mail (troca de senha do painel).** Sem SMTP, o "esqueci a senha" do dono não chega a lugar nenhum (o link só vai para o log). Qualquer SMTP serve; o mais simples é um serviço transacional com plano grátis (Brevo, Resend, Amazon SES…) usando um remetente do seu domínio `0mega.fun`, e no `.env`:

```bash
MESAMAR_MAIL_FROM=MesaMar <nao-responda@0mega.fun>
SPRING_MAIL_HOST=smtp.do-servico
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=...
SPRING_MAIL_PASSWORD=...
```

O serviço vai pedir registros **SPF/DKIM** no DNS. **Esses registros você cria manualmente na Cloudflare**; este passo a passo não mexe na Cloudflare.

## 5. Subir

```bash
cd /opt/mesamar
echo <SEU_TOKEN_GITHUB> | docker login ghcr.io -u matheusLima-jv --password-stdin

docker compose -f docker-compose.prod.yml --env-file .env pull
docker compose -f docker-compose.prod.yml --env-file .env up -d

# acompanhe até aparecer "Started MesaMarApplication" (Ctrl+C para sair do log)
docker compose -f docker-compose.prod.yml logs -f app
```

Na primeira subida o Flyway cria as tabelas sozinho.

## 6. Nginx

```bash
# copie os dois arquivos para as pastas que o seu container do Nginx lê, por exemplo:
#   deploy/nginx-mesamar.conf        -> <pasta-conf.d>/mesamar.conf
#   deploy/nginx-mesamar-proxy.conf  -> <pasta-snippets>/mesamar-proxy.conf

docker exec <nome-do-container-nginx> nginx -t        # tem que dizer "syntax is ok"
docker exec <nome-do-container-nginx> nginx -s reload
```

Se o `nginx -t` reclamar de `http2 on;`, o seu Nginx é anterior à 1.25.1: troque por `listen 443 ssl http2;` (há um comentário no arquivo).

### Log de acesso sem credenciais

O `nginx-mesamar.conf` define um `log_format` que troca por `***` o que é credencial na URL: o endereço da comanda (`/c/<token>`, `/api/c/<token>`), o convite (`?convite=`) e a troca de senha (`?token=`). Sem isso, quem lê o log do Nginx (ou uma cópia dele) veria os links. O arquivo grava o log em `/var/log/nginx/mesamar.access.log`: confira que essa pasta existe no seu container (ou troque o caminho) e que **não há outro `access_log` mais permissivo** para o mesmo site.

Para conferir depois do `nginx -s reload`, abra um link de comanda e olhe a última linha do log: tem que aparecer `GET /c/*** HTTP/2.0`, nunca o código. O log de **erro** do Nginx pode citar o caminho quando uma conexão falha (`request: "GET /c/..."`): mantenha ele só com você e com rotação.

O cabeçalho `Referrer-Policy: no-referrer` já sai da aplicação, então o link da comanda também não vaza para outros sites.

### Pedidos de exclusão de dados (LGPD)

O registro das exclusões atendidas fica em **`/opt/mesamar/lgpd/exclusoes.txt`** (só UUIDs). Crie a pasta uma vez (`mkdir -p /opt/mesamar/lgpd && chmod 700 /opt/mesamar/lgpd`) e **inclua o arquivo na cópia dos backups que você leva para fora da VPS**: é ele que refaz as exclusões se um backup antigo for restaurado. O procedimento está em `docs/lgpd-procedimento.md`.

## 7. Conferir

| # | Teste | Esperado |
|---|---|---|
| 1 | `curl -s https://mesamar.0mega.fun/actuator/health` | `{"status":"UP",...}` |
| 2 | Abrir o site no celular (4G) | Landing completa, com o botão do WhatsApp se você configurou o número |
| 3 | Enviar o formulário com o seu WhatsApp | "Pronto, você está na lista." |
| 4 | Abrir `https://mesamar.0mega.fun/admin` | Pede login; com o usuário e a senha do `.env`, mostra o seu lead |
| 5 | Mandar o link da landing para você mesmo no WhatsApp | Aparece a prévia com a imagem e o título do MesaMar |
| 6 | Abrir `/teste-sinal` no celular e medir | A medição aparece no painel, em "Sinal por praia" |
| 7 | **IP real:** enviar o formulário vazio 9 vezes seguidas no celular | Na 9ª aparece "Recebemos muitos envios desta conexão". No computador, em outra rede, na mesma hora, o formulário continua funcionando (prova de que o limite é por IP, não global) |
| 8 | `/admin` → "Gerar convite avulso" → abrir o link numa janela anônima e criar uma conta | Entra no `/painel` e cadastra a barraca |
| 9 | Painel → Sair → "Esqueci a senha" com o e-mail da conta | O e-mail chega (olhe o spam na primeira vez) e o link troca a senha |

Se o passo 7 bloquear **todo mundo** junto, o Nginx não está repassando o IP real: confira o bloco `set_real_ip_from` / `real_ip_header` e o `proxy_set_header X-Forwarded-For $remote_addr`.

## Atualizar para uma versão nova

Depois de cada merge em `main` com o CI verde:

```bash
cd /opt/mesamar
docker compose -f docker-compose.prod.yml --env-file .env pull
docker compose -f docker-compose.prod.yml --env-file .env up -d
```

## Voltar para a versão anterior

Cada imagem também é publicada com o SHA curto do commit (aba *Packages* do GitHub ou `git log --oneline`):

```bash
nano .env                       # MESAMAR_TAG=<sha-da-versão-boa>
docker compose -f docker-compose.prod.yml --env-file .env up -d
```

As migrações do Flyway só adicionam coisas nesta fase, então voltar a imagem não quebra o banco.

## Barraca de demonstração (treino)

Antes de operar de verdade, treine a equipe numa barraca fictícia. Depois que o app estiver no ar:

1. Entre em `https://mesamar.0mega.fun/admin` → **Barraca de demonstração** → **Criar a barraca de demonstração**.
2. **Anote na hora** o e-mail e a senha do dono, o código da barraca e o PIN da atendente: eles aparecem uma vez.
3. A equipe treina em `/painel` (dono) e `/staff/` (atendente) com esses dados. Tudo mostra a faixa amarela de **treino**, e a chave Pix é fictícia (não leva dinheiro a lugar nenhum).
4. **Reiniciar** no mesmo botão apaga o que o treino gerou e troca a senha e o PIN.

Os materiais para imprimir (guia de 1 página e plaquinha do balcão) estão no painel do dono, no menu **Materiais**, com o nome e o código da barraca de quem está logado.

## Backup diário, restauração e monitoramento

O banco é o único lugar onde os pedidos e os pagamentos existem. Este bloco liga o backup, prova que ele restaura e avisa quando algo para.

### Backup automático (pg_dump + 14 dias)

Os scripts ficam em `deploy/backup/` e rodam **dentro do container do banco** (não precisam de nada instalado na VPS, não abrem porta). Cada backup é um arquivo comprimido, conferido na hora (se o arquivo sair truncado ou ilegível, o script falha e **não** o guarda), com permissão só do seu usuário; os de mais de 14 dias são apagados.

```bash
# uma vez, na VPS (a pasta é a do deploy: /opt/mesamar)
cd /opt/mesamar
sudo mkdir -p /opt/mesamar/backups && sudo chown "$USER" /opt/mesamar/backups
chmod +x deploy/backup/*.sh        # só se ainda não estiverem executáveis (git já guarda o bit)

# teste à mão primeiro
export MESAMAR_COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env"
./deploy/backup/backup.sh
ls -lh /opt/mesamar/backups
```

Saída esperada: `backup ok: /opt/mesamar/backups/mesamar-AAAA-MM-DD_HHMM.dump (…)` e `rotação: … backup(s) guardado(s)`.

**Agende no cron** (`crontab -e`), de madrugada, **fora do horário de praia** (aqui, 3h15):

```cron
15 3 * * * cd /opt/mesamar && MESAMAR_COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env" MESAMAR_BACKUP_PING_URL="https://hc-ping.com/SEU-ID" ./deploy/backup/backup.sh >> /opt/mesamar/backups/backup.log 2>&1
```

- `MESAMAR_BACKUP_PING_URL` é **opcional**: se você criar um monitor do tipo "healthcheck" (ex.: healthchecks.io, plano grátis, com "período: 1 dia, tolerância: 2 h"), o script avisa que deu certo, e **você recebe um alerta quando o backup PARAR de rodar** (um backup que não roda não faz barulho). Sem a variável, o script simplesmente não avisa ninguém.
- Variáveis opcionais: `MESAMAR_BACKUP_DIR` (padrão `/opt/mesamar/backups`) e `MESAMAR_BACKUP_DIAS` (padrão 14).
- O backup fica **na mesma VPS**: protege de erro de operação, não de perder o servidor. **Copie para fora** pelo menos uma vez por semana: `scp usuario@vps:/opt/mesamar/backups/mesamar-*.dump ~/backups-mesamar/` (ou um `rclone` para um armazenamento seu). Isto não é automático de propósito: depende de uma conta sua.

### Teste de restauração (faça agora e uma vez por mês)

Um backup que nunca foi restaurado é uma esperança, não um backup. Este teste gera um dump, restaura num **banco descartável** ao lado do de produção e compara, tabela por tabela, a quantidade de linhas e a versão do esquema. **Não altera o banco de verdade.**

```bash
cd /opt/mesamar
export MESAMAR_COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env"
./deploy/backup/testar-restauracao.sh                                    # com um dump novo
./deploy/backup/testar-restauracao.sh /opt/mesamar/backups/mesamar-AAAA-MM-DD_0315.dump   # com um backup já guardado
```

Esperado, no fim: `RESTAURAÇÃO OK: N tabelas, M linhas, esquema V12, idêntico ao banco original.` Qualquer outra coisa (erro do `pg_restore`, tabela faltando, contagem diferente) termina com `ERRO` e código 1: **esse backup não serve.** Rode fora do horário de movimento: se alguém fizer um pedido entre o dump e a conferência, as tabelas movimentadas podem diferir em poucas linhas, e o teste acusa (rode de novo).

O CI roda esse mesmo teste a cada push, no banco que acabou de passar pelo E2E, então o script em si está sempre testado.

### Recuperação de desastre (restaurar de verdade)

Use quando o banco foi perdido ou corrompido. **Apaga o banco de produção** e o troca pelo backup; o que entrou depois do backup se perde.

```bash
cd /opt/mesamar
export MESAMAR_COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env"
./deploy/backup/restaurar.sh /opt/mesamar/backups/mesamar-AAAA-MM-DD_0315.dump --producao
# pede para digitar RESTAURAR; para a aplicação, restaura, sobe a aplicação de novo
```

Depois: abra `https://mesamar.0mega.fun/actuator/health` (deve dizer `"status":"UP"`), entre no `/admin` e num painel de dono, e confira uma comanda recente. Sem `--producao`, o script restaura num banco de **treino** (`mesamar_restauracao`) sem tocar no de produção, e `./deploy/backup/restaurar.sh --limpar` o apaga.

Se o servidor inteiro se perder: instale o Docker, refaça os passos 1 a 6 deste guia (o `.env` precisa estar guardado fora da VPS: **guarde uma cópia dele num cofre de senhas**), suba só o `db`, e restaure o último backup que você copiou para fora com o comando acima.

### Health check externo (UptimeRobot)

A aplicação responde a `GET /actuator/health` com `{"status":"UP"}` (sem detalhes, é seguro deixar público). Num monitor externo (UptimeRobot, plano grátis):

1. **Add New Monitor** → tipo **HTTP(s)** → URL `https://mesamar.0mega.fun/actuator/health`.
2. Em "Keyword", escolha **"Keyword exists"** com `"status":"UP"` (assim um 200 com erro dentro também alerta).
3. Intervalo de **5 minutos** e **alerta para o seu e-mail ou WhatsApp**.

Isto avisa quando o site, o Nginx, a aplicação ou o banco caem. Ele **não** avisa se o backup parou: para isso, é o `MESAMAR_BACKUP_PING_URL` acima.

### Logs de erro

Em produção a aplicação escreve **uma linha JSON por evento** na saída do container, e o Docker guarda no máximo **5 arquivos de 10 MB** por serviço (o disco da VPS não enche com log).

```bash
cd /opt/mesamar
docker compose -f docker-compose.prod.yml logs --since 24h app | grep '"level":"ERROR"'      # erros das últimas 24 h
docker compose -f docker-compose.prod.yml logs --since 1h app | grep -c '"level":"WARN"'      # quantos avisos na última hora
docker compose -f docker-compose.prod.yml logs -f app                                          # acompanhar ao vivo
```

Erros que valem uma olhada: `Falha ao anonimizar`, `Falha ao verificar pedidos atrasados`, `Não deu para avisar` (push) e qualquer `ERROR` com stack trace. O resumo diário pode ser um `cron` que manda esse `grep` por e-mail; o mínimo é olhar antes de cada fim de semana de operação.

## O que este deploy NÃO faz (de propósito)

- Não abre nenhuma porta nova no host nem mexe no UFW. A aplicação só existe nas redes Docker.
- Não altera SSH, Cloudflare SSL/TLS, Bot Fight Mode nem regras de firewall da Cloudflare.
- Não guarda nenhum segredo no repositório: tudo fica no `.env` da VPS.

## Tempo real (SSE) atrás do Nginx e da Cloudflare

A fila e a página do cliente usam uma conexão que fica aberta (SSE, `text/event-stream`). Nada a configurar:
- o servidor já manda `X-Accel-Buffering: no`, então o Nginx não segura os avisos;
- o `proxy_read_timeout 60s` do `nginx-mesamar-proxy.conf` é maior que o batimento do servidor (a cada 25 s), e a Cloudflare também mantém a conexão viva por causa dele.

**Como conferir depois do deploy** (use o link do QR de uma comanda de teste):

```bash
curl -N https://mesamar.0mega.fun/api/c/<TOKEN_DO_QR>/eventos
```

Esperado: aparece `:conectado` na hora e uma linha `:ping` a cada 25 segundos, sem parar. Se ficar mudo por muito tempo, alguma camada está segurando a resposta: confira se o `location` do `/api/` do Nginx não tem `proxy_buffering on` e se não há compressão ligada para `text/event-stream`.

## Avisos de pedido (Web Push): chaves VAPID

Os avisos no celular bloqueado precisam de um par de chaves que identifica o servidor perante o Google, a Apple e a Mozilla. **Sem elas o sistema funciona normalmente**, só que o app mostra "Os avisos ainda não foram configurados no servidor".

1. **No seu computador** (não na VPS), na pasta do projeto:

   ```bash
   ./gradlew --console=plain -q :backend:gerarVapid
   ```

   Saem duas linhas (`MESAMAR_VAPID_PUBLIC_KEY=…` e `MESAMAR_VAPID_PRIVATE_KEY=…`). Nada é gravado em arquivo.
2. **Copie as duas linhas para o `.env` da VPS** (e, se quiser, `MESAMAR_VAPID_SUBJECT=mailto:seu@email`; vazio usa a URL pública do site). **A chave privada é segredo:** só no `.env`, nunca no Git, no WhatsApp ou em e-mail.
3. Reinicie: `docker compose -f docker-compose.prod.yml up -d`.
4. No app do quiosque, aba **Mais**, o cartão "Avisos de pedido" deve mostrar o botão **"Ativar os avisos"**. Ative em cada celular que vai receber (dono e atendentes) e use **"Mandar um aviso de teste"**.

Cuidados:
- Só funciona em **HTTPS** (em produção já é) e **não** em `http://IP-DO-PC` na rede local.
- No **iPhone**, o app precisa estar instalado na tela inicial (iOS 16.4 ou mais novo).
- **Trocar as chaves depois** faz todo mundo precisar ativar os avisos de novo. Guarde a chave privada num lugar seguro.

