# ADR 0011 — Web Push, alerta de atraso e "Pausar pedidos"

- **Status:** aceito
- **Data:** 2026-10-01

## Contexto

O dono atende no sol, com o celular no bolso e a tela bloqueada. O som e o destaque do app (ADR 0010) só valem com o app aberto, então o checkpoint 4.2 leva o aviso de pedido novo e de pedido parado **até o celular bloqueado**, e dá ao quiosque um botão para segurar os pedidos no pico.

## Decisão

**Web Push feito com a criptografia do próprio JDK, sem biblioteca**
- Implementamos a **RFC 8291** (mensagem cifrada com ECDH P-256 e HKDF) sobre o conteúdo `aes128gcm` da **RFC 8188**, e a autenticação do servidor pelo **VAPID (RFC 8292)**: JWT `ES256` assinado em formato `R||S`. Motivo: as bibliotecas Java de Web Push são poucas, pouco mantidas e puxam dependências de criptografia de terceiros; o algoritmo cabe em ~150 linhas de JDK e fica sob o nosso controle e testes.
- **Conferido contra a RFC:** o corpo cifrado do exemplo da RFC 8291 sai **idêntico, byte a byte**, e cada valor intermediário do Apêndice A (segredo ECDH, `PRK_key`, IKM, PRK, chave de conteúdo e nonce) bate. O texto da RFC mostra "Content-Length: 145", mas o corpo que ele mesmo traz tem 144 bytes (3 linhas de 64 caracteres em base64): conferimos o corpo, não esse número. O lado do aparelho (decifrar) também é testado, e a assinatura VAPID é verificada com a chave pública.
- **Chaves VAPID só no `.env`** (`MESAMAR_VAPID_PUBLIC_KEY`, `MESAMAR_VAPID_PRIVATE_KEY`, `MESAMAR_VAPID_SUBJECT`). O par se gera uma vez, no computador do Matheus, com `./gradlew --console=plain -q :backend:gerarVapid` (nada é gravado). Sem as chaves, o push fica desligado: o app mostra o motivo em vez de um botão que não funciona, e o resto do sistema segue igual. Trocar as chaves depois obriga todo mundo a reativar os avisos. Os testes usam as chaves de exemplo da RFC (públicas).

**Inscrições (um aparelho, uma pessoa)**
- O app pede a permissão, inscreve o aparelho no navegador e manda o `endpoint` e as chaves ao servidor (`/api/staff/push/inscrever`), sempre na barraca de quem está logado. Inscrever de novo é inofensivo, e o app confirma a inscrição toda vez que abre.
- **O servidor só envia para serviços de push conhecidos** (Google/FCM, Mozilla, Apple e Windows), por `https` e sem porta, usuário ou fragmento na URL, e **não segue redirecionamentos**. Quem chama o endereço é o servidor, e o `endpoint` vem do navegador de alguém logado: sem essa trava, daria para fazê-lo acessar endereços internos (SSRF). Hosts parecidos (`fcm.googleapis.com.malvado.com`), IPs e `localhost` são recusados (testado com as propriedades de produção).
- A chave do aparelho precisa ser um **ponto válido da curva P-256** (a RFC exige) e o segredo de autenticação, ter 16 bytes. Máximo de 40 aparelhos por barraca.
- Um endpoint que já existe em **outra barraca** só muda de dono se as chaves forem as mesmas (o mesmo aparelho entrando em outro login). Chaves diferentes dão 409: ninguém toma a inscrição de outro. Cancelar só mexe na inscrição **da própria barraca**.
- **Atendente desativado deixa de receber** (a inscrição é removida na hora do envio), e **"Sair deste celular"** desativa os avisos naquele aparelho. As inscrições têm FK composta `(pessoa, barraca)`: não dá para inscrever um atendente na barraca de outro.

**Entrega**
- Só depois do **commit** do pedido (`@TransactionalEventListener(AFTER_COMMIT)`), numa thread virtual: o cliente que está pedindo nunca espera os serviços de push, e um serviço fora do ar não atrapalha ninguém (testado).
- **O texto do aviso não leva o nome do cliente**: "Pedido nº 23 · Guarda-sol 12" e "3 itens" (ou "Balcão"). Vai cifrado, então só o aparelho lê. Validade (TTL) de 5 min para pedido novo e 10 min para atraso: um aviso velho de pedido já entregue não tem valor. Urgência alta.
- Resposta **404 ou 410** do serviço: a inscrição não existe mais e é removida. Outros erros (5xx, rede) **não** removem: são temporários.
- **O pedido que aguarda pagamento não avisa** (ADR 0009): só avisa quando entra na fila.
- No navegador, o service worker **sempre mostra a notificação** quando chega um aviso: o Chrome exige isso (`userVisibleOnly`) e, sem ela, mostra um aviso genérico feio. Tocar nela abre a fila (reaproveita o app se já estiver aberto).
- O cartão **"Avisos de pedido"** na aba Mais explica cada situação (servidor sem chaves, iPhone sem instalar, permissão negada, navegador sem suporte) e tem **"Mandar um aviso de teste"**, que vai só para o aparelho de quem pediu.

**Alerta de pedido atrasado**
- Uma tarefa a cada 30 s procura pedidos `RECEBIDO` ou `EM_PREPARO` parados além do `tempo_alerta_pedido_min` **da própria barraca** e avisa **uma vez só** por pedido (a hora fica em `alerta_atraso_em`): push para os aparelhos e um evento `atraso` para o app aberto, que toca três bips graves e mostra um aviso.
- **Sem ShedLock.** O plano previa uma trava de tarefa agendada para não rodar em dobro com dois servidores. `SELECT … FOR UPDATE SKIP LOCKED` dá a mesma garantia com uma linha de SQL: cada pedido é pego por **um** servidor, e quem chega depois pula os pedidos já travados. Testado com 6 verificações ao mesmo tempo sobre 3 pedidos: 3 avisos, nenhum em dobro. Uma dependência a menos e o mesmo efeito. Se um dia houver outras tarefas agendadas que precisem de exclusão, uma trava por `pg_try_advisory_lock` serve.
- **Não criamos a tabela `Notificacao`** do modelo do plano (histórico e "lida em"): o MVP não tem caixa de notificações, só o aviso na hora. O que precisamos (uma vez por pedido) está em `alerta_atraso_em`.

**"Pausar pedidos"**
- Botão na aba Fila, com um recado opcional de até 80 letras ("Voltamos em 10 minutos"). Dono **e atendentes** podem usar (quem está no balcão sente o pico antes do dono). Vale na hora: o servidor recusa pedido novo (409 `pedidos-pausados`) e **todas as comandas da barraca são avisadas em tempo real**, assim como o app dos outros atendentes. Quem já pediu continua na fila. Repetir a mesma pausa não avisa ninguém de novo.

## Consequências

- **iPhone:** o Web Push só existe com o app instalado na tela inicial (iOS 16.4 ou mais novo). O app avisa isso no cartão de avisos. No Android, funciona no Chrome, instalado ou não.
- **Só funciona em HTTPS** (ou `localhost`): em produção está garantido pelo Cloudflare; testar no celular pela rede local (`http://IP-DO-PC`) não dá.
- O push depende de serviços de terceiros (Google, Apple…): atraso ou perda são possíveis em rede ruim, e é por isso que o alerta de atraso e o app aberto continuam existindo.
- O mesmo `endpoint` é um segredo: está no banco, em texto, junto com as chaves do aparelho. Quem lê o banco poderia mandar avisos aos aparelhos (mas não ler nada deles). Aceito, como o token da comanda (ADR 0007).
