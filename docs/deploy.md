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
- [ ] Você escolheu o subdomínio. Este guia usa **`mesamar.r4d4r.online`**; se for outro, troque em todos os arquivos.
- [ ] Você tem um **token do GitHub** com permissão `read:packages` (Settings → Developer settings → Personal access tokens). O pacote da imagem é privado porque o repositório é privado.

## 1. DNS na Cloudflare

No painel da Cloudflare, em **DNS → Records**, crie:

| Tipo | Nome | Conteúdo | Proxy |
|---|---|---|---|
| A | `mesamar` | IP da VPS (o mesmo do `r4d4r.online`) | **Ativado** (nuvem laranja) |

Nada muda no modo SSL (continua Full (Strict)) nem no Bot Fight Mode.

## 2. Certificado

Use o **mesmo método do Certbot que você já usa** para o `r4d4r.online`, agora para `mesamar.r4d4r.online`. Por exemplo, se for webroot:

```bash
sudo certbot certonly --webroot -w /caminho/do/webroot -d mesamar.r4d4r.online
```

Os arquivos devem aparecer em `/etc/letsencrypt/live/mesamar.r4d4r.online/`. Se o caminho montado no seu container do Nginx for outro, ajuste no `nginx-mesamar.conf`.

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
#   scp -P 2222 deploy/docker-compose.prod.yml deploy/prod.env.example usuario@r4d4r.online:/opt/mesamar/

cp prod.env.example .env
chmod 600 .env

# gere as duas senhas e cole no .env (DB_PASSWORD e MESAMAR_ADMIN_PASSWORD)
openssl rand -base64 24
openssl rand -base64 24

nano .env   # preencha também MESAMAR_WHATSAPP e, se quiser, Turnstile e Analytics
```

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
| 1 | `curl -s https://mesamar.r4d4r.online/actuator/health` | `{"status":"UP",...}` |
| 2 | Abrir o site no celular (4G) | Landing completa, com o botão do WhatsApp se você configurou o número |
| 3 | Enviar o formulário com o seu WhatsApp | "Pronto, você está na lista." |
| 4 | Abrir `https://mesamar.r4d4r.online/admin` | Pede login; com o usuário e a senha do `.env`, mostra o seu lead |
| 5 | Mandar o link da landing para você mesmo no WhatsApp | Aparece a prévia com a imagem e o título do MesaMar |
| 6 | Abrir `/teste-sinal` no celular e medir | A medição aparece no painel, em "Sinal por praia" |
| 7 | **IP real:** enviar o formulário vazio 9 vezes seguidas no celular | Na 9ª aparece "Recebemos muitos envios deste aparelho". No computador, na mesma hora, o formulário continua funcionando (prova de que o limite é por aparelho, não global) |

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
