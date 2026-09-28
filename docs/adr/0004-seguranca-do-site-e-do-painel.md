# ADR 0004 — Segurança do site público e do painel

- **Status:** aceito
- **Data:** 2026-09-28

## Contexto

O checkpoint 1.3 adiciona o Spring Security para proteger o painel `/admin`. Ao mesmo tempo, a política de privacidade promete que a landing **não usa cookies**, e o formulário da lista de espera é anônimo.

## Decisão

- **Um único usuário do painel**, definido por `MESAMAR_ADMIN_USER` / `MESAMAR_ADMIN_PASSWORD`. A senha é codificada (BCrypt) na subida. **Sem senha configurada, o painel fica trancado** (nenhum login funciona), em vez de cair num usuário padrão.
- **Login por formulário próprio** com CSRF, sessão só para quem entra no painel, e **limite de 10 tentativas por IP a cada 10 minutos**.
- **CSRF desligado apenas** em `POST /lista-de-espera` e `/api/teste-sinal/**`: são envios anônimos, sem sessão para ser sequestrada. O formulário da landing usa `action` comum (sem `th:action`) de propósito, para o Thymeleaf não gerar token nem cookie de sessão. Um teste garante que as páginas públicas **não criam nenhum cookie**.
- **Cabeçalhos:** Content-Security-Policy restrita ao próprio domínio (mais Cloudflare Analytics e Turnstile, que são opcionais), `frame-ancestors 'none'`, `X-Frame-Options: DENY`, `nosniff` e `Referrer-Policy: strict-origin-when-cross-origin`. Sem scripts inline. Os E2E falham se o navegador registrar qualquer erro de console, o que pega um bloqueio de CSP.
- **CSV do painel** neutraliza células que começam com `= + - @`: um nome malicioso digitado no formulário não vira fórmula ao abrir a planilha.

## Consequências

- Para ter mais de uma pessoa no painel, será preciso trocar o usuário único por usuários no banco (isso já está previsto na Fase 2, com o cadastro do dono).
- Se no futuro entrar algum script de terceiros, a CSP precisa ser atualizada, e os E2E vão acusar.
