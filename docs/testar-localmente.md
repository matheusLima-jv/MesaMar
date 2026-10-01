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
| 6 | Mesmo formulário | Enviar vazio 9 vezes seguidas | Na 9ª: "Recebemos muitos envios deste aparelho" (espere 10 min ou reinicie o app para zerar) |
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
| 29 | Painel → Equipe | Adicionar "Carla" com PIN `1234` | Recusa por ser sequência; com `2580` entra |
| 30 | Mesma tela | Adicionar "carla" de novo | "Já existe um atendente com esse nome." |
| 31 | Regras | Baixar o limite de guarda-sóis para 5 | Recusa: "Você tem 20 guarda-sóis ativos…" |
| 32 | Painel → Cardápio | Criar "Bebidas" e "Porções"; cadastrar 15 itens com "Adicionar e cadastrar outro" | Cada item volta ao formulário já na mesma categoria; anote quanto tempo levou |
| 33 | Cardápio | Tocar em "Esgotou" num item do meio da lista | A página volta no mesmo item, riscado, com o botão "Voltou" |
| 34 | Cardápio | Subir e descer itens e categorias (↑ ↓) | A ordem muda na hora |
| 35 | Cardápio | "Tirar do cardápio" num item | Some da categoria e aparece em "Fora do cardápio" |
| 36 | Cardápio → Editar "Bebidas" | Tentar excluir com produtos dentro | Recusa: "ainda tem N produtos" |
| 37 | Painel → Equipe | Adicionar "Carla" com PIN `2580` e anotar o código da barraca | Aparece na lista como "Ativo" |
| 38 | Celular: `http://IP-DO-PC:8080/staff/` | "Sou da equipe" → código → Carla → PIN `1111` | "PIN errado. Restam 4 tentativas…" |
| 39 | Mesmo celular | PIN `2580` | Abre o app na aba Cardápio |
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

> A instalação na tela inicial (item 41) exige **HTTPS** fora do `localhost`. No celular pela rede local (`http://IP-DO-PC`), o app abre, mas o Chrome não oferece "Instalar". Para testar a instalação de verdade, use o ambiente publicado (`https://mesamar.0mega.fun/staff/`).

Consultar direto no banco, se quiser:

```bash
docker compose exec db psql -U mesamar -c "select nome, nome_barraca, praia, whatsapp, criado_em from lead order by criado_em desc;"
```

## 5. Testes E2E (os mesmos do CI)

Com o `docker compose up` rodando, em outro terminal:

```bash
cd e2e
npm ci
npx playwright install chromium
E2E_ADMIN_PASSWORD=uma-senha-sua npx playwright test    # no Windows (PowerShell): $env:E2E_ADMIN_PASSWORD="..."; npx playwright test
npx playwright show-report
```

Esperado: **10 passaram**. O teste de 4G imprime o tempo de carregamento. Rode o E2E logo depois de subir o app, porque os testes enviam o formulário e contam no limite de envios por aparelho.

## 6. Testes de backend (opcional; o CI já roda)

```bash
./gradlew ktlintCheck build     # precisa de JDK 21, Node 22 e Docker rodando (Testcontainers)
```

Esperado: 267 testes passando. Relatório em `backend/build/reports/tests/test/index.html`.

## 7. Simular produção (opcional)

Para ver o comportamento do perfil `prod` (logs em JSON, health sem detalhes), coloque no `.env`:

```dotenv
SPRING_PROFILES_ACTIVE=prod
```

e rode `docker compose up -d` de novo. Não é necessário para validar a fase.

## Limpar tudo

```bash
docker compose down -v     # -v apaga também o banco local
```
