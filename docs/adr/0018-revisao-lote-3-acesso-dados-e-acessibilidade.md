# ADR 0018 — Revisão de código, lote 3: revogação de acesso, dados pessoais e acessibilidade

- **Status:** aceito (aguardando a aprovação do Matheus)
- **Data:** 2026-10-03

## Contexto

A revisão de código apontou, depois dos lotes 1 e 2, oito pontos de acessibilidade e de uso do app (22 a 29) e doze de segurança e tratamento de dados (49 a 60). Este ADR registra as decisões que não são óbvias no código. O detalhe item por item está em `docs/relatorios/fase-6.md`.

## Decisões

**1. O acesso é conferido durante a conexão, não só ao abrir (item 49).**
- `StaffAcesso.valido(principal)` é a mesma conferência que o filtro faz a cada requisição. O `HubEventos` a executa **a cada batimento (25 s)** para cada conexão do app e derruba quem perdeu o acesso (atendente desativado, sessões revogadas, senha do dono trocada, barraca desativada).
- Revogações explícitas (desativar atendente, "sair de todos os aparelhos", redefinir senha) chamam `revisarAcessosAposCommit()`: a conexão cai logo depois do commit, sem esperar o batimento.
- O erro do batimento ao conferir conta como "ainda tem acesso" (um soluço do banco não derruba a equipe inteira).

**2. Revogação explícita de sessões do atendente (item 50).**
- V17: `atendente.sessoes_revogadas_em`. Sessão iniciada **antes** desta hora não vale. Desativar o atendente grava a hora, então **reativar não faz sessões antigas voltarem**.
- Botão novo no painel, em Equipe: **"Sair de todos os aparelhos"** (celular perdido). Também apaga as inscrições de aviso (push) do atendente.
- **Trocar o PIN continua não derrubando sessões** (decisão do ADR 0016, mantida). Para celular perdido, o caminho é o botão novo, e o painel orienta a trocar o PIN também.

**3. Limite de tentativas também por conta (item 51).**
- Login do dono no app: 15 tentativas por e-mail a cada 15 minutos, em qualquer IP (além das 10 por IP). Login do painel: igual, pelo campo e-mail. O atendente já tinha contador por atendente (ADR 0016).
- O texto "deste aparelho" virou "desta conexão": o limite é por IP, e vários aparelhos na mesma rede o dividem.

**4. Credenciais fora de logs e de cabeçalhos (itens 52 e 53).**
- `Referrer-Policy: no-referrer` em tudo (e na página do cliente, que tinha `same-origin` na meta). O endereço da comanda, do convite e da troca de senha nunca vai no `Referer`.
- `deploy/nginx-mesamar.conf`: log de acesso com `map` que troca o token por `***` (`/c/<token>`, `/api/c/<token>`, `?token=`, `?convite=`). Testado num Nginx de verdade. **O Matheus precisa copiar o arquivo** (`docs/deploy.md`).
- E-mail sem SMTP: em produção **não escreve** o destinatário nem o link no log. `mesamar.mail.logar-corpo-sem-smtp` (padrão `false`) liga isso só no compose de desenvolvimento (`MESAMAR_MAIL_LOGAR_CORPO=true`).

**5. QR novo (item 54).**
- `POST /api/staff/comandas/{id}/novo-qr` troca `token_acesso`; o antigo vai para `token_anterior` (V17). Comanda, pedidos e saldo ficam. Só comanda ativa, só da barraca de quem pede. As conexões em tempo real do link antigo caem.
- **Conciliação com o que estava pendente:** o link antigo não abre mais nada, **exceto** reenviar um pedido que **já existe** naquela comanda (devolve 200 e a página para de reenviar). Pedido que não chegou a existir dá 404 e o atendente lança de novo pelo app. O app avisa isso antes de trocar.

**6. Dados locais com validade (item 55).**
- Página do cliente: dados de uma comanda (carrinho, pedido em envio, avisos lidos) saem do navegador depois de **7 dias sem abrir** aquela comanda, ou na hora se o link responder 404. Sem data (de antes da regra), ganha a de hoje, para nunca apagar um pedido pendente por engano.
- Service worker: a cópia da comanda guardada vence em 7 dias (já guardava no máximo 3).
- App da equipe: check-in, lançamento e pagamento sem resposta vencem em **3 dias**.

**7. Texto livre e dados pessoais (itens 57 e 58).**
- A anonimização dos 90 dias agora **limpa o texto livre** (observação do item, detalhe do motivo de cancelamento, motivo do fechamento sem receber). Fica só a categoria ("Esgotou", "Cliente desistiu", "Outro"), que é o que o relatório usa. Roda todo dia e também limpa o que ficou de antes da regra. **Isto muda o ADR 0014** (que deixava o texto livre).
- Os campos de texto livre dos apps avisam "não escreva dados pessoais".
- **O push de "aviso de problema" não leva mais o texto digitado** (tela bloqueada do dono). Diz só "Toque para ler o aviso no painel". **Isto muda o ADR 0016, item 9** (que mandava o começo do texto).

**8. Versão do texto de consentimento (item 59).**
- `cliente.consentimento_versao` (V17). O app manda a versão do texto que estava na tela; o servidor grava (`desconhecida` se faltar ou vier fora do formato; `NULL` nos cadastros de antes). Os textos ficam em `docs/consentimento.md`; um teste confere que app, servidor e documento têm a mesma versão.

**9. Procedimento de correção e exclusão (item 60).**
- Manual no MVP, com scripts em `deploy/lgpd/` (`corrigir-cliente.sh`, `excluir-cliente.sh`, `reaplicar-exclusoes.sh`) e passo a passo em `docs/lgpd-procedimento.md`.
- Cada exclusão grava o **UUID do cliente** (sem nome nem telefone) em `/opt/mesamar/lgpd/exclusoes.txt`, **fora do banco**: um registro dentro do banco voltaria no tempo junto com uma restauração. O `restaurar.sh` reaplica todas as exclusões depois de restaurar, e se falhar manda **não subir a aplicação**.
- O SQL da exclusão é testado contra o esquema real (`LgpdSqlTest`).

**10. Acessibilidade e uso (itens 22 a 29).**
- Modais (`ativarModal`): o resto do app fica `inert`, o Tab não sai do modal, o Esc fecha e o foco volta a quem abriu (ou ao conteúdo, se ele sumiu).
- Atualização automática (`mountPreservando`): guarda foco, cursor e rolagem e os devolve no mesmo elemento. Quem está num campo (digitando, ou com a lista de um select aberta) **não é redesenhado**: a atualização espera ele sair.
- ARIA: o app inteiro deixou de ser uma região `aria-live` (os avisos têm a sua região); os guarda-sóis, as abas do login e a forma de pagamento viraram radios e abas com teclado (setas, Home, End); o erro de cada campo fica ligado a ele (`aria-invalid`, `aria-describedby`).
- Clientes: busca por nome ou guarda-sol (sem acento) e filtro pela situação da comanda.
- Avisos que não somem: pedido parado e "cliente indo pagar" ficam numa faixa fixa, em qualquer aba, derivados do que o servidor mostra.
- A rolagem ao trocar de aba é da página (antes se mexia num elemento que não rola). Nomes longos quebram em vez de sumir; abas rolam na horizontal com zoom grande; a barra de abas e a barra do pedido saem da frente com o teclado aberto; viewport com `interactive-widget=resizes-content`.
- O login do dono aponta para "Esqueci a minha senha" (o fluxo do painel); o do atendente diz que o PIN é trocado pelo dono.

## Consequências

- `docs/deploy.md` ganhou passos para o Matheus (Nginx com log mascarado, pasta do registro de exclusões na cópia para fora da VPS).
- Há **dois pontos em que mudei uma decisão anterior** (7): o texto livre agora é anonimizado e o push de problema não leva o texto. Se preferir o comportamento antigo, é pequeno de desfazer.
- Zoom de 200%, leitor de tela (TalkBack/VoiceOver) e teclado aberto de verdade **não têm como ser testados por automação aqui**: o E2E cobre largura estreita, foco, Tab e setas; o resto está no roteiro manual.
