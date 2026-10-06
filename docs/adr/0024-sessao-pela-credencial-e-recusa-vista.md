# 0024 — Sessão presa à credencial conferida e recusa vista na tela

Data: 2026-10-06. Complementa as ADRs 0020 (fila offline) e 0022. Esquema inalterado (V21).

## Contexto

Uma terceira revisão externa apontou corridas no login e na fila do app da equipe. Conferidas no código:

1. A sessão do dono (painel e app) e a do atendente valiam enquanto a última troca de senha ou revogação fosse **anterior à hora do login**. Essa hora era anotada depois de ler a credencial. No app, era anotada depois do BCrypt. Uma troca confirmada durante a conferência ganhava horário anterior ao da sessão, e a sessão aberta com a senha antiga continuava valendo.
2. Pela ADR 0020, toda recusa do servidor vira conflito no topo da fila e espera descarte explícito. Isso vale também para a recusa que a pessoa acabou de ver na tela (WhatsApp inválido, o preço mudou, comanda encerrada). Enquanto ninguém descartava no indicador, os envios seguintes ficavam "preservados no aparelho" mesmo com sinal. O check-in corrigido reenviava os dados antigos, porque o mesmo id já estava na fila.

## Decisão

1. **A sessão guarda o marcador lido junto com a credencial.** O dono guarda `credenciais_em` lido no mesmo registro do hash que o BCrypt conferiu. O atendente guarda `sessoes_revogadas_em` lido com a linha travada, junto com o PIN. A cada requisição (e na revisão das conexões em tempo real), a sessão vale se o banco tiver **o mesmo valor**. Qualquer troca posterior derruba a sessão, sem depender de relógio. A hora do login deixa de existir na sessão.
2. **Recusa vista na tela sai da fila.** Quando a tela que enviou o check-in ou o lançamento ainda está aberta e mostra a recusa do servidor, a própria tela tira a ação recusada da fila. A pessoa então corrige e envia de novo. Recusa que chega sem ninguém na tela (reenvio em segundo plano, ou a pessoa já saiu) continua como conflito no indicador, com descarte explícito, como na ADR 0020.
3. **A recusa de outra ação não é a resposta desta.** Se uma ação mais antiga da fila é recusada enquanto a tela espera a sua, a tela recebe "preservada no aparelho" e não a mensagem da outra. Antes, a tela entendia a recusa alheia como sua, e um novo envio podia duplicar o pedido quando o conflito fosse descartado.
4. **Só recusa avaliada pelo servidor libera a correção.** Sessão vencida (401), CSRF, 408 e 429 não dizem se um envio anterior chegou. Nesses casos o check-in e o lançamento continuam pendentes com o mesmo id, como o pagamento desde a ADR 0023.

Decisões menores, registradas aqui por serem definições:

- **Portão A:** o resumo "Sinal por praia" conta o teste de sinal e a primeira abertura dos apps. Pedido, fila, pagamento e reabertura (vinda do cache) ficam só na lista de medições. Mediana sem amostra aparece como "—", não como 0.
- **Log do Nginx:** o log de acesso registra só o caminho, com o token de `/c/` mascarado, e troca qualquer query string por `?***`. O servidor da porta 80 usa o mesmo formato.
- **Marcador do backup:** `ultima-copia-externa.txt` guarda o instante dos dados copiados (antes do dump), e não o fim do envio. O monitor mede a idade dos dados.

## Consequências

- Uma sessão aberta durante uma troca de senha cai na próxima ação, mesmo que a troca tenha sido marcada um instante antes do login. Quem entrou com a senha nova não é afetado.
- Sessões abertas antes desta versão perdem a validade na atualização. Hoje elas já não sobrevivem a reinícios, porque ficam em memória.
- A ordem da fila continua garantida para o que acontece sem ninguém olhando. O que a pessoa viu e corrigiu na hora não trava os envios seguintes.
- O log do Nginx deixa de mostrar parâmetros de busca (paginação, por exemplo). Quando for preciso, use o log da aplicação com o `X-Request-ID`.
