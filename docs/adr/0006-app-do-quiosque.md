# ADR 0006 — App do quiosque: sessão, CSRF e quem pode o quê

- **Status:** aceito
- **Data:** 2026-09-30

## Contexto

O checkpoint 2.4 cria o app instalável do quiosque (`/staff`, PWA), usado pelo dono e pelos atendentes o dia inteiro, no celular. Ele conversa com o servidor por uma API JSON (`/api/staff`). Nas próximas fases, o app vai receber a fila em tempo real, o check-in e os pagamentos.

## Decisão

- **Sessão com cookie `HttpOnly`, e não token no JavaScript.** Um token guardado no `localStorage` fica exposto a qualquer script que rode na página, e o cookie não. A sessão do app tem **chave própria** (`MESAMAR_STAFF`), separada do `/painel` e do `/admin`: entrar num não dá acesso aos outros.
- **CSRF no cabeçalho `X-CSRF-TOKEN`.** O app pega o token em `GET /api/staff/sessao` e o envia em todo POST. Se a sessão expirou no servidor, a API responde 403 `csrf`, e o app busca um token novo e repete a chamada **uma vez**.
- **Sem redirecionar para páginas de login.** A API responde 401/403 em JSON, e o app volta sozinho para a tela de entrada.
- **Troca do id da sessão no login**, contra fixação de sessão.
- **Atendente entra com código da barraca + nome + PIN** (ver o relatório do 2.2). O app lembra o código e o último nome **no aparelho**, o que reduz o segundo login a "tocar no nome + PIN".
- **Quem pode o quê:**

  | Ação | Dono | Atendente |
  |---|---|---|
  | Marcar esgotado / voltou | ✅ | ✅ (é operação do dia: acabou o coco) |
  | Preços, produtos, Pix, regras, equipe | ✅ no painel | ❌ |
  | Check-in, fila, pagamentos | ✅ | ✅ (próximas fases) |

- **Acesso revalidado a cada chamada.** Se o dono desativa um atendente, o próximo clique dele no app já volta para o login, sem esperar a sessão expirar.
- **"Esgotado" pela API é um "definir" (`{esgotado: true}`), e não um "alternar".** Um reenvio (sinal ruim) não desfaz a ação. É também o formato que a fila offline da Fase 6 precisa.
- **Front sem framework** (TypeScript puro + Vite): 12 KB de JS, ou 4,8 KB com gzip. Todo texto entra por `textContent` (sem `innerHTML`).
- **Service worker só para abrir o app sem rede e guardar os arquivos com hash.** A API **nunca** passa pelo cache. O modo offline de verdade, com fila de ações para reenviar, fica para a Fase 6, como no plano.

## Consequências

- O app e a API precisam estar no mesmo domínio (cookie `SameSite=Lax`), o que já é o caso: tudo em `mesamar.0mega.fun`.
- Quem limpar os dados do navegador precisa digitar o código da barraca de novo.
