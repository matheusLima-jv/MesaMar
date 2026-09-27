# Relatório — Fase 1: Landing page + lista de espera

- **Branch / PR:** `fase-1-landing` → [PR #2](https://github.com/matheusLima-jv/MesaMar/pull/2) (rascunho até o último checkpoint)
- A Fase 1 foi dividida em 3 checkpoints, validados um por vez:
  - [x] **1.1 — Landing estática** ← este relatório
  - [ ] 1.2 — Lista de espera (formulário, validação, anti-spam) + `/teste-sinal`
  - [ ] 1.3 — Painel admin com CSV + arquivos de deploy + E2E Playwright

---

## Checkpoint 1.1 — Landing estática

- **Data:** 2026-09-27
- **CI:** ✅ verde (ktlint, 39 testes, imagem Docker, smoke test do `docker compose` abrindo a landing e a privacidade)

### 1. O que foi feito

- **Landing em `/`** (Thymeleaf + CSS próprio, sem framework nem fonte externa), com as seções do plano: hero com a promessa principal, a dor, "Como funciona" em 4 passos, diferenciais, oferta do piloto, FAQ e rodapé.
- Na hero há uma **ilustração da fila do dono** feita em HTML/CSS, com a legenda "Exemplo ilustrativo" para não parecer dado real.
- **Política de privacidade em `/privacidade`** em linguagem simples (LGPD): o que coletamos, para quê, base legal, retenção, direitos e contato.
- **SEO:** `title`, `meta description`, `canonical`, Open Graph completo (prévia no WhatsApp), `twitter:card`, `/robots.txt` e `/sitemap.xml` gerados com a URL pública.
- **Imagens:** imagem de compartilhamento 1200×630 (`og-mesamar.png`), favicon SVG e ícone para iPhone.
- **Botão "Falar no WhatsApp"** com mensagem pré-preenchida. Ele só aparece quando o número está configurado.
- **Cloudflare Web Analytics** (sem cookies), que só é carregado quando o token está configurado.
- **Cache:** o CSS e as imagens saem com hash no nome e cache de 1 ano. As páginas HTML não são cacheadas, então um texto novo aparece na hora.

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

**39 testes, 39 passando** (eram 11 na Fase 0):

| Classe | Testes | O que garante |
|---|---|---|
| `LandingPageTest` | 9 | HTML em pt-BR com um único `h1`; todas as seções e perguntas do FAQ presentes; meta description, canonical e Open Graph com URL absoluta; CSS com hash e cache de 1 ano; WhatsApp e analytics escondidos sem configuração; **nenhum depoimento ou número de cliente inventado**; privacidade com header e footer; imagens e ícones servidos |
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
- Não fiz a **medição de performance** num 4G real (a meta do plano é abaixo de 1,5s). A página é leve (HTML + ~9 KB de CSS, sem fonte externa e sem JavaScript próprio), e a medição entra no E2E do checkpoint 1.3.
- A imagem de compartilhamento usa a fonte padrão do Linux em que foi gerada. Se quiser uma identidade visual própria (logo, cores), é fácil trocar depois.

### 8. Próximo checkpoint (1.2), sem implementar

- **Formulário da lista de espera** na seção do piloto: nome, nome da barraca, praia, WhatsApp e consentimento, com validação e mensagens em português, página de confirmação, honeypot e rate limit por IP.
- **Cloudflare Turnstile:** preciso saber se você vai criar o widget (gratuito). Sem as chaves, deixo o Turnstile desligado por configuração e o formulário fica protegido só pelo honeypot e pelo rate limit.
- **`/teste-sinal`:** página mínima que mede o tempo de carregamento e de uma chamada à API e salva a medição com a praia escolhida.
- Tabelas novas no Flyway: `lead` e `medicao_sinal`.

⏸️ Aguardando validação. Responda "APROVADO 1.1" ou "AJUSTAR 1.1: ..."
