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

Esperado: 244 testes passando. Relatório em `backend/build/reports/tests/test/index.html`.

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
