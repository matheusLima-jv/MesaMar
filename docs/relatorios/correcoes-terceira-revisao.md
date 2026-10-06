# Correções da terceira revisão externa

Data: 2026-10-06. Branch `correcoes-terceira-revisao`, a partir de `correcoes-revisao-externa`. Banco continua em V21.

## 1. O que foi feito

Uma terceira revisão, feita por outro modelo, listou 13 achados (3 altos, 9 médios, 1 baixo). Cada um foi conferido no código e, quando dava, reproduzido. Resultado: **10 procedem, 2 procedem só em parte e 1 não é bug.** Nenhum foi inventado do zero, mas alguns impactos estavam exagerados e um estava subestimado.

| Achado | Veredito | O que de fato acontecia | Correção |
|---|---|---|---|
| A01 Último login regravava o usuário inteiro | **Procede** | O login lia o usuário e o salvava inteiro (merge do JPA). Uma troca de senha confirmada entre a leitura e a gravação era desfeita: a senha antiga voltava a valer. O mesmo valia para a barraca recém-criada, que se perdia e permitia criar outra. Janela curta, consequência grave | Grava só `ultimo_login_em` (`registrarLogin`) |
| A02 Sessão validada pela hora do login | **Procede** | No app da equipe, a hora do dono era anotada **depois** do BCrypt. Uma troca de senha confirmada durante a conferência deixava valendo a sessão aberta com a senha antiga. Painel e atendente tinham a mesma falha, com janela de microssegundos | A sessão guarda o marcador lido junto com a credencial e só vale enquanto ele for igual ([ADR 0024](../adr/0024-sessao-pela-credencial-e-recusa-vista.md)) |
| A03 Pagamento repetido mostrava outro valor | **Procede** (o revisor classificou como alta; na prática é média) | Depois de uma resposta perdida, o diálogo deixava mudar valor e forma e reenviava com o mesmo id. O servidor devolvia o pagamento já gravado (R$ 20), mas a mensagem dizia "Recebido R$ 30". O valor registrado sempre esteve certo; a mensagem é que enganava | Valor e forma ficam travados desde o primeiro envio; a mensagem usa o valor que o servidor registrou; recusa avaliada destrava |
| A04 Check-in com telefone longo e recusa na fila | **Procede, e era pior do que o descrito** | (1) WhatsApp com mais de 25 caracteres travava o botão em "Gerando…". (2) **Qualquer** recusa do servidor vista na tela virava conflito no topo da fila: check-in inválido, "o preço mudou", comanda encerrada. Até alguém descartar no indicador, os envios seguintes ficavam "preservados no aparelho" mesmo com sinal, e o check-in corrigido reenviava os dados antigos | Limite de 25 no campo e conferência local; a tela que mostra a recusa tira a ação da fila; check-in pendente recusado volta a ser editável |
| A05 QR antigo sem prazo | **Procede**, impacto baixo | Já estava registrado como P3 no relatório anterior. Exige o token antigo **e** o id do pedido, que só o celular que fez o pedido conhece | O link antigo segue o prazo de 7 dias do extrato e o limite de pedidos |
| A06 Resumo do Portão A misturava métricas | **Procede** | Pedido, fila e pagamento (sem tempo de abertura) contavam como falha. Exemplo do revisor confirmado: 1 abertura de 2 s + 9 eventos de fila davam 10%. Mediana sem amostra aparecia como 0 | Conta só teste de sinal e primeira abertura; "—" sem amostra; tipo de cada medição na lista |
| A07 Teto de 40 aparelhos furado na transferência | **Procede**, impacto baixo | Exige alguém com sessão nas duas barracas | O teto vale também para o aparelho que vem de outra barraca |
| A08 Conciliação carrega todos os pedidos | **Não é bug** | Só roda em reenvio de pedido do cliente, olha poucos pedidos da própria comanda e para no primeiro que bate | Sem mudança |
| A09 Turnstile sem prazo (o revisor marcou como suspeita) | **Procede, confirmado** | A imagem não traz Apache, Jetty nem Netty, então o `RestClient.create()` usa o cliente HTTP do Java sem prazo de conexão nem de leitura. Uma Cloudflare lenta prenderia o formulário da lista de espera e uma thread do servidor | Conexão em 5 s, leitura em 10 s; a recusa continua "falha fechada" |
| A10 Monitor media a idade pelo fim do backup | **Procede**, efeito pequeno | O alerta de RPO atrasava o tempo que o backup leva (dump, restauração de verificação, S3) | O marcador guarda o instante anterior ao dump |
| A11 Exclusão LGPD gravada depois de anonimizar | **Procede**, janela pequena | Uma falha entre as duas etapas (disco cheio, Ctrl+C) deixava a exclusão fora do registro usado na restauração | Registra primeiro, com `sync`, e depois anonimiza; anonimizar duas vezes não faz mal |
| A12 Máscara do Nginx pegava um parâmetro só | **Procede em parte** | Duas credenciais na mesma URL o app nunca gera. Testado num Nginx real, a configuração antiga vazava o segundo valor. O ponto mais concreto o revisor citou só de passagem: o servidor da porta 80 usava o formato padrão e gravava `http://…/c/<token>` inteiro | O log grava só o caminho, com o token mascarado e a query trocada por `?***`; a porta 80 usa o mesmo formato |
| A13 Quebra de linha no log de problema | **Procede só no código** | `tela` e `conexao` mantinham `\n`, mas em produção o log é JSON (ECS), que escapa a quebra. Não dava para forjar linhas no servidor, só no log de texto do desenvolvimento | `tela`, `conexao` e navegador viram uma linha só (inclusive U+0085, U+2028 e U+2029); a mensagem guarda as quebras |

Encontrado durante a conferência, fora da lista do revisor: quando uma ação **mais antiga** da fila era recusada enquanto a tela esperava a sua, a tela recebia a recusa da outra como se fosse dela. No lançamento, isso gerava um id novo. Quando o conflito era descartado, o pedido original e o novo seguiam juntos: **pedido duplicado**. Corrigido junto com o A04.

## 2. Decisões

[ADR 0024](../adr/0024-sessao-pela-credencial-e-recusa-vista.md):

- A sessão (painel, app do dono e atendente) vale enquanto o marcador de credencial for **igual** ao lido junto com o hash ou PIN conferido. A hora do login saiu da sessão.
- A recusa mostrada na tela que enviou a ação sai da fila. A recusa que ninguém viu continua no indicador para descarte explícito (ADR 0020).
- Sessão vencida, CSRF, 408 e 429 não liberam a correção do check-in nem do lançamento: o mesmo id é repetido, como já acontecia no pagamento.
- O Portão A conta teste de sinal e primeira abertura. O log do Nginx não grava query string. O marcador do backup guarda a idade dos dados.

## 3. Como rodar e testar

Na raiz, com Docker ativo e JDK 21:

```powershell
$env:JAVA_HOME='C:/Program Files/Amazon Corretto/jdk21.0.12_8'
./gradlew.bat ktlintCheck build
```

No diretório `web-staff`: `npm run build` (lint, testes, TypeScript e bundle).

## 4. Resultados automatizados

- `ktlintCheck` e backend: **507 testes**, zero falhas, erros ou ignorados (eram 497; 10 novos).
- `web-staff`: **32 testes** (eram 19), lint, TypeScript e bundle aprovados.
- Conferência ao contrário: com o código de produção anterior e os testes novos, **8 dos 10 testes de backend falham** (A01, os três do A02, A05, A06, A07 e A13). Os dois que passam são o prazo do Turnstile (testa o cliente novo, que não existia) e a guarda de que a sessão continua valendo sem troca de senha. No `web-staff`, os 5 testes do check-in falham com o formulário antigo, assim como 2 do pagamento, 2 do lançamento e o da recusa alheia na fila.
- Nginx: o mapa novo foi testado num `nginx:1.27-alpine` com 12 URLs (token no caminho, `?token=`, `?convite=`, parâmetros repetidos). Nenhum valor chegou ao log. O mapa antigo, com as mesmas URLs, vazava `convite=…` e o `?token=` depois de `/c/***`.
- `backup.sh` e `excluir-cliente.sh`: `bash -n` e ShellCheck sem avisos; o teste do monitor (`.github/scripts/testar-alertas.py`) passa.

Testes novos:

- `CorrecoesTerceiraRevisaoTest` (backend): corrida do último login com troca de senha; troca de senha durante o BCrypt do login do dono no app; sessão do painel e do atendente depois de troca ou revogação não vistas pelo login; link antigo depois de 7 dias; resumo do Portão A com métricas misturadas; teto de aparelhos na transferência; Turnstile mudo; problema reportado com quebras de linha.
- `web-staff/tests`: `fila-recusa.test.cjs` (fila real em IndexedDB de memória), `checkin.test.cjs`, `receber.test.cjs` e dois casos novos em `lancar.test.cjs`.

Playwright e os ensaios Bash completos de backup não foram repetidos nesta branch.

## 5. Validação manual

[Roteiro de sete passos](../testar-localmente.md#correções-da-terceira-revisão). O passo do Nginx depende de copiar o arquivo novo para o proxy ([deploy.md](../deploy.md#nginx-log-de-acesso-sem-credenciais)); nada foi alterado no servidor.

## 6. Limitações e riscos

- **Sessões abertas antes da atualização** deixam de valer. Hoje elas já não sobrevivem a reinícios, porque ficam em memória.
- **A08** fica como está. Se a conciliação um dia pesar, o caminho é filtrar origem e data no banco e carregar os itens dos candidatos de uma vez.
- **Log de erros do Nginx** não aceita formato e pode conter o caminho com token quando o upstream falha. Está anotado no deploy.md.
- **A corrida do A02 no atendente** foi testada pelo estado que ela deixa (revogação não vista pelo login), não pela intercalação real, que dura microssegundos. A do dono no app foi reproduzida de fato, com a troca confirmada durante a conferência da senha.
- Os dois itens P3 do relatório anterior que não estavam nesta lista (push pendente após trocar de barraca e duplicação após a janela de conciliação) continuam abertos.

## 7. Próximo passo

Validar este checkpoint. O PR #27 já está na `main`, mas o PR #28 (`correcoes-revisao-externa`) foi mergeado na `correcoes-auditoria-geral` depois disso e não chegou à `main`. Esta branch parte dele, então levá-la à `main` leva as duas entregas. Depois, preparação do piloto.

⏸️ Aguardando validação. Responda "APROVADO CORREÇÕES DA TERCEIRA REVISÃO" ou "AJUSTAR CORREÇÕES DA TERCEIRA REVISÃO: ...".
