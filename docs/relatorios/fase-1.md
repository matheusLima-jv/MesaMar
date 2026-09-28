# Relatório — Fase 1: Landing page + lista de espera

- **Branch / PR:** `fase-1-landing` → [PR #2](https://github.com/matheusLima-jv/MesaMar/pull/2) (rascunho até o último checkpoint)
- A Fase 1 foi dividida em 3 checkpoints, validados um por vez:
  - [x] **1.1 — Landing estática** (aprovado)
  - [x] **1.2 — Lista de espera + `/teste-sinal`** (aprovado)
  - [x] **1.3 — Painel admin + deploy + E2E** ← checkpoint atual (final deste arquivo)

---

## Checkpoint 1.1 — Landing estática

- **Data:** 2026-09-27
- **CI:** ✅ verde (ktlint, 40 testes, imagem Docker, smoke test do `docker compose` abrindo a landing e a privacidade)

### 1. O que foi feito

- **Landing em `/`** (Thymeleaf + CSS próprio, sem framework nem fonte externa), com as seções do plano: hero com a promessa principal, a dor, "Como funciona" em 4 passos, diferenciais, oferta do piloto, FAQ e rodapé.
- Na hero há uma **ilustração da fila do dono** feita em HTML/CSS, com a legenda "Exemplo ilustrativo" para não parecer dado real.
- **Política de privacidade em `/privacidade`** em linguagem simples (LGPD): o que coletamos, para quê, base legal, retenção, direitos e contato.
- **SEO:** `title`, `meta description`, `canonical`, Open Graph completo (prévia no WhatsApp), `twitter:card`, `/robots.txt` e `/sitemap.xml` gerados com a URL pública.
- **Imagens:** imagem de compartilhamento 1200×630 (`og-mesamar.png`), favicon SVG e ícone para iPhone.
- **Botão "Falar no WhatsApp"** com mensagem pré-preenchida. Ele só aparece quando o número está configurado.
- **Cloudflare Web Analytics** (sem cookies), que só é carregado quando o token está configurado.
- **Cache:** o CSS e as imagens saem com hash no nome e cache de 1 ano. As páginas HTML não são cacheadas, então um texto novo aparece na hora.


### Revisão visual (v2) — pedido do Matheus: "estilo mais moderno, sem cara de página de IA"

O que mudou e por quê:

| Antes (v1) | Agora (v2) | Motivo |
|---|---|---|
| Fonte do sistema em tudo | **Bricolage Grotesque** nos títulos (self-hosted, 41 KB, licença OFL), fonte do sistema no texto | Título com personalidade é o que mais tira a cara de template; servir a fonte do próprio domínio evita chamar o Google (LGPD e velocidade) |
| Selo em "pílula" no topo, grade de cartões com ícones em quadradinhos coloridos | Rótulos com traço (`— COMO FUNCIONA`), listas com linhas divisórias, números grandes vazados (01–04) | Pílulas decorativas e grids de 4 cartões iguais são o padrão mais reconhecível de página gerada |
| Seção "Diferenciais" em 4 cartões | **Tabela "Caderninho × MesaMar"** (5 situações reais da praia) | Comparação concreta convence mais que adjetivos |
| Cartão da fila isolado | **Ilustração do produto:** celular do cliente com o cardápio + aviso de "Pedido #23" chegando para o dono | Mostra o produto em uso, dos dois lados |
| Dor em 4 cartões | Faixa corrida (pedido esquecido ✕ guarda-sol errado ✕ "eu já paguei!") + frase "Não falta cliente. Falta mão." | Linguagem de quem vive a praia |
| Oferta do piloto em cartões | **"R$ 0"** gigante em amarelo | Uma informação só, impossível de não ver |
| Rodapé simples | Marca "MesaMar" enorme cortada no rodapé | Assinatura visual |
| Cabeçalho com efeito de vidro (blur) | Cabeçalho sólido | Blur sem necessidade é enfeite e pesa em celular fraco |

Paleta reduzida a três papéis: papel claro (fundo), tinta/azul-oceano (texto e blocos escuros) e **um único acento amarelo-sol**. A faixa corrida respeita a configuração do celular de "reduzir movimento". A imagem de compartilhamento, o favicon e o ícone do iPhone foram refeitos no mesmo estilo.

### 2. Decisões tomadas

- **Configuração por variável de ambiente** (`MESAMAR_PUBLIC_URL`, `MESAMAR_WHATSAPP`, `MESAMAR_CONTACT_EMAIL`, `MESAMAR_CF_ANALYTICS_TOKEN`), validada na subida da aplicação. Por exemplo, um WhatsApp fora do formato `55 + DDD + número` impede o start, em vez de gerar um link quebrado.
- **Sem WhatsApp configurado, o botão some.** Assim não vai para o ar um número de exemplo por engano.
- **Sem Spring Security ainda.** A landing é pública; a autenticação entra no checkpoint 1.3, junto com o painel admin.
- O formulário da lista de espera **ainda não existe**. No lugar dele, a seção do piloto tem só o botão do WhatsApp (quando configurado). O formulário entra no checkpoint 1.2.

### 3. Como rodar localmente

```bash
git fetch origin
git checkout fase-1-landing
docker compose up --build
```

- Landing: http://localhost:8080/
- Privacidade: http://localhost:8080/privacidade
- SEO: http://localhost:8080/robots.txt e http://localhost:8080/sitemap.xml

**Para ver no celular:** com o celular no mesmo Wi-Fi, abra `http://<IP-do-seu-computador>:8080/`.

**Para ver o botão do WhatsApp:** crie um arquivo `.env` na raiz com `MESAMAR_WHATSAPP=5513XXXXXXXXX` (só dígitos) e rode `docker compose up --build` de novo.

### 4. Testes automatizados

**40 testes, 40 passando** (eram 11 na Fase 0):

| Classe | Testes | O que garante |
|---|---|---|
| `LandingPageTest` | 10 | HTML em pt-BR com um único `h1`; todas as seções e perguntas do FAQ presentes; meta description, canonical e Open Graph com URL absoluta; CSS e fonte com hash, cache de 1 ano e preload; WhatsApp e analytics escondidos sem configuração; **nenhum depoimento ou número de cliente inventado**; privacidade com header e footer; imagens e ícones servidos |
| `LandingConfiguredTest` | 4 | Com a configuração de produção: 2 botões de WhatsApp com a mensagem pré-preenchida; canonical, Open Graph, sitemap e robots usando o domínio real; script do analytics com o token; canais de contato na privacidade |
| `SeoControllerTest` | 2 | `robots.txt` libera a landing, bloqueia `/staff/`, `/cliente/` e `/actuator/` e aponta o sitemap; `sitemap.xml` é XML válido com as URLs absolutas |
| `SitePropertiesTest` | 13 | Validação do WhatsApp (aceita `55` + DDD + número, rejeita `+55`, espaços, sem DDI); validação da URL pública; codificação do link do WhatsApp |
| Fase 0 | 11 | Continuam passando |

### 5. Roteiro de validação manual (Matheus)

| # | Passo | Resultado esperado |
|---|---|---|
| 1 | Abrir http://localhost:8080/ no computador | Landing completa, sem erro de layout |
| 2 | Abrir no **celular**, de preferência ao sol | Texto legível, botões grandes, nada cortado na lateral |
| 3 | **Ler todos os textos** (hero, dor, passos, diferenciais, piloto, FAQ) | Você aprova ou me manda os ajustes. É o principal ponto deste checkpoint |
| 4 | Abrir as 5 perguntas do FAQ | Cada uma abre e fecha com um toque |
| 5 | Configurar `MESAMAR_WHATSAPP` no `.env`, subir de novo e tocar em "Falar no WhatsApp" | O WhatsApp abre na conversa com o número e a mensagem "Olá! Vi o site do MesaMar…" |
| 6 | Abrir http://localhost:8080/privacidade | Página legível, com o link do rodapé funcionando |
| 7 | Abrir `/robots.txt` e `/sitemap.xml` | Texto e XML com `http://localhost:8080/...` |

A prévia no WhatsApp (Open Graph) só dá para testar depois do deploy (checkpoint 1.3), porque o WhatsApp precisa acessar a página pela internet.

### 6. Pontos para você decidir sobre os textos

1. **"…e ninguém sai sem pagar."** É a frase mais forte da hero, e o sistema **reduz** o calote (check-in presencial, limite de saldo, aviso de pagamento), mas não **garante** que ninguém saia sem pagar. Mantive porque estava no plano, mas uma alternativa mais honesta seria: *"…e você sabe quem já pagou."*
2. **FAQ "E se a internet cair?"** Promete que o app do quiosque funciona sem internet. Isso é a Fase 6, que pelo plano pode vir **depois** do piloto se o sinal na praia for bom. Se for o caso, essa resposta precisa ser suavizada antes de a landing ir para o ar.
3. **Oferta do piloto:** listei "QR Codes impressos para o balcão" e "ajuda para montar o cardápio e treinar a equipe". Confirme se você topa entregar isso pessoalmente.

### 7. Limitações e riscos

- **O formulário ainda não existe:** sem o WhatsApp configurado, a seção do piloto não tem botão de ação. Isso se resolve no checkpoint 1.2.
- Não fiz a **medição de performance** num 4G real (a meta do plano é abaixo de 1,5s). A página continua leve (HTML + ~14 KB de CSS + 41 KB de fonte, sem JavaScript próprio), e a medição entra no E2E do checkpoint 1.3.

### 8. Próximo checkpoint (1.2), sem implementar

- **Formulário da lista de espera** na seção do piloto: nome, nome da barraca, praia, WhatsApp e consentimento, com validação e mensagens em português, página de confirmação, honeypot e rate limit por IP.
- **Cloudflare Turnstile:** preciso saber se você vai criar o widget (gratuito). Sem as chaves, deixo o Turnstile desligado por configuração e o formulário fica protegido só pelo honeypot e pelo rate limit.
- **`/teste-sinal`:** página mínima que mede o tempo de carregamento e de uma chamada à API e salva a medição com a praia escolhida.
- Tabelas novas no Flyway: `lead` e `medicao_sinal`.

✅ Aprovado pelo Matheus em 27/09/2026.

---

## Checkpoint 1.2 — Lista de espera + teste de sinal

- **Data:** 2026-09-27
- **CI:** ✅ verde (ktlint, **78 testes**, imagem Docker, smoke test do `docker compose`)

### 1. O que foi feito

**Lista de espera (formulário na seção do piloto)**
- Campos: nome, nome da barraca, praia (lista das praias do Guarujá + "Outra"), WhatsApp com DDD e consentimento LGPD com link para a política.
- Envio sem JavaScript (formulário HTML comum), com a página rolando de volta para o formulário quando há erro.
- **WhatsApp aceita o que as pessoas digitam** — `(13) 99999-8888`, `13999998888`, `+55 13 …`, `013…` — e salva normalizado (`+5513999998888`). DDD inexistente, celular sem o 9 e número curto/longo são recusados.
- Mensagens de erro em português, uma por campo, abaixo do campo; o que a pessoa digitou **não é apagado**; campos com erro ficam marcados para leitores de tela (`aria-invalid`).
- **Mesmo WhatsApp duas vezes = um lead só** (o segundo envio atualiza os dados).
- Página "Pronto, você está na lista." com botão para adiantar a conversa no WhatsApp.
- **Anti-spam:** campo-armadilha invisível (robô que preenche finge sucesso e nada é salvo), limite de 8 envios por aparelho a cada 10 minutos (página "Recebemos muitos envios deste aparelho"), e **Cloudflare Turnstile pronto, mas desligado** até você configurar as chaves.

**Teste de sinal (`/teste-sinal`)**
- Página mínima: você escolhe a praia e toca em "Medir sinal agora". Ela mostra quanto a página demorou para abrir, o tempo de resposta do servidor (mediana de 5 medições) e o tipo de conexão, e dá o veredito: ✅ até 5 s (meta do Portão A) ou ⚠️ lento.
- Cada medição é salva **anônima** (praia, tempos e tipo de conexão; sem IP, sem dado pessoal). Fora do Google (`noindex` + `robots.txt`).
- Se não houver sinal para enviar, a tela avisa que isso também é um resultado.

**Banco:** migração `V2` com as tabelas `lead` (WhatsApp único) e `medicao_sinal`.

### 2. Decisões tomadas

| Decisão | Onde |
|---|---|
| Rate limit próprio em memória em vez de Bucket4j; honeypot que "finge sucesso"; Turnstile opcional com falha fechada; lead único por WhatsApp | [ADR 0003](../adr/0003-anti-spam-da-lista-de-espera.md) |
| Sem as suas respostas às perguntas do checkpoint 1.1, mantive os textos (headline, FAQ offline, entregas do piloto) como estavam e deixei o Turnstile desligado por configuração | — |
| Removi o mínimo de 2 letras do nome: com campo vazio apareciam duas mensagens ao mesmo tempo. Agora é uma mensagem clara por campo | — |
| A política de privacidade ganhou a linha sobre o teste de sinal | `/privacidade` |

### 3. Como rodar localmente

```bash
git pull
docker compose up --build
```

- Formulário: http://localhost:8080/#lista-de-espera
- Teste de sinal: http://localhost:8080/teste-sinal
- Ver os leads salvos (até o painel do checkpoint 1.3):
  ```bash
  docker compose exec db psql -U mesamar -c "select nome, nome_barraca, praia, whatsapp, criado_em from lead order by criado_em desc;"
  docker compose exec db psql -U mesamar -c "select praia, carregamento_ms, ping_ms, tipo_conexao, criada_em from medicao_sinal order by criada_em desc;"
  ```

### 4. Testes automatizados

**78 testes, 78 passando** (eram 40):

| Classe | Testes | O que garante |
|---|---|---|
| `LeadFormTest` | 9 | Formulário com labels, praias e honeypot; envio válido salva com WhatsApp normalizado e redireciona; obrigado com `noindex`; WhatsApp repetido vira 1 lead atualizado; campos vazios → 422 com as 5 mensagens e nada salvo; WhatsApp/praia inválidos recusados **mantendo o que foi digitado**; sem consentimento não salva; honeypot finge sucesso e não salva; 9º envio do mesmo IP → 429, outro IP continua conseguindo |
| `LeadTurnstileTest` | 3 | Com as chaves configuradas: widget e script aparecem, **a chave secreta nunca vai para o HTML**, sem desafio resolvido o envio é recusado, com desafio resolvido salva |
| `BrazilianPhoneTest` | 17 | 8 formatos aceitos e 8 recusados (DDD inexistente, celular sem 9, fixo inválido, outro país, curto, longo) + formatação para exibição |
| `RateLimiterTest` | 4 | Limite, chaves separadas, liberação quando a janela passa, limpeza de chaves antigas |
| `SinalControllerTest` | 5 | Página com `noindex` e script com hash; ping 204 sem cache; medição salva **sem nenhuma coluna de dado pessoal**; praia/tempos inválidos → 400; 31ª medição do mesmo IP → 429 |
| Anteriores | 40 | Continuam passando |

### 5. Roteiro de validação manual (Matheus)

| # | Passo | Resultado esperado |
|---|---|---|
| 1 | Na landing, tocar em "Entrar na lista do piloto" **sem preencher nada** | A página volta no formulário, com uma mensagem em português embaixo de cada campo |
| 2 | Preencher com WhatsApp `(13) 1234` | Só o WhatsApp fica com erro; o resto do que você digitou continua lá |
| 3 | Preencher tudo certo, com o seu WhatsApp do jeito que você digita no dia a dia | Vai para "Pronto, você está na lista." |
| 4 | Rodar o `select` do item 3 acima | O lead aparece com o WhatsApp no formato `+55…` |
| 5 | Enviar de novo com o mesmo WhatsApp e outro nome de barraca | Continua **um** lead, com o nome novo |
| 6 | No celular, abrir `/teste-sinal`, escolher uma praia e medir | Aparecem os 3 números e o veredito; a medição aparece no `select` |
| 7 | Testar o formulário no celular, com o teclado aberto | O WhatsApp abre o teclado numérico; nada fica escondido atrás do teclado |

### 6. Limitações e riscos

- **Proteção de IP em produção:** o limite por aparelho confia no IP repassado pelo Nginx. O `docs/deploy.md` (checkpoint 1.3) vai configurar o Nginx para usar o IP real da Cloudflare; sem isso, dá para burlar o limite (ADR 0003).
- **Ainda sem painel:** os leads só podem ser vistos pelo banco até o checkpoint 1.3.
- **Sem aviso quando chega um lead.** Se quiser saber na hora (e-mail ou WhatsApp), dá para adicionar no 1.3.
- O teste de sinal mede a abertura da página **do teste**, que é leve. É um bom indicador do sinal, mas a página do cliente (Fase 3) terá que ser igualmente leve para o resultado valer.

### 7. Próximo checkpoint (1.3), sem implementar

- **Painel admin** protegido por senha (Spring Security): listas de leads e medições, com exportação CSV, e resumo do sinal por praia (mediana e % abaixo de 5 s).
- **Deploy:** `deploy/docker-compose.prod.yml`, snippet do Nginx (com o IP real da Cloudflare), `docs/deploy.md` passo a passo para a sua VPS.
- **E2E Playwright** no CI: enviar o formulário no tamanho de celular e medir o sinal.
- Preciso de você: **domínio** (`mesamar.r4d4r.online` ou outro), **WhatsApp** do botão, e-mail para privacidade (opcional) e se vai querer o **Turnstile** e o **Cloudflare Web Analytics**.

✅ Aprovado pelo Matheus em 27/09/2026.

---

## Checkpoint 1.3 — Painel admin, deploy e E2E (fecha a Fase 1)

- **Data:** 2026-09-28
- **CI:** ✅ verde: ktlint, **98 testes** de backend, imagem Docker, smoke test do `docker compose` e **9 testes E2E** no tamanho de celular

### 1. O que foi feito

**Painel `/admin`** (protegido por senha)
- Login próprio (usuário e senha vêm do `.env`). **Sem senha configurada, o painel fica trancado.**
- **Lista de espera:** total de interessados e tabela com data, nome, barraca, praia e WhatsApp (clicável: abre a conversa).
- **Sinal por praia:** número de medições, mediana de abertura da página, mediana de resposta do servidor e **% de medições abaixo de 5 s**, em verde ou vermelho. É o número que decide o Portão A.
- **Baixar CSV** dos leads e das medições, pronto para abrir no Excel em português (acentos, `;`, datas no horário de Brasília). Nomes começando com `=`/`+`/`-`/`@` são neutralizados.
- "Sair" encerra a sessão; 10 tentativas de senha erradas bloqueiam aquele IP por alguns minutos.

**Segurança do site** ([ADR 0004](../adr/0004-seguranca-do-site-e-do-painel.md))
- Cabeçalhos de segurança (CSP, anti-iframe, nosniff, referrer).
- **As páginas públicas continuam sem nenhum cookie**, como promete a política de privacidade. Há um teste garantindo isso.

**Deploy** (você executa: [`docs/deploy.md`](../deploy.md))
- `deploy/docker-compose.prod.yml`: app + Postgres, **sem abrir porta no host**. O app entra na rede Docker do seu Nginx (`web`).
- `deploy/nginx-mesamar.conf` + `nginx-mesamar-proxy.conf`: HTTPS, **IP real do visitante via Cloudflare** (`CF-Connecting-IP`) e `X-Forwarded-For` sobrescrito (fecha a brecha do ADR 0003), sem cache na Cloudflare para `/admin` e `/api`.
- `deploy/prod.env.example`: todas as variáveis, com instrução para gerar as senhas.
- Guia com DNS, certificado, rede Docker, subida, conferência (7 testes), atualização, **rollback por SHA** e backup manual.

**E2E (Playwright, Pixel 7) rodando no CI contra o `docker compose`**
- Landing num **4G congestionado simulado** (4 Mbps, 150 ms de latência): **361 ms** até o `load` no último CI (meta: 1,5 s).
- Sem rolagem lateral no celular, **sem erro no console** (pega bloqueio de CSP), FAQ abre, botão principal leva ao formulário.
- Formulário vazio mostra as 5 mensagens; envio completo cai no "Pronto, você está na lista.".
- Teste de sinal mede e salva; pede a praia antes.
- Admin: cria lead, faz login, vê o lead, baixa o CSV, sai; senha errada não entra.

**Correções que os E2E encontraram** (e que os testes de backend não pegariam)
- Depois do envio, a página de obrigado abria rolada para baixo (o navegador herdava o `#lista-de-espera` do formulário). Agora abre no topo.
- No celular, "Quero o piloto grátis" levava ao começo da seção, e o formulário ficava fora da tela. Agora vai direto ao formulário.

### 2. Decisões tomadas

| Decisão | Onde |
|---|---|
| Usuário único do painel via `.env`, painel trancado sem senha, CSRF só onde há sessão, CSP restrita, CSV à prova de fórmula | [ADR 0004](../adr/0004-seguranca-do-site-e-do-painel.md) |
| App sem porta publicada; conversa com o Nginx por rede Docker externa | `deploy/docker-compose.prod.yml` |
| Domínio de produção: **`mesamar.0mega.fun`** (definido pelo Matheus em 28/09) | `deploy/`, `docs/deploy.md` |
| O E2E só falha acima de 3 s de carregamento (e não em 1,5 s), para não quebrar o CI por variação da máquina do GitHub. O valor medido aparece em toda execução | `e2e/tests/landing.spec.ts` |

### 3. Como rodar e testar localmente

```bash
git pull
# no .env: MESAMAR_ADMIN_PASSWORD=uma-senha-sua
docker compose up --build
```

- Painel: http://localhost:8080/admin (usuário `admin` e a senha do `.env`)
- E2E no seu computador: veja a seção "Testes de ponta a ponta" do README

### 4. Testes automatizados

**98 testes de backend** (eram 78) **+ 9 E2E**:

| Classe | Testes | O que garante |
|---|---|---|
| `AdminPanelTest` | 8 | Painel e CSVs exigem login; login com CSRF e `noindex`; senha errada volta com mensagem; painel mostra lead, link do WhatsApp e resumo do sinal; CSV com BOM, `;`, aspas e **fórmula neutralizada**; CSV de medições; sair encerra a sessão; 11ª tentativa de login → 429 |
| `AdminLockedTest` | 1 | Sem senha configurada, nenhuma senha entra |
| `SecurityHeadersTest` | 3 | CSP e demais cabeçalhos; **nenhum cookie** em `/`, `/privacidade`, `/teste-sinal`, obrigado e no envio do formulário; cache longo dos estáticos continua valendo com o Spring Security |
| `CsvTest` | 8 | Escape de cada caso (acentos, fórmulas, `;`, aspas) e formato do arquivo |
| E2E Playwright | 9 | Os fluxos reais no navegador de celular (ver acima) |
| Anteriores | 78 | Continuam passando |

### 5. Roteiro de validação manual (Matheus)

**No seu computador:**

| # | Passo | Esperado |
|---|---|---|
| 1 | Pôr `MESAMAR_ADMIN_PASSWORD` no `.env`, subir e abrir `/admin` | Pede login; entra com `admin` + a sua senha |
| 2 | Enviar 2 leads pela landing e 1 medição pelo `/teste-sinal` | Aparecem no painel |
| 3 | Baixar os dois CSVs e abrir no Excel ou no Google Planilhas | Acentos certos, uma informação por coluna |
| 4 | Sair e tentar abrir `/admin` de novo | Volta para o login |

**Deploy na VPS** (seguindo o [`docs/deploy.md`](../deploy.md), depois do merge deste PR):

| # | Passo | Esperado |
|---|---|---|
| 5 | Fazer o deploy e rodar os 7 testes da seção "Conferir" do guia | Todos ok, **inclusive o do IP real** (o bloqueio vale só para o seu celular) |
| 6 | Mandar o link da landing no WhatsApp | Prévia com imagem e título |

### 6. Limitações e riscos

- **Sem aviso quando chega um lead:** você precisa abrir o painel. Se quiser um e-mail a cada lead, é pequeno de adicionar.
- **O painel tem um único usuário.** O cadastro de donos e atendentes é a Fase 2.
- **A medição de 361 ms é no CI**, com a rede simulada, não na praia. Os números que valem para o Portão A são os do `/teste-sinal`, feitos por você.
- **Backup ainda é manual** (comando no guia). O backup automático é a Fase 7.
- **A lista de IPs da Cloudflare no Nginx** precisa ser conferida em https://www.cloudflare.com/ips/ no dia do deploy.

---

## 🚦 Próximo passo: Portão A (em campo, não é código)

Depois do deploy:

1. **Divulgar:** landing nos grupos de anfitriões e comerciantes da região, e **visitas presenciais** a barracas (com o roteiro de perguntas que combinamos: como coletam pedidos hoje, o que já deu errado, quanto tempo perdem).
2. **Medir o sinal:** abrir `/teste-sinal` em **pelo menos 3 praias**, num **domingo de sol, entre 12h e 15h**, com o Wi-Fi desligado. Umas 3 medições por praia.
3. **Olhar o painel:**
   - **Seguir para a Fase 2** se houver **≥ 5 leads qualificados** (barraqueiros de verdade) **ou ≥ 3 donos** que aceitaram o piloto numa conversa, **e** a maioria das praias com **"Até 5 s" ≥ 50%**.
   - **Sinal ruim:** registramos em ADR e a Fase 6 (modo offline do atendente) vem **antes** do piloto.
   - **Poucos leads:** pausar e rever o posicionamento antes de construir mais.

⏸️ Aguardando validação do checkpoint 1.3. Responda **"APROVADO 1.3"** ou **"AJUSTAR 1.3: ..."**. Depois do deploy e das medições, me mande o resultado do Portão A.
