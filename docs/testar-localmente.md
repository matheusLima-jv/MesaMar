# Verificação atual (revisões 61–110)

Os roteiros históricos abaixo documentam entregas anteriores. Recursos de cliente, fila, pagamento e offline já estão disponíveis. Mudanças de regras afetam os próximos pedidos de contas existentes, preservando preços de pedidos gravados.

```bash
./gradlew ktlintCheck build
(cd web-customer && npm ci && npm run build)
(cd web-staff && npm ci && npm run build)
python3 .github/scripts/testar-alertas.py
cd e2e
npm ci
npx playwright install chromium webkit
E2E_ADMIN_PASSWORD=<senha obrigatória do ambiente local> npx playwright test
```

O E2E falha na configuração quando falta senha; não pula cenários administrativos. Use banco descartável, fixtures isoladas e Chrome/WebKit. Console e HTTP inesperados são conferidos em todos os contextos até o fim. Testes de conflito declaram somente os status esperados.

Valide check-in e pedido offline, reabertura sem rede, ordem, token preservado, indicador de pendências, conflito que interrompe a fila, saída sem rede e troca de autor/barraca. Pagamento e avanço da fila exigem confirmação online. Ensaios unitários injetam falhas de corpo após cabeçalhos, quota, cache antigo e respostas fora de ordem.

Em aparelhos reais: Android e iPhone pequenos; primeira visita e primeira reabertura offline; instalação e atualização; equipe e cliente no mesmo navegador; zoom 200%, teclado, VoiceOver/TalkBack; permissão/entrega push; Pix, QR e retorno do banco. Emuladores e teclado automatizado não substituem essas verificações.

Recuperação e alertas: [procedimentos atuais](deploy.md). Execute o ensaio de restauração contra cópia externa com credenciais reais e cronometre todo o processo (RPO 24 h/RTO 2 h). O harness `.github/scripts/testar-backup-no-banco.sh` destina-se exclusivamente a container PostgreSQL descartável, com `MESAMAR_OPERACAO_TESTE=1`; nunca o execute em produção.

---

# Testar tudo na sua máquina antes do deploy

Roteiro de ~30 minutos para validar a Fase 1 inteira localmente, do jeito que ela vai rodar em produção.

## 1. O que instalar

| Ferramenta | Para quê | Obrigatório? |
|---|---|---|
| **Git** | Baixar o código | Sim |
| **Docker Desktop** (ou Docker Engine + Compose v2) | Rodar app + banco com um comando | Sim |
| **Node 22** | Rodar os testes E2E (Playwright) | Só para o passo 5 |
| **JDK 21** | Rodar `./gradlew build` fora do Docker (testes de backend, IDE) | Só para o passo 6 |

No Windows, use o Docker Desktop com WSL2. O repositório tem `.gitattributes` para o `gradlew` não virar CRLF.

**Portas livres:** `8080` (app) e `5432` (banco, só em `127.0.0.1`). Se você já tiver um Postgres local na 5432, troque no `docker-compose.yml` para `127.0.0.1:5433:5432`.

## 2. Baixar e configurar

```bash
git clone https://github.com/matheusLima-jv/MesaMar.git
cd MesaMar
git checkout fase-1-landing        # enquanto o PR #2 não for mergeado

cp .env.example .env
```

No `.env`, ajuste pelo menos:

```dotenv
MESAMAR_ADMIN_PASSWORD=uma-senha-sua
MESAMAR_WHATSAPP=5513XXXXXXXXX     # o seu número, só dígitos (faz o botão aparecer)
```

Deixe `MESAMAR_PUBLIC_URL=http://localhost:8080` localmente. O `https://mesamar.0mega.fun` só entra no `.env` da VPS.

## 3. Subir

```bash
docker compose up --build
```

A primeira vez leva de 5 a 10 minutos (baixa o Gradle, as dependências e os pacotes npm). Está pronto quando aparecer `Started MesaMarApplication` no log.

## 4. Roteiro manual

| # | Onde | O que fazer | Esperado |
|---|---|---|---|
| 1 | http://localhost:8080/ | Rolar a página inteira | Landing completa, fonte Bricolage nos títulos, faixa corrida |
| 2 | Celular no mesmo Wi-Fi: `http://IP-DO-PC:8080/` | Mesma coisa, de preferência ao sol | Legível, sem nada cortado na lateral |
| 3 | Landing → "Entrar na lista do piloto" | Enviar vazio | Uma mensagem em português por campo |
| 4 | Mesmo formulário | Enviar com WhatsApp `(13) 1234` | Só o WhatsApp com erro; o resto continua preenchido |
| 5 | Mesmo formulário | Enviar certo | "Pronto, você está na lista." |
| 6 | Mesmo formulário | Enviar vazio 9 vezes seguidas | Na 9ª: "Recebemos muitos envios desta conexão" (espere 10 min ou reinicie o app para zerar) |
| 7 | Botão "Falar no WhatsApp" | Tocar | Abre o WhatsApp com a mensagem pronta |
| 8 | http://localhost:8080/teste-sinal (no celular) | Escolher praia e medir | 3 números + veredito "✅ … Medição salva" |
| 9 | http://localhost:8080/admin | Entrar com `admin` + a sua senha | Lead do passo 5 e medição do passo 8 aparecem |
| 10 | Painel → "Baixar CSV" (os dois) | Abrir no Excel ou Planilhas | Acentos certos, uma informação por coluna |
| 11 | Painel → "Sair" | Abrir `/admin` de novo | Volta para o login |
| 12 | http://localhost:8080/privacidade | Ler | Texto claro; links do rodapé funcionando |
| 13 | DevTools do navegador → Application → Cookies, na landing | Conferir | **Nenhum cookie** em `localhost:8080` |

### Painel do dono (Fase 2 · checkpoint 2.1)

| # | Onde | O que fazer | Esperado |
|---|---|---|---|
| 14 | `/admin` → linha do lead do passo 5 → "Gerar convite" | Tocar | Caixa com o link do convite e o botão "Enviar pelo WhatsApp" (o link só aparece uma vez) |
| 15 | Link do convite, numa **janela anônima** | Criar a conta com senha curta (`123`) | "A senha precisa ter pelo menos 10 caracteres." |
| 16 | Mesmo formulário | Criar a conta certa | Vai para "Entrar", com "Conta criada!" e o e-mail já preenchido |
| 17 | Abrir o mesmo link do convite de novo | — | "Esse convite não vale mais." |
| 18 | Entrar | E-mail + senha | Primeiro acesso cai em "Vamos cadastrar a barraca" |
| 19 | Barraca → Continuar | Nome + praia | Home com o checklist: dados ✓, Pix pendente, atendentes/guarda-sóis/cardápio "Em breve" |
| 20 | Pix | CPF `529.982.247-24` (dígito errado) | "Essa chave não parece um CPF válido." |
| 21 | Pix | CPF `529.982.247-25`, nome `José da Praia` | Home mostra `529.982.247-25` e "JOSE DA PRAIA" (sem acento, como o Pix exige) |
| 22 | Regras | Limite de conta `1.250,50`, pagamento "A cada pedido" | Home mostra `R$ 1.250,50` e "A cada pedido" |
| 23 | Na mesma janela, abrir `/admin` | — | Pede o login do admin (as duas áreas não se misturam) |
| 24 | Painel → Sair → "Esqueci a senha" → seu e-mail | Enviar | "Se existir uma conta com esse e-mail…" |
| 25 | Log da aplicação (`docker compose logs app`) | Procurar `nova-senha?token=` | Sem SMTP, o e-mail aparece no log: abra o link, troque a senha e entre com a nova |
| 26 | Painel → Guarda-sóis | Criar "do 1 ao 20" | "Pronto: 20 criados." e a sugestão passa para 21 a 30 |
| 27 | Mesma tela | Criar "do 21 ao 40" (limite padrão é 30) | "…cabem só mais 10" e nada é criado |
| 28 | Grade de números | Tocar no 5, depois criar "do 1 ao 20" de novo | O 5 fica riscado; depois volta ("1 reativado") |
| 29 | Painel → Equipe | Adicionar "Carla" com PIN `123456` | Recusa por ser sequência; `1234` (4 números) também é recusado; com `258014` entra |
| 30 | Mesma tela | Adicionar "carla" de novo | "Já existe um atendente com esse nome." |
| 31 | Regras | Baixar o limite de guarda-sóis para 5 | Recusa: "Você tem 20 guarda-sóis ativos…" |
| 32 | Painel → Cardápio | Criar "Bebidas" e "Porções"; cadastrar 15 itens com "Adicionar e cadastrar outro" | Cada item volta ao formulário já na mesma categoria; anote quanto tempo levou |
| 33 | Cardápio | Tocar em "Esgotou" num item do meio da lista | A página volta no mesmo item, riscado, com o botão "Voltou" |
| 34 | Cardápio | Subir e descer itens e categorias (↑ ↓) | A ordem muda na hora |
| 35 | Cardápio | "Tirar do cardápio" num item | Some da categoria e aparece em "Fora do cardápio" |
| 36 | Cardápio → Editar "Bebidas" | Tentar excluir com produtos dentro | Recusa: "ainda tem N produtos" |
| 37 | Painel → Equipe | Adicionar "Carla" com PIN `258014` e anotar o código da barraca | Aparece na lista como "Ativo" |
| 38 | Celular: `http://IP-DO-PC:8080/staff/` | "Sou da equipe" → código → Carla → PIN `1111` | "PIN errado. Restam 4 tentativas…" |
| 39 | Mesmo celular | PIN `258014` | Abre o app na aba Cardápio |
| 40 | Aba Cardápio | Tocar "Esgotou" num item; conferir no painel do computador | O item fica riscado nos dois |
| 41 | Aba Mais | Seguir o passo a passo de instalação (Android: Chrome; iPhone: Safari) | Ícone do MesaMar na tela inicial; abre sem barra do navegador |
| 42 | Painel → Equipe → desativar a Carla; tocar em qualquer coisa no app | — | O app volta para a tela de entrada |

### Check-in e QR (Fase 3 · checkpoint 3.1)

Reative a Carla (Painel → Equipe) antes de começar.

> O link do QR usa a URL pública do `.env`. Para o QR abrir em outro celular na rede local, coloque `MESAMAR_PUBLIC_URL=http://IP-DO-PC:8080` no `.env` e rode `docker compose up -d` de novo (com `localhost`, o outro celular não acha o servidor).

| # | Onde | O que fazer | Esperado |
|---|---|---|---|
| 43 | Celular: app do quiosque | Entrar como Carla | Abre na aba **Clientes**, com "Ninguém com comanda aberta agora." |
| 44 | Clientes → "+ Novo cliente" | Nome `Ana`, guarda-sol 12, WhatsApp seu, **sem** marcar o consentimento → "Gerar QR" | "Confirme que o cliente concordou." e nada é criado |
| 45 | Mesmo formulário | Marcar o consentimento → "Gerar QR", **cronometrando do toque em "Novo cliente" até o QR** | QR em tela cheia com "Ana", "Guarda-sol 12 · chegada nº 1". Meta: até 20 s |
| 46 | Tela do QR | Ler com a câmera de **outro celular**, a uns 50 cm (de preferência no sol) | Abre o link `/c/…` (a página do cliente; veja o 3.2 abaixo) |
| 47 | Tela do QR → "Enviar pelo WhatsApp" | Tocar | Abre o WhatsApp no seu número com a mensagem e o link |
| 48 | "Pronto" → fazer o check-in do `Bruno` também no 12 | Conferir a lista e a grade | Ana (1) e Bruno (2) por ordem de chegada; o 12 aparece com um ponto na grade; o botão "QR" mostra o código de novo |

### Página do cliente: cardápio e pedido (Fase 3 · checkpoint 3.2)

Use o QR da Ana (itens 45 e 46) no **celular B**, com o cardápio já preenchido (tenha pelo menos 3 produtos, em 2 categorias).

| # | Onde | O que fazer | Esperado |
|---|---|---|---|
| 49 | Celular B: ler o QR da Ana, **cronometrando** | Esperar abrir | "Olá, Ana · Guarda-sol 12", o nome da barraca e o cardápio por categoria, com os preços |
| 50 | App do quiosque, aba Cardápio | Tocar "Esgotou" num item; no celular B, abrir o link de novo | O item aparece **riscado, com "Esgotado" e sem botão**. Depois, "Voltou" no app e recarregar: o botão volta |
| 51 | Celular B | Tocar "Adicionar" em 2 itens e "+" num deles | Aparece "−  2  +", e a barra amarela no rodapé mostra "Ver pedido · 3 itens · R$ …" com o total certo |
| 52 | Celular B → barra "Ver pedido" | Escrever `sem gelo` na observação de um item | O total confere com os preços; a observação fica no campo |
| 53 | Celular B → "Enviar pedido" (**cronometre do QR até aqui e conte os toques**) | Esperar | "Pedido nº 1 enviado", com os itens, a observação e o total |
| 54 | Celular B → "Voltar ao cardápio", pedir mais um item, **ligar o modo avião**, "Enviar pedido" | Esperar uns segundos | "O pedido ainda não saiu. Sem sinal. Tentaremos de novo. Se preferir, chame um atendente." |
| 55 | Celular B | **Desligar o modo avião** | Em poucos segundos: "Pedido nº 2 enviado". **Uma vez só** (confira no banco, abaixo) |
| 56 | Celular B: montar um pedido; no app, marcar um dos itens como "Esgotou"; **sem recarregar a página**, "Enviar pedido" | Ler a mensagem | "Acabou ou saiu do cardápio: …". O item sai do carrinho e o resto continua |
| 57 | Celular B, rodapé | Tocar "Política de privacidade" | Abre a política, com a seção 5 sobre os dados dos clientes |
| 58 | Celular B | Abrir o link do QR de uma comanda que **não existe** (troque um caractere) | "Esse link não vale mais" |

Conferir os pedidos direto no banco:

```bash
docker compose exec db psql -U mesamar -c "select p.numero_pedido, p.status, p.criado_em, i.linha, i.quantidade, i.nome_snapshot, i.preco_snapshot_centavos, i.observacao from pedido p join item_pedido i on i.pedido_id = p.id order by p.criado_em, i.linha;"
```

### Meus pedidos, saldo e regras (Fase 3 · checkpoint 3.3)

Continue no **celular B**, com a Ana. Como o app do quiosque ainda não tem a fila (Fase 4), os itens 60, 61 e 66 mudam o banco direto, só para você ver a página reagir.

| # | Onde | O que fazer | Esperado |
|---|---|---|---|
| 59 | Celular B | Olhar o topo da página e abrir a aba **Meus pedidos** | "Sua conta: R$ …" no topo, igual à soma dos pedidos. Os pedidos aparecem do mais novo para o mais antigo, com "Recebido", itens, observação e total |
| 60 | Deixar "Meus pedidos" aberta; no computador, rodar o 1º comando abaixo | Esperar até 15 s, **sem recarregar** | O pedido nº 1 passa a "Em preparo" |
| 61 | No computador, rodar o 2º comando abaixo (cancela o último item pedido) | Esperar até 15 s | O item aparece riscado com "Cancelado" e **a conta baixa** |
| 62 | Painel → Regras → "Limite de conta aberta" `50` → Salvar. No celular B, pedir até a conta chegar a R$ 50 ou mais | Voltar ao cardápio | Aviso vermelho "Sua conta chegou a R$ 50,00…", **sem botões** de adicionar. O último pedido, o que cruzou o limite, passou |
| 63 | Com o limite atingido, cancelar itens (como no 61) até a conta ficar abaixo de R$ 50. No celular B, tocar em "Cardápio" depois de 10 s | Esperar a página atualizar | O aviso some e os botões voltam. Dá para pedir de novo |
| 64 | Painel → Regras → "A cada pedido" → Salvar (e tirar o limite). Celular B: abrir o cardápio e pedir | Conferir | Cardápio: "Neste quiosque você paga a cada pedido". Confirmação: "Para o pedido entrar na fila, pague R$ … a um atendente". Em Meus pedidos: **"Aguardando pagamento"** |
| 65 | Painel → Regras → "No final" → Salvar. Celular B: pedir de novo | Conferir | O novo pedido nasce "Recebido" |
| 66 | No computador, rodar o 3º comando abaixo. Celular B: recarregar e tentar pedir | Conferir | "O quiosque pausou os pedidos por agora…". Depois, trocar `true` por `false` no comando e voltar ao normal |

Comandos para os itens 60, 61 e 66 (o app do quiosque ainda não faz isso; chega na Fase 4):

```bash
docker compose exec db psql -U mesamar -c "update pedido set status='EM_PREPARO' where numero_pedido = 1 and dia = current_date;"
docker compose exec db psql -U mesamar -c "update item_pedido set cancelado = true, motivo_cancelamento = 'teste' where id = (select i.id from item_pedido i join pedido p on p.id = i.pedido_id order by p.criado_em desc, i.linha limit 1);"
docker compose exec db psql -U mesamar -c "update barraca set pedidos_pausados = true;"
```

### Fila e tempo real (Fase 4 · checkpoint 4.1)

Use o app do quiosque (Carla) no **celular A** e três clientes (check-in de Ana, Bruno e Carol) nos **celulares B, C e D**. Se tiver só um celular de cliente, use abas anônimas diferentes.

| # | Onde | O que fazer | Esperado |
|---|---|---|---|
| 67 | Celular A: abrir a aba **Fila** | Olhar | "Tudo em dia" e o indicador verde "Ao vivo" |
| 68 | Celulares B, C e D | Pedir um item cada, **com 10 s de intervalo** | No celular A, **sem recarregar**, os pedidos aparecem na ordem em que foram feitos, com o selo "Novo", **bip e vibração** (toque na tela uma vez antes, para o navegador liberar o som). Guarda-sol em destaque, nome, itens e observações. A aba mostra a contagem |
| 69 | Painel → Regras → "Alerta de pedido parado" `2` min; deixar um pedido parado | Esperar | Faixa **verde**, depois **amarela** (1 min) e **vermelha** com a etiqueta "Atrasado" (2 min). A cor muda sozinha |
| 70 | Celular A: "Em preparo" num pedido, depois "Entregue" | Olhar o celular do cliente, **sem recarregar** | O status muda em até 2 segundos. O pedido entregue some da fila |
| 71 | Celular A: "Cancelar…" num pedido com 2 itens, escolher 1 item e **não** escolher o motivo | Tocar em "Confirmar cancelamento" | "Escolha o motivo do cancelamento." |
| 72 | Mesmo diálogo, motivo "Esgotou" | Confirmar e olhar o celular do cliente | Aviso vermelho "O quiosque cancelou 1× … do pedido nº …: Esgotou.", item riscado e **a conta baixa**. "Entendi" fecha o aviso |
| 73 | Celular A: cancelar um pedido inteiro com "Outro" e escrever um detalhe | Olhar o cliente | Aviso com "Outro: o detalhe" e o pedido "Cancelado" |
| 74 | Celular A: filtros "Pendentes" e o seletor de guarda-sol | Tocar | Só os pedidos que combinam aparecem |
| 75 | Celular A: **modo avião por 20 s**, e um cliente pedindo nesse tempo. Depois, desligar o modo avião | Olhar | "Reconectando…" em vermelho e, ao voltar, "Ao vivo" e **o pedido feito no meio aparece sozinho** |

Também vale conferir o botão **"Som: ligado"** (alterna e continua assim depois de recarregar) e, em dois celulares do quiosque, tocar em "Entregue" no mesmo pedido quase juntos: só um aplica, e o outro mostra "Esse pedido já foi entregue." sem quebrar nada.

### Avisos de pedido, atraso e pausa (Fase 4 · checkpoint 4.2)

**Precisa do servidor publicado em HTTPS** (`https://mesamar.0mega.fun`) e das chaves VAPID no `.env` (passo a passo no `docs/deploy.md`). O push não funciona em `http://IP-DO-PC`.

| # | Onde | O que fazer | Esperado |
|---|---|---|---|
| 76 | App do quiosque (celular A), aba Mais | Olhar o cartão "Avisos de pedido" | Com as chaves configuradas: botão **"Ativar os avisos"**. Sem elas: "Os avisos ainda não foram configurados no servidor" |
| 77 | Android, Chrome: "Ativar os avisos" | Permitir as notificações | "Ativados neste celular", com "Mandar um aviso de teste" |
| 78 | "Mandar um aviso de teste", depois **bloquear a tela** | Esperar | Chega a notificação "MesaMar: avisos funcionando", também com a tela bloqueada |
| 79 | **Fechar o app e bloquear a tela do celular A.** No celular B (cliente), fazer um pedido com 3 itens | Olhar o celular A | Notificação **"Pedido nº X · Guarda-sol 12" / "3 itens"**, com vibração. **Tocar abre a Fila** no pedido |
| 80 | iPhone, **sem** instalar o app: aba Mais | Olhar | "No iPhone, os avisos só funcionam com o app instalado na tela inicial". Instalando (itens 41) e abrindo pelo ícone, o botão aparece e os avisos funcionam (iOS 16.4 ou mais novo) |
| 81 | Painel → Regras → "Alerta de pedido parado" `1` min. Deixar um pedido parado | Esperar até 90 s | Notificação **"Pedido nº X está parado"**. Com o app aberto: três bips graves e um aviso vermelho. O cartão fica **vermelho com "Atrasado"**. O aviso sai **uma vez só** |
| 82 | App aberto e à vista, cliente pede | Olhar | Bip duplo e vibração, selo "Novo" e a notificação também aparece |
| 83 | Fila → "Pausar pedidos", recado `Voltamos em 10 minutos` | Olhar o celular B, **sem recarregar**; tentar pedir | Banner "O quiosque pausou os pedidos: Voltamos em 10 minutos" e o pedido é recusado. "Voltar a receber pedidos" libera na hora |
| 84 | Painel → Equipe: desativar a Carla. Cliente faz um pedido | Olhar o celular da Carla | A Carla **não recebe** o aviso (e o app dela volta para o login) |
| 85 | Aba Mais → "Sair deste celular". Cliente faz um pedido | Olhar o celular | **Nenhuma** notificação nesse celular |

> A instalação na tela inicial (item 41) exige **HTTPS** fora do `localhost`. No celular pela rede local (`http://IP-DO-PC`), o app abre, mas o Chrome não oferece "Instalar". Para testar a instalação de verdade, use o ambiente publicado (`https://mesamar.0mega.fun/staff/`).

Consultar direto no banco, se quiser:

```bash
docker compose exec db psql -U mesamar -c "select nome, nome_barraca, praia, whatsapp, criado_em from lead order by criado_em desc;"
```

### Fechar conta e Pix (Fase 5 · checkpoint 5.1)

Precisa de uma barraca com a chave Pix configurada (Painel → Pix) e de um cliente com pedidos. Para ler o QR no app do banco, use o celular do cliente **e outro aparelho** para ler a tela (o app do banco não lê a própria tela).

| # | Onde | O que fazer | Esperado |
|---|---|---|---|
| 86 | Celular do cliente → aba **Meus pedidos** | Olhar embaixo da lista | Botão **"Fechar conta"**, que abre a aba **Conta** |
| 87 | Aba **Conta** | Olhar o extrato | Todos os pedidos, itens cancelados **riscados**, e **"Falta pagar"** igual ao saldo do cabeçalho. O botão "Fechar a conta" fica desligado até escolher a forma |
| 88 | Escolher **Dinheiro**, digitar `abc` em "Vou pagar com R$", tocar em "Fechar a conta" | Olhar | Aviso "Não entendemos o valor do troco". Com `100`: **"Aguardando confirmação do quiosque"** e "Vá ao quiosque pagar R$ … em dinheiro (troco para R$ 100,00)". Valor de troco **menor que a conta** é recusado |
| 89 | Aba **Cardápio**, depois **Conta → "Voltar e pedir mais"** | Tentar pedir antes e depois | Com a conta fechando, o cardápio avisa "Sua conta está sendo fechada…" e **não deixa pedir**. Depois de "Voltar e pedir mais", dá para pedir de novo e a escolha de forma some |
| 90 | Escolher **Cartão** e fechar | Olhar | "Vá ao quiosque pagar … na maquininha" e "O quiosque foi avisado às HH:MM" |
| 91 | Escolher **Pix** e fechar | Olhar a tela e **ler o QR com o app de um banco** (ou colar o código em "Pix copia e cola") | O banco mostra **o nome e a cidade da barraca e o valor da conta**. Conferir também que a **chave** é a do dono |
| 92 | Tocar em **"Copiar código Pix"** | Colar num bloco de notas | O texto começa com `000201` e termina com `6304` e 4 letras/números. Em `http://IP-DO-PC` o navegador pode bloquear a cópia: o aviso manda abrir **"Ver o código"** e copiar à mão |
| 93 | Tocar em **"Já paguei"** | Olhar | "Você avisou às HH:MM. O quiosque vai conferir o Pix no app do banco." O botão some |
| 94 | **"Trocar a forma de pagamento"** → Dinheiro → "Trocar a forma de pagamento" | Olhar | Volta a "Aguardando confirmação", agora em dinheiro, **sem** o QR |
| 95 | Painel do dono sem chave Pix, cliente fecha a conta | Olhar as formas | Só **Dinheiro** e **Cartão** aparecem |
| 96 | Cliente com a conta zerada (nenhum pedido) | Abrir a aba Conta | "Não há nada para pagar agora" e nenhuma forma de pagamento |

> No 5.1 o app do quiosque **ainda não mostra** a comanda aguardando pagamento, e o dono não é avisado: isso chega no 5.2. Hoje só dá para ver pelo celular do cliente.

### Pagamentos: receber, bloquear e fechar a conta (Fase 5 · checkpoint 5.2)

Use dois celulares: **A** (cliente, pela página do QR) e **B** (dono ou atendente, no app do quiosque). Os itens 100 e 101 precisam do servidor em HTTPS e das chaves VAPID (como os itens 76 a 85).

| # | Onde | O que fazer | Esperado |
|---|---|---|---|
| 97 | B, aba **Pagamentos** | Olhar com nenhuma conta fechando | "Ninguém avisou que vai pagar" e, embaixo, **"Contas abertas com saldo"** com quem tem pedidos, as maiores primeiro |
| 98 | A: Conta → Dinheiro, "vou pagar com" `100` → Fechar a conta | Olhar o B **sem recarregar** | A conta aparece na aba (com o número na aba), **som de três notas** e aviso "Guarda-sol 12 · Ana está indo pagar · R$ … · Dinheiro (troco para R$ 100,00)". O cartão mostra "Troco para R$ 100,00: devolver R$ …" |
| 99 | A: "Trocar a forma de pagamento" → Pix → "Já paguei" | Olhar o B | A conta passa a dizer **"Pix · avisou que pagou agora"** e **"Confira no app do banco antes de confirmar"** |
| 100 | **Fechar o app no B e bloquear a tela.** No A, fechar a conta em Cartão | Olhar o B | Notificação **"Guarda-sol 12 · indo pagar" / "R$ … · Cartão"**, sem o nome do cliente. Tocar abre a aba **Pagamentos** |
| 101 | Mesmo teste, no Pix, tocando em "Já paguei" | Olhar o B | **"Guarda-sol 12 · Pix enviado" / "R$ … · confira no app do banco"**. Escolher o Pix, sem tocar em "Já paguei", **não** avisa |
| 102 | No A, tocar duas vezes seguidas no mesmo botão de fechar | Olhar o B | Um aviso só |
| 103 | B: **Receber** na conta do Pix (confira antes no app do seu banco) | Ver o diálogo | Vem com **Pix** marcado e o aviso "Confira no app do seu banco antes de confirmar". "Valor recebido agora" já traz o que falta |
| 104 | B: receber **uma parte** (ex.: R$ 20,00 no Pix) | Olhar o A, **sem recarregar** | "Recebido R$ 20,00… Ainda falta R$ …". No A, **"Já pago"** e "Falta pagar" mudam na hora, e o Pix mostra só o que falta |
| 105 | B: receber o resto **em dinheiro**, "entregou" um valor maior | Olhar o diálogo antes de confirmar | **"Devolver de troco: R$ …"** com a conta certa. Ao confirmar: "Conta de Ana paga e encerrada. Troco: R$ …". No A: **"Conta paga"** com o extrato |
| 106 | B: tocar duas vezes em "Confirmar" (ou confirmar de novo depois de tirar o celular do sinal e voltar) | Olhar o extrato do A | **Um pagamento só** |
| 107 | B: tentar receber **mais do que falta**, ou deixar o valor vazio | Olhar | Mensagem "O valor passa do que falta pagar" ou "Digite quanto o cliente pagou", sem registrar nada |
| 108 | B: **Bloquear** o cliente. No A, tentar pedir | Olhar o A | O cardápio mostra "Sua comanda está bloqueada" e **não deixa pedir**. **Desbloquear** libera na hora |
| 109 | Modo "pagar a cada pedido" (Painel → Regras): A faz um pedido de R$ 24,00 | Olhar a Fila no B, depois receber R$ 10,00 e o resto | O pedido **não** está na fila; com R$ 10,00 continua fora; ao completar R$ 24,00 **entra na fila** com o aviso de pedido novo, e o tempo de espera **começa nessa hora** |
| 110 | B como **dono**: **Fechar sem receber…** numa conta com saldo. Depois, entrar como **atendente** | Olhar | O dono precisa escrever o motivo (mín. 3 letras); a conta some e fica registrado o valor não recebido. **O atendente não vê** o botão "Fechar sem receber" |

> Link de uma conta **paga há mais de 7 dias** deixa de abrir ("Esse link não vale mais"). Para ver isso sem esperar, mude `fechada_em` no banco: `docker compose exec db psql -U mesamar -c "update comanda set fechada_em = now() - interval '8 days' where status = 'FECHADA';"`.

### Relatório do dia e LGPD (Fase 5 · checkpoint 5.3)

Use um dia com movimento (ou crie: 3 ou 4 clientes, pedidos, pagamentos em formas diferentes, um cancelamento e uma conta fechada sem receber).

| # | Onde | O que fazer | Esperado |
|---|---|---|---|
| 111 | Painel → **Relatório** | Abrir | "Hoje, DD/MM/AAAA" com Resumo, Recebido por forma, Mais vendidos, Cancelamentos, Contas em aberto e Não recebido. Sem rolagem lateral no celular |
| 112 | Mesma página | Somar o **caixa do dia** (o que você recebeu em Pix, dinheiro e cartão) | **Recebido no dia** e **cada forma** batem com o seu caixa. O troco devolvido **não** entra no dinheiro |
| 113 | Mesma página | Conferir "Vendido", "Clientes" e os pedidos | **Vendido** = soma dos pedidos sem os itens cancelados. **Vendido = Recebido + Contas em aberto + Não recebido** (se o dia está todo fechado) |
| 114 | Mesma página | Olhar "Mais vendidos", "Cancelamentos" e "Tempo médio" | A quantidade de cada item confere com a fila; os cancelamentos mostram o motivo (Esgotou, Cliente desistiu, Outro); o tempo médio vai da entrada na fila até "Entregue" |
| 115 | **Baixar em CSV** → abrir no Excel ou Google Planilhas | Olhar | Abre em colunas **Seção, Item, Quantidade, Valor (R$)**, com acentos certos e valores com vírgula (ex.: `49,00`) |
| 116 | "Dia anterior" / escolher uma data / digitar uma data no futuro na URL (`?dia=2099-01-01`) | Olhar | Mostra os números do dia escolhido (as **contas em aberto** são sempre as de agora); data futura volta para hoje |
| 117 | Outra conta de dono (outra barraca) | Abrir o relatório | **Nenhum** cliente, valor ou motivo da primeira barraca |
| 118 | Banco: envelhecer um cliente e rodar o job (ver abaixo) | Abrir o relatório e a página do cliente | Nome vira **"Cliente"** e o WhatsApp some; **os valores do relatório não mudam**. Cliente com conta ainda aberta **também** é anonimizado (o valor devido continua na conta) |

Para o item 118, sem esperar 90 dias, no banco do compose:

```bash
docker compose exec db psql -U mesamar -c "update cliente set criado_em = now() - interval '91 days' where nome = 'Ana';"
```

O job roda todo dia às 3h30 (Brasília): depois de envelhecer o cliente, confira no dia seguinte (o `AnonimizacaoTest` já cobre o comportamento; aqui você só confere que ele rodou de madrugada no servidor de verdade, procurando "LGPD: N clientes anonimizados" em `docker compose logs app`).

### Backup e restauração na VPS (Fase 7 · checkpoint 7.1)

Estes itens são **no servidor de produção** (ou num ambiente igual a ele), seguindo a seção "Backup diário, restauração e monitoramento" do `docs/deploy.md`.

| # | Onde | O que fazer | Esperado |
|---|---|---|---|
| 119 | VPS: `./deploy/backup/backup.sh` | Rodar à mão | `backup ok: /opt/mesamar/backups/mesamar-AAAA-MM-DD_HHMM.dump (…)` e `rotação: … backup(s) guardado(s)`. O arquivo existe e é só seu (`ls -l`: `-rw-------`) |
| 120 | VPS: `./deploy/backup/testar-restauracao.sh` | Rodar fora do horário de movimento | No fim: **`RESTAURAÇÃO OK: N tabelas, M linhas, esquema V12, idêntico ao banco original`**. O banco de produção continua intacto |
| 121 | VPS: `crontab -l` e, **no dia seguinte**, `ls -lh /opt/mesamar/backups` e `tail /opt/mesamar/backups/backup.log` | Conferir o cron | Um backup novo da madrugada (3h15) e a linha `backup ok` no log |
| 122 | Ambiente de ensaio: `./deploy/backup/restaurar.sh ARQUIVO.dump` | Usar o nome `mesamar_recuperacao_<id>` mostrado no log para consultar o treino pelo administrador; após conferir, removê-lo com `DROP DATABASE nome_do_treino` | Dados validados; produção **não mudou**. Confira o nome antes de remover. Não existe opção `--limpar` |
| 123 | UptimeRobot (ou outro) | Criar o monitor de `https://mesamar.0mega.fun/actuator/health` com a palavra-chave `"status":"UP"`. Para testar: `docker compose -f docker-compose.prod.yml stop app`, esperar 5 a 10 min, depois `start app` | Você recebe o alerta de "fora do ar" e depois o de "voltou" |
| 124 | healthchecks.io (opcional) | Criar um monitor com período de 1 dia e colocar a URL em `MESAMAR_BACKUP_PING_URL` no cron | O monitor fica verde depois do backup; se você **comentar o cron por 2 dias**, ele alerta |
| 125 | Seu computador | Copiar um backup para fora: `scp usuario@vps:/opt/mesamar/backups/mesamar-*.dump ~/backups-mesamar/` e rodar `restaurar.sh` com ele (sem `--producao`) | O backup copiado também restaura. Defina **onde** e **com que frequência** você guarda cópias fora da VPS |

> **Não** teste `restaurar.sh ARQUIVO.dump --producao` no servidor real só por curiosidade: ele ativa os dados do backup, preservando o banco anterior como resgate. Para ensaiar, use uma cópia descartável da VPS ou o ambiente local.

### Barraca de demonstração e materiais (Fase 7 · checkpoint 7.2)

| # | Onde | O que fazer | Esperado |
|---|---|---|---|
| 126 | /admin → "Barraca de demonstração" | Tocar em **Criar a barraca de demonstração** | Aparece, **uma vez**, o e-mail (`demo@treino.invalid`), a **senha do dono**, o **código da barraca** e o **PIN da atendente**. Anote. Ao recarregar a página, a senha e o PIN **não aparecem mais** e o botão vira "Reiniciar…" |
| 127 | Painel do dono (celular ou computador): entrar com o e-mail e a senha anotados | Olhar | **Faixa amarela** "Barraca de treino: nada aqui é de verdade". Cardápio com 6 itens, 20 guarda-sóis, atendente Carla (treino) |
| 128 | App do quiosque: entrar como atendente (código da barraca, "Carla (treino)", o PIN anotado) | Olhar | Faixa amarela de treino. A aba **Fila** já tem os pedidos da "Ana (treino)" e do "Beto (treino)" |
| 129 | Treinar o dia inteiro: check-in, o cliente pede pelo QR, Em preparo / Entregue, cancelar com motivo, o cliente fecha a conta (Pix, dinheiro, cartão), receber, bloquear | Fazer tudo como na barraca de verdade | Tudo funciona, e **a página do cliente mostra a faixa de treino**. O QR Pix é válido para ler, mas **a chave é fictícia: não leva dinheiro a lugar nenhum** |
| 130 | /admin → **Reiniciar a barraca de demonstração** | Depois, tentar entrar com a senha e o PIN antigos | O que o treino gerou **some**, o cardápio e as regras voltam ao padrão, os dois clientes de exemplo voltam. **A senha e o PIN antigos não entram mais**; os novos (mostrados uma vez) entram |
| 131 | Painel do dono da **barraca de verdade** → **Materiais** → **Guia rápido da equipe** → Imprimir | Olhar a prévia de impressão (A4) | **Uma página só**, com o nome e o código da **sua** barraca e o endereço do app. Legível a 1 metro |
| 132 | Mesmo lugar → **Plaquinha do balcão** → Imprimir | Olhar a prévia e a folha impressa | "Peça pelo celular", "Faça seu cadastro aqui e receba seu QR" e os 3 passos, em letra grande. **Cole no balcão e peça a alguém que não conhece o sistema para ler**: entendeu o que fazer? |
| 133 | Plaquinha e guia impressos | Ver a folha ao sol, a 2 metros (plaquinha) e a 1 metro (guia) | Dá para ler. Se não, anote o que ficou pequeno (ajusto o tamanho) |
| 134 | Barraca **de verdade** (painel, app e página do cliente) | Olhar | **Nenhuma** faixa de treino |
| 135 | Dono de **outra** barraca: abrir Materiais | Olhar o guia e a plaquinha | Mostram **o nome e o código da barraca dele**, nunca os de outra |

### Reportar problema (Fase 7 · checkpoint 7.3)

| # | Onde | O que fazer | Esperado |
|---|---|---|---|
| 136 | App do quiosque → aba **Mais** | Ver o cartão "Reportar problema", escrever uma frase e tocar em **Enviar aviso** | "Aviso enviado. Obrigado!" e a caixa esvazia |
| 137 | /admin → **Problemas reportados** (recarregue a página) | Procurar o aviso | A linha traz **quando**, a **barraca**, "Quiosque", a **tela** em que você estava antes de abrir "Mais" (ex.: `fila`) e a **conexão** (ex.: `online · 4g · tempo real conectado`). Passando o mouse na conexão, aparece o navegador |
| 138 | Página do cliente (celular B) → rodapé → **Reportar um problema** | Escrever e enviar | "Aviso enviado. Obrigado!". No /admin, a linha aparece como "Cliente" e **sem o nome do cliente** |
| 139 | Celular em **modo avião** (ou sem sinal): tentar enviar | Olhar | Mensagem "Não deu para enviar agora. Tente de novo quando o sinal voltar". **Nada** é enviado em segundo plano |
| 140 | Escrever só "a" | Enviar | "Conte o que aconteceu, com pelo menos 3 letras" |
| 141 | Mandar 11 avisos seguidos no app | Olhar | A partir do 11º: "Você já mandou vários avisos. Espere um pouco" |
| 142 | Painel do dono → menu **Avisos** | Abrir depois de mandar os avisos dos itens 136 e 138 | Os avisos **da sua barraca**, do mais novo para o mais antigo, com "Equipe" ou "Cliente", a hora, a tela e a conexão. Sem o navegador de quem avisou. Barraca sem aviso mostra "Nenhum aviso até agora." |
| 143 | Dono de **outra** barraca → Avisos | Olhar | Só os avisos dele, nunca os da primeira barraca |

### Revisão final (Fase 7 · checkpoint 7.4)

| # | Onde | O que fazer | Esperado |
|---|---|---|---|
| 144 | App do quiosque, uma conta com pedido **já pago** | Tentar cancelar o pedido (ou o único item) | Mensagem "O cliente já pagou por isso… combine a troca ou devolva o valor no balcão". O pedido continua como estava. Antes de qualquer pagamento, cancelar funciona |
| 145 | Painel aberto no computador **e** app aberto no celular, com o dono logado nos dois. Em outra aba, "Esqueci a senha" e trocar pelo link do e-mail (no log, sem SMTP) | Voltar ao painel e ao app e clicar em qualquer coisa | O painel volta para o **login**; o app pede para entrar de novo. A senha nova entra |
| 146 | App da atendente, celular A. No celular B (ou outra rede), errar o PIN 5 vezes | Entrar com o PIN certo no celular A | **Entra.** Só o celular B fica bloqueado por 15 minutos. No painel do dono, Equipe, a atendente **não** aparece bloqueada |
| 147 | /admin → Problemas reportados e uma barraca com mais de 90 dias de uso | (só em produção, depois de 90 dias) | Avisos antigos somem sozinhos de madrugada |
| 148 | Cliente com comanda de **ontem**, sem saldo, que nunca tocou em "Fechar conta" | Olhar de manhã (depois das 4h) | A comanda está **fechada** e o link já não aceita pedido novo. A que ainda deve continua aberta em "Contas abertas" |
| 149 | Cliente com comanda de **ontem** que ainda deve (mude o `dia` da comanda no banco para ontem) | Tentar pedir de novo pela página do cliente | "Esta comanda é de outro dia e já não aceita pedidos." O pedido **não** é feito. "Voltar e pedir mais" também é recusado |
| 150 | Mesma comanda, no app do quiosque (aba Pagamentos) | Receber o que ela deve | O pagamento funciona e a comanda se encerra |
| 151 | **Celular do dono**, app do quiosque entrando **como dono** → aba Mais → ativar avisos. Depois, outro celular manda "Reportar problema" (quiosque ou página do cliente) | Olhar a tela do celular do dono (com o navegador ou o app em segundo plano) | Chega uma notificação **"Aviso de problema · Cliente"** (ou "Equipe") com o começo do texto. Tocar abre a página **Avisos** do painel (pede o login do painel se preciso). Atendentes, no mesmo estabelecimento, **não** recebem. Vários avisos seguidos viram uma notificação só |

### Lançar pedido para cliente (Fase 6 · checkpoint 6.1)

| # | Onde | O que fazer | Esperado |
|---|---|---|---|
| 152 | App do quiosque, aba Clientes, um cliente com comanda aberta | Tocar em **Pedir** ao lado do nome, escolher 2 águas de coco com o **+** e tocar em **Lançar pedido** | Aviso "Pedido N lançado para …". Na aba **Fila** o pedido aparece como qualquer outro. Na página do cliente (se ele tiver o QR aberto), o pedido aparece em Meus pedidos sem recarregar |
| 153 | Mesma tela, sem escolher nada | Olhar o botão | **Lançar pedido** fica desligado e o texto diz "Nenhum item escolhido." |
| 154 | Barraca com **Pausar pedidos** ligado, e o cliente tentando pedir pelo celular dele | Lançar o pedido pelo app do quiosque | O cliente é barrado ("O quiosque pausou os pedidos"), e a **equipe consegue lançar** |
| 155 | Comanda **bloqueada** (aba Pagamentos → Bloquear) | Tentar lançar pedido para ela | Recusado com "Sua comanda está bloqueada…" (a mensagem é a mesma do cliente) |
| 156 | Produto marcado como **Esgotou** na aba Cardápio | Abrir Pedir | O item aparece riscado, com a tag "Esgotado", e **sem** os botões + e − |

O token de QR gerado no aparelho (check-in sem sinal) só tem teste automático neste checkpoint; a tela de check-in offline vem no 6.2.

### Correções da revisão (lote 1)

| # | Onde | O que fazer | Esperado |
|---|---|---|---|
| 157 | Página do cliente com o DevTools aberto (aba Network, "Slow 3G") | Enviar um pedido e, assim que o servidor responder, ficar sem rede (modo avião) antes de a página mostrar "enviado". Voltar a rede | A página tenta de novo sozinha e mostra **um** pedido. Em Meus pedidos e na Fila, um só |
| 158 | App do quiosque, aba Pagamentos | Abrir "Receber", ligar o modo avião e tocar em Confirmar. Fechar o diálogo, voltar a rede e abrir "Receber" de novo | Aparece o aviso "O último pagamento (R$ …) foi enviado e a resposta não chegou", com o valor e a forma travados. Confirmar registra **um** pagamento só |
| 159 | App do quiosque na aba Pagamentos, e o cliente pedindo pelo celular | Fazer um pedido pelo celular | O saldo da conta na aba Pagamentos sobe sozinho, sem recarregar |
| 160 | Página do cliente numa aba anônima com o armazenamento do site bloqueado (configurações do navegador) | Montar o carrinho e enviar | O pedido é enviado normalmente (só não sobrevive a recarregar a página) |
| 161 | Painel → Cardápio, e um cliente com o carrinho montado | Mudar o preço da água de coco e o cliente enviar o pedido | "O preço mudou: Água de coco. Confira o total novo e envie de novo." O carrinho mostra o total novo |
| 162 | App do quiosque, aba Pagamentos, conta **bloqueada** sem nada a pagar | Olhar a lista | A conta aparece em "Contas abertas", com Desbloquear e Encerrar conta |
| 163 | Conta fechada **sem receber** (dono, aba Pagamentos) | Abrir a página do cliente, aba Conta | "Conta encerrada pelo quiosque" e "Ficou sem pagar R$ …", **não** "Conta paga" |
| 164 | Cadastro de conta do painel | Tentar uma senha com 40 letras acentuadas (ex.: "é" repetido) | Mensagem "A senha está longa demais…", sem erro interno |

### Correções da revisão (lote 2)

| # | Onde | O que fazer | Esperado |
|---|---|---|---|
| 165 | App do quiosque com o DevTools (Network → "Slow 3G") | Abrir Clientes e, antes de carregar, tocar em Cardápio | A aba Cardápio fica. A lista de clientes não aparece por cima quando chega |
| 166 | App do quiosque, aba Cardápio | Tocar "Esgotou" 5 vezes bem rápido no mesmo produto | O botão termina no estado do último toque, e o painel mostra o mesmo |
| 167 | Lançar pedido, com "Slow 3G" | Tocar Lançar e tentar mexer no + e − | Os botões ficam travados até a resposta |
| 168 | Lançar pedido em modo avião | Tocar Lançar, esperar o erro, ir para outra aba, voltar e tocar Pedir na mesma pessoa | A tela mostra "Este pedido foi enviado e a resposta não chegou" com os itens travados. Com a rede de volta, Reenviar cria **um** pedido |
| 169 | Novo cliente em modo avião | Preencher e tocar Gerar QR. Recarregar o app e abrir Novo cliente | O formulário volta preenchido e travado. Com a rede, Gerar QR cria **uma** comanda |
| 170 | Aba Mais em modo avião | Tocar "Sair deste celular" | Mensagem "Não deu para sair agora". Recarregar a página continua logado (não finge que saiu) |
| 171 | Aba Pagamentos, diálogo Receber aberto | Tocar em outra aba | O diálogo some |
| 172 | Aba Mais em modo avião | Olhar o cartão "Avisos de pedido" | "Não deu para falar com o servidor agora", com Tentar de novo (não "servidor sem chaves") |
| 173 | Página do cliente aberta, e o dono no app | Marcar um produto como esgotado | A página do cliente mostra o esgotado sozinha, sem recarregar. A aba Cardápio de outro celular do quiosque também |
| 174 | Página do cliente: abrir uma vez com sinal, fechar, ligar o modo avião e abrir de novo | Olhar o topo | "Sem sinal. Mostrando o cardápio salvo às HH:MM…". A página abre com estilo (CSS) mesmo na primeira reabertura |
| 175 | DevTools → Application → Cache Storage, com a página do cliente e o app abertos no mesmo navegador | Publicar uma versão nova e recarregar os dois | Cada app fica só com o cache da versão nova dele; o cache do outro continua lá |
| 176 | Cliente com o pedido "Sem sinal. Tentaremos de novo" e o atendente lança os mesmos itens pelo app | Voltar o sinal no celular do cliente | O cliente vê o pedido como enviado, e a Fila tem **um** pedido |
| 177 | App do quiosque em segundo plano (outro app na frente) por alguns minutos, sem tempo real | Voltar ao app | A fila e os pagamentos se atualizam na hora |

### Correções da revisão (lote 3)

| # | Onde | O que fazer | Esperado |
|---|---|---|---|
| 178 | App do quiosque, com leitor de tela (TalkBack ou VoiceOver) | Abrir o QR de um cliente e navegar pelos controles | O leitor só enxerga o QR (o resto do app fica de fora), o Tab não sai dele, e ao fechar o foco volta ao botão que abriu |
| 179 | Check-in, com teclado externo ou leitor de tela | Escolher o guarda-sol com as setas | Um grupo "Guarda-sol" de radios: setas movem e escolhem, o Tab entra e sai do grupo |
| 180 | Aba Fila aberta e um pedido novo chegando | Deixar o foco num botão da fila e esperar um pedido chegar. Repetir com o filtro de guarda-sol aberto | O foco continua no mesmo botão; a lista do filtro não fecha sozinha (a fila se atualiza quando você sai do campo) |
| 181 | Pedido parado além do tempo da barraca, e um cliente tocando em "fechar a conta" | Ir para a aba Cardápio | No topo fica uma faixa "Pedido nº … está esperando há N min" e "… está indo pagar", com Ver e Entendi. Não some sozinha; some quando o pedido sai da fila ou a conta é recebida |
| 182 | Tela de entrada do app | Tocar em "Sou o dono" | Aparece "Esqueci a minha senha", que abre o fluxo do painel. Na do atendente: "Esqueceu o PIN? Peça ao dono…" |
| 183 | Celular que usou a página do cliente em comandas de dias diferentes | Abrir uma comanda nova e olhar os dados do site (DevTools → Application → Local Storage) | As entradas de comandas sem uso há mais de 7 dias foram apagadas. Link que dá "não vale mais" apaga as da própria comanda |
| 184 | Página /privacidade, seção 5 | Ler | Fala da cópia da comanda no celular, dos 7 dias, dos textos livres apagados aos 90 dias, da notificação sem texto e dos pedidos de exclusão em até 15 dias |
| 185 | Painel do dono → Equipe, atendente logado no celular dela | "Celular perdido? Tirar … de todos os aparelhos" → Sair de todos os aparelhos | O app dela volta ao login na hora (mesmo com a fila aberta). O PIN antigo ainda entra. Desativar e reativar não traz a sessão de volta |
| 186 | App do quiosque → Clientes → QR de um cliente | "Trocar o QR deste cliente" → Gerar QR novo agora | Aparece o QR novo. Abrir o link antigo no celular do cliente: "Esse link não vale mais". A conta e os pedidos continuam no QR novo |
| 187 | Cliente que fez um pedido com a página "Sem sinal" | Trocar o QR dele antes de o sinal voltar | Se o pedido já tinha chegado, a página antiga para de reenviar. Se não tinha, ela mostra o erro e o atendente lança de novo |
| 188 | Pedido com observação "sem gelo, é para o filho do Marcos", depois cancelado com "Outro: …" (mude a data do cliente para 91 dias atrás e rode o job, ou espere) | Olhar o relatório e a comanda | A observação e o detalhe do motivo sumiram; o relatório continua agrupando em "Outro" |
| 189 | Reportar um problema na página do cliente, com o celular do dono bloqueado | Olhar a tela do dono | A notificação diz "Toque para ler o aviso no painel", **sem** o texto digitado |
| 190 | Servidor com `deploy/lgpd/excluir-cliente.sh --link …` (ver `docs/lgpd-procedimento.md`) | Rodar, depois restaurar um backup antigo no banco de treino | O cliente aparece como "Cliente", o link antigo dá 404, e o treino restaurado já sai com a exclusão refeita |

## 5. Testes E2E (os mesmos do CI)

Com o `docker compose up` rodando, em outro terminal:

```bash
cd e2e
npm ci
npx playwright install chromium webkit
E2E_ADMIN_PASSWORD=uma-senha-sua npx playwright test    # no Windows (PowerShell): $env:E2E_ADMIN_PASSWORD="..."; npx playwright test
npx playwright show-report
```

Esperado: **11 passaram**. O teste de 4G imprime o tempo de carregamento. Rode o E2E logo depois de subir o app, porque os testes enviam o formulário e contam no limite de envios por aparelho.

## 6. Testes de backend (opcional; o CI já roda)

```bash
./gradlew ktlintCheck build     # precisa de JDK 21, Node 22 e Docker rodando (Testcontainers)
```

Esperado: todos os testes passando (a contagem está no relatório do último checkpoint). Relatório em `backend/build/reports/tests/test/index.html`.

## 7. Simular produção (opcional)

O perfil `prod` exige credenciais de execução sem DDL, migração separada, SMTP completo e origem HTTPS pública. Use `deploy/docker-compose.prod.yml` e `deploy/prod.env.example`, seguindo `docs/deploy.md`; trocar somente `SPRING_PROFILES_ACTIVE` no compose local já não simula uma produção válida.

## Revisão 61–80

1. Abra a edição de um produto e de uma categoria em duas abas. Salve na primeira e tente salvar o formulário antigo da segunda: deve aparecer uma recusa sem perder a primeira mudança.
2. Reordene o cardápio em dois aparelhos ao mesmo tempo: a página atualizada deve manter posições únicas.
3. Repita um check-in com o mesmo identificador: token, cliente e comanda permanecem iguais.
4. Solicite recuperação com SMTP indisponível: a resposta continua genérica; reset e entrega ficam confirmados no banco. Ao recuperar o SMTP antes de uma hora, o envio é repetido.
5. Confira `historico_acao` após mudança de estado, bloqueio e configuração: cada ação possui antes/depois operacional e autor, sem senhas, tokens ou texto livre.
6. Com mais de 100 clientes/contas, use anterior/próxima. O contador de quem vai pagar e o saldo total devem incluir todas as páginas. Relatório/CSV identificam o limite dos detalhes e mantêm os totais completos.
7. Saia e volte à página do cliente: SSE e timers reiniciam uma única vez. Confira que conta e pedidos voltam a atualizar.
8. Valide recuperação de reservas da outbox, falhas de integridade e concorrência com `Correcoes61a80Test`. Para os ciclos de vida do frontend: `cd web-customer` e `npm test`.

## Revisão 111–115

1. No painel Regras, confira o aviso de exposição. Defina limite R$ 150, leve saldo a R$ 140 e faça pedido de R$ 80: aceito com R$ 220 em aberto. Próximo pedido do cliente recusado; pagamento que reduz saldo abaixo de R$ 150 libera o próximo, desde que a conta esteja aberta e sem pausa.
2. Com pedidos pausados, abra Lançar pedido pela equipe e confira o aviso permanente de exceção. Lance, confira autor e `pausa_ignorada=true` no histórico usando [consulta restrita](preparacao-piloto.md#consulta-restrita-das-exceções). Com saldo já no limite, confira também `limite_ignorado=true`. Reenvie o mesmo ID: um pedido e uma exceção. Sem pausa e abaixo do limite: nenhuma `EXCECAO`.
3. Repita sem sinal; reabra o app e sincronize. O pedido é preservado; a auditoria usa pausa/limite do servidor ao receber. Mude as regras antes de sincronizar para verificar essa distinção.
4. Feche conta com R$ 24; confirme R$ 10. Cliente recebe orientação exata para Pagamentos → Continuar conta. A equipe toca no botão: status ABERTA, pago R$ 10, saldo R$ 14 e intenção/aviso anterior limpos; cliente atualiza em tempo real. Repetir a ação não altera os pagamentos. Novo fechamento calcula apenas saldo restante.
5. Com saldo restante no limite ou pausa ativa, Continuar conta preserva essas restrições do cliente. Sem pagamento confirmado, bloqueada, fechada, cancelada ou de outro dia: retomada recusada. Com ID de outra barraca: 404. Tente com pagamento total: conta encerrada, sem botão de retomada.
6. Simule devolução sem alterar pagamentos: preencha [controle do piloto](piloto-devolucoes.md) e faça conciliação restrita. Apure semana e decisão de prioridade. Não contar cancelamento como estorno.
7. Execute SMTP, suporte real, deploy, monitores e treinamento conforme [lista de execução](preparacao-piloto.md). Exemplos fictícios não aprovam esse passo. Registre evidência real antes de iniciar o piloto.

## Revisão 116 (checkpoint 6.3)

1. Entre no app do quiosque como atendente em um celular e como dono em outro. No painel, abra **Equipe → Aparelhos da equipe**: em até 1 minuto, os dois aparecem com o nome, o tipo (Android, iPhone/iPad ou computador), "Última sincronização" e "Tudo enviado".
2. Coloque o celular da atendente em **modo avião**. Lance pedidos para dois clientes (por exemplo 6 e 4 pastéis). O app mostra "Pedido preservado neste aparelho". No painel, a hora desse aparelho para de mudar.
3. O painel só conhece a última contagem enviada pelo aparelho; ações criadas totalmente sem sinal aparecem depois de um informe que alcance o servidor. Em ensaio, mantenha uma ação pendente com resposta 503 por mais de 15 minutos, permitindo os informes de sincronia: o contato continua recente, mas **Atenção** e a hora da ação mais antiga permanecem. A idade pode ser simulada nos testes automatizados.
4. Tire o modo avião. Em segundos o indicador volta a 🟢; na fila chegam exatamente os dois pedidos, com 10 itens no total, uma vez cada. No painel, o aparelho volta para "Tudo enviado" com a hora nova.
5. Repita com os **dois celulares em modo avião** lançando na **mesma comanda**. Volte a rede de um, depois do outro. A comanda tem os dois pedidos, nenhum duplicado, e o total soma os dois.
6. Ligue e desligue o modo avião várias vezes seguidas com um pedido guardado: ele chega uma única vez.
7. Com um pedido recusado (por exemplo, a comanda encerrada antes da sincronização), o painel mostra "1 recusada: confira no aparelho" com **Atenção**.

## Correções da auditoria geral

1. **QR revogado:** abra uma conta no cliente, gere QR novo na equipe e tente criar pedido, fechar, avisar ou reabrir pelo link antigo. Nenhuma nova ação deve ser gravada; o QR atual funciona. Reenvio de UUID já confirmado mantém um único pedido.
2. **Navegação:** com rede lenta, abra Clientes/Cardápio e mude rapidamente para Fila/Pagamentos. A resposta atrasada não pode substituir o título ou conteúdo da aba atual. E2E `auditoria-navegacao` controla as quatro corridas.
3. **Limites:** em cardápio de 31 produtos, selecione 30 e tente o último. A tela informa o limite e continua utilizável. Com 60 unidades, a próxima é recusada. Pedido com produto repetido pela API responde 400 e não altera saldo.
4. **Aparelho compartilhado:** A preserva ação, sai e B entra no mesmo aparelho. No painel, filas de A e B permanecem separadas; entrar novamente como A recupera suas ações. Sincronizações recentes não escondem uma pendência antiga já informada.
5. **Outro dia:** em ambiente descartável, preserve check-in e lançamento antes da virada do dia em São Paulo, reabra e sincronize no dia seguinte. Novas ações viram conflito com orientação para conferir a conta; não criam registros do dia novo. UUID confirmado antes da virada deve confirmar o resultado existente, sem duplicar. Atualize o app antes deste ensaio.
6. **Pedidos não pagos:** ative pagar por pedido, envie coco e pastel e abra Pagamentos → Revisar pedidos não pagos. Os itens aparecem. Cancelar sem motivo mostra erro; cancelar um item com motivo abate o saldo e aparece no cliente. Cancelar o restante remove o pedido da lista e mantém autoria/histórico. O fluxo não devolve pagamentos registrados.
7. **Cache e toque:** conferir URL com hash de `medicoes.js` nos dois fronts/service workers; a URL simples revalida. Enquanto toca Adicionar, receber atualização da conta não pode perder a seleção. Repetir em Safari e Chrome físicos na entrega final.
8. **Operação isolada:** executar os testes de imagem/implantação indicados no relatório. Subida saudável persiste digest; falha preserva `.env`; Compose independente usa o novo digest. Ensaio de restauração usa `ARQUIVO.dump --producao`, preserva o banco anterior e retorna a ele se a ativação falhar.
9. **Concorrência de cadastro:** dois envios simultâneos do mesmo WhatsApp devem manter um lead e atualizar os campos sem erro 500, preservando criação, origem e identificador originais.

## Correções da revisão externa

1. **Perfil de produção:** num ambiente de ensaio com banco descartável, rode a migração e suba o app com `SPRING_PROFILES_ACTIVE=prod`. O app inicia com o banco em V21. Antes da correção, ele recusava iniciar com "Esta imagem exige esquema V19".
2. **Relógio errado:** adiante em 10 minutos o relógio de um celular da equipe (ou mude a data para anteontem). Faça um check-in e lance um pedido. Os dois chegam normalmente, sem "criada em outro dia". Volte o relógio ao automático.
3. **Ação de ontem continua recusada:** guarde um lançamento sem sinal antes da meia-noite e sincronize no dia seguinte. Ele vira conflito com a orientação de conferir a conta, como no item 5 da seção anterior.
4. **Trocar PIN e desativar ao mesmo tempo:** com o painel aberto em duas abas, desative a atendente numa e troque o PIN na outra quase juntas. Ao final, a atendente continua **Desativada** e não entra com o PIN novo.
5. **Pagamento sem resposta e sessão vencida:** em Pagamentos, registre um pagamento parcial com o celular perdendo o sinal no envio. Deixe a sessão vencer (ou saia), entre de novo e reabra Receber dessa conta: o diálogo repete o **mesmo** valor e forma, sem deixar mudar, e a conta recebe o valor uma única vez.

## Correções da terceira revisão

1. **Pagamento sem resposta, no mesmo diálogo:** em Pagamentos, abra Receber, digite R$ 20 e ative o modo avião logo depois de tocar em Confirmar. Com o erro na tela, o valor e a forma ficam travados. Volte o sinal e confirme de novo: o aviso diz "Recebido R$ 20,00" e a conta desconta R$ 20 uma vez só.
2. **Recusa no check-in:** em Clientes → Novo cliente, preencha o WhatsApp `123` e toque em Gerar QR. O erro aparece no campo. Corrija para `(13) 99999-8888` e toque de novo: o QR sai com o número corrigido. O indicador de sincronização não mostra nenhuma ação recusada.
3. **WhatsApp longo:** tente colar um texto de mais de 25 caracteres no WhatsApp. O campo corta no limite e o botão Gerar QR continua funcionando.
4. **Recusa no lançamento não trava a fila:** lance um pedido numa comanda já encerrada (abra Pedir antes de encerrar a conta em outro aparelho). A recusa aparece na tela. Em seguida, lance um pedido para outra comanda aberta: ele entra na hora, sem ficar "preservado neste aparelho".
5. **Troca de senha continua derrubando as sessões:** repita o item 145. O resultado esperado não mudou. A corrida corrigida (troca confirmada durante o login) só se reproduz nos testes automáticos.
6. **Portão A:** com uma praia escolhida em `/teste-sinal`, abra o app do cliente e faça um pedido. Em `/admin`, a linha da praia conta só as aberturas, e a coluna Servidor mostra "—" quando não houver teste de sinal. A lista "Últimas medições" mostra o tipo de cada linha.
7. **Log do Nginx (só no ensaio de produção):** siga a seção "Nginx" de [deploy.md](deploy.md). Abra um link de comanda com `http://` e outro com `?token=a&convite=b`: o log mostra `/c/***` e `?***`, sem nenhum dos valores.

## Limpar tudo

```bash
docker compose down -v     # -v apaga também o banco local
```
