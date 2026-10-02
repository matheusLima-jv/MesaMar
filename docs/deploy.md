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

## 7. Conferir

| # | Teste | Esperado |
|---|---|---|
| 1 | `curl -s https://mesamar.0mega.fun/actuator/health` | `{"status":"UP",...}` |
| 2 | Abrir o site no celular (4G) | Landing completa, com o botão do WhatsApp se você configurou o número |
| 3 | Enviar o formulário com o seu WhatsApp | "Pronto, você está na lista." |
| 4 | Abrir `https://mesamar.0mega.fun/admin` | Pede login; com o usuário e a senha do `.env`, mostra o seu lead |
| 5 | Mandar o link da landing para você mesmo no WhatsApp | Aparece a prévia com a imagem e o título do MesaMar |
| 6 | Abrir `/teste-sinal` no celular e medir | A medição aparece no painel, em "Sinal por praia" |
| 7 | **IP real:** enviar o formulário vazio 9 vezes seguidas no celular | Na 9ª aparece "Recebemos muitos envios deste aparelho". No computador, na mesma hora, o formulário continua funcionando (prova de que o limite é por aparelho, não global) |
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

## Backup manual (até a Fase 7, que automatiza)

```bash
cd /opt/mesamar
docker compose -f docker-compose.prod.yml exec -T db pg_dump -U mesamar mesamar | gzip > backup-$(date +%F).sql.gz
```

Guarde uma cópia fora da VPS (ex.: `scp` para o seu computador).

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

