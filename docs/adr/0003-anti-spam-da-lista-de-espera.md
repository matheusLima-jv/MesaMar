# ADR 0003 — Anti-spam da lista de espera

- **Status:** aceito
- **Data:** 2026-09-27

## Contexto

O formulário da landing e a API do `/teste-sinal` são públicos. O plano pede Turnstile + honeypot + rate limit (Bucket4j).

## Decisão

1. **Honeypot:** campo `website` posicionado fora da tela (não `display:none`, que alguns robôs ignoram). Se vier preenchido, respondemos como sucesso e **não salvamos nada**, para o robô não aprender a contornar.
2. **Rate limit próprio, em memória** (`RateLimiter`, janela deslizante por chave):
   - formulário: 8 envios por IP a cada 10 minutos (conta tentativas com erro também, porque uma pessoa corrigindo campos raramente passa de 3–4);
   - medições de sinal: 30 por IP a cada 10 minutos.
   Não usei Bucket4j: são ~40 linhas testáveis, sem dependência nova, e com uma única instância da aplicação não há estado para compartilhar. **Se um dia houver mais de uma instância**, trocar por contagem no Postgres ou Redis.
3. **Cloudflare Turnstile opcional:** liga sozinho quando `MESAMAR_TURNSTILE_SITE_KEY` e `MESAMAR_TURNSTILE_SECRET_KEY` estão configuradas. Sem as chaves, o formulário depende só de (1) e (2). Se a Cloudflare não responder, o envio é **recusado** (falha fechada) com uma mensagem para tentar de novo.
4. **Lead duplicado:** o WhatsApp (normalizado para E.164) é único. Um segundo envio com o mesmo número **atualiza** o cadastro em vez de duplicar.

## Consequências e cuidado no deploy

- O IP do cliente vem do cabeçalho `X-Forwarded-For` (`server.forward-headers-strategy=framework`). **Em produção, o Nginx precisa sobrescrever esse cabeçalho** com o IP real vindo da Cloudflare (`CF-Connecting-IP`), senão qualquer pessoa pode inventar um IP e escapar do limite. Isso entra no `docs/deploy.md` do checkpoint 1.3.
- Reiniciar a aplicação zera os contadores. Aceitável para o objetivo (frear spam, não bloquear permanentemente).
