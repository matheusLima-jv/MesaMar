# ADR 0016 — Revisão final: sessões, bloqueio de PIN, cancelamento depois de pagar e encerramento diário

- **Status:** aceito (aguardando a aprovação do 7.4)
- **Data:** 2026-10-02

## Contexto

Antes do piloto, a revisão final do código apontou sete pontos em que o sistema podia ser derrubado, enganado ou deixar dado para trás. Todos foram corrigidos juntos, porque mexem nas mesmas peças (login, fila e pagamento).

## Decisões

**1. Trocar a senha do dono derruba as sessões abertas.**
- Coluna `usuario.credenciais_em` (V15). É preenchida quando a senha muda pelo link do e-mail e quando a demonstração é reiniciada.
- O login guarda a hora em que aconteceu (`logadoEm`). A cada requisição do painel (`OwnerPrincipalCheckFilter`) e do app (`StaffPrincipalCheckFilter`), se `credenciais_em` for posterior ao login, a sessão deixa de valer: o painel manda para o login e o app responde 401.
- Motivo: celular perdido ou senha vazada. Antes, quem já estava logado continuava dentro depois da troca.
- A troca de senha **dentro do painel** não existe hoje; se vier, ela precisa gravar `credenciais_em` e manter a sessão de quem trocou.
- Atendentes não têm senha: trocar o PIN não derruba a sessão aberta (o dono desativa o atendente, e isso já derruba na hora).

**2. O bloqueio de PIN é por origem (IP), com um teto por atendente.**
- Antes: 5 PINs errados bloqueavam o atendente para todos. Quem soubesse o código da barraca (que é público) trancava a equipe inteira errando de propósito.
- Agora: 5 erros de **uma origem** bloqueiam aquela origem por 15 minutos, sem trancar a atendente no celular do quiosque. 20 erros (sem um acerto no meio) bloqueiam a atendente para todos; o dono desbloqueia no painel. Acertar o PIN, trocar o PIN ou desbloquear zera tudo.
- **Cada origem soma no máximo 5 erros ao teto da conta** (correção da revisão final): sem isso, um único IP, esperando cada bloqueio de 15 minutos vencer, chegava aos 20 em cerca de 1 hora e trancava a equipe toda. Agora o teto só é atingido de pelo menos 4 lugares diferentes. Um lugar sozinho continua limitado a 5 tentativas a cada 15 minutos.
- **O contador por origem fica em memória.** Vale para um servidor só (o piloto) e zera quando o servidor reinicia. Com dois servidores, cada um conta os seus. É o mesmo limite já conhecido do `RateLimiter`.
- A origem é o `remoteAddr`. Atrás do proxy da produção, isso depende da configuração de cabeçalhos encaminhados que o `RateLimiter` já usa. **Se várias barracas saírem pelo mesmo IP (4G compartilhado, por exemplo), elas dividem o contador por origem**; o teto de 20 por atendente continua valendo para cada uma.

**3. Cancelar o que o cliente já pagou é recusado.**
- Cancelar pedido ou item que deixaria o saldo negativo (`itens − cancelado − pago < 0`) devolve 409 com a mensagem "O cliente já pagou por isso… combine a troca ou devolva o valor no balcão".
- Motivo: o sistema não registra devolução de dinheiro, e uma conta negativa quebraria o relatório (vendido = recebido + em aberto + não recebido). Antes de qualquer pagamento, cancelar continua sempre possível.

**4. Cancelar também libera pedidos que esperavam pagamento (`LiberacaoPedidos`).**
- No modo "pagar a cada pedido", cancelar o pedido da frente pode deixar o seguinte coberto pelo que já foi pago. A liberação, que antes só rodava depois de um pagamento, passou para uma classe própria, usada pelos dois lados.
- A ordem das travas é sempre **comanda e depois pedido**, nos dois caminhos, para não haver deadlock; cada pedido é relido do banco depois de travado.

**5. Encerramento diário (4h, Brasília).**
- Comandas de dias anteriores, abertas ou aguardando pagamento e **sem saldo**, são encerradas. Quem ainda deve continua aberto.
- Motivo: no modo "pagar a cada pedido" (ou quando o cliente pagava sem tocar em "Fechar conta") a comanda nunca fechava. O link antigo continuava aceitando pedido e o cliente nunca era anonimizado.
- `mesamar.encerramento.cron` muda o horário. A anonimização roda às 3h30, então a comanda encerrada às 4h só é anonimizada na madrugada seguinte (a anonimização espera a comanda fechar).

**6. A anonimização também apaga os avisos de "reportar problema"** com mais de 90 dias (o mesmo prazo `mesamar.anonimizacao.dias`). O texto livre pode ter dado pessoal. A política de privacidade foi ajustada.

**7. CSP sem terceiros nas páginas dos apps.**
- `/c/**`, `/cliente/**` e `/staff/**` saem com uma política sem nenhum serviço de fora. O endereço da comanda é a credencial do cliente e não pode ir para um script de terceiros (por exemplo, o Web Analytics "automático" da Cloudflare, que se injeta sozinho).
- A landing continua liberando Web Analytics e Turnstile. Admin, painel e APIs também usam a política sem terceiros.

## Consequências

- Quem fica logado e troca a senha por fora do fluxo (direto no banco) não derruba nada: só o fluxo do e-mail e a demonstração gravam `credenciais_em`.
- Um atendente que erra o PIN 5 vezes no celular do quiosque continua bloqueado ali por 15 minutos. O dono não vê isso no painel (só o bloqueio de 20), mas "Desbloquear" limpa as duas coisas.
- O cancelamento depois do pagamento passa a depender de conversa no balcão. Se o piloto mostrar que isso é frequente, o próximo passo é registrar devolução (Fase 8).

## Testes

`RevisaoFinalTest` (7), `EquipeTest` (12, três novos para o PIN), `SecurityHeadersTest` e `AnonimizacaoTest` (um novo cada).
