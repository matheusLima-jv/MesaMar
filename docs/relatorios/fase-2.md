# Relatório — Fase 2: painel do dono

- **Branch / PR:** `fase-2-contas` → [PR #3](https://github.com/matheusLima-jv/MesaMar/pull/3) (rascunho até o último checkpoint)
- A Fase 2 foi dividida em 4 checkpoints, aprovados um por vez (os testes manuais ficam para a entrega final, a pedido do Matheus):
  - [x] **2.1 — Contas do dono, barraca, regras e Pix** (aprovado)
  - [x] **2.2 — Equipe (PIN) e guarda-sóis** (aprovado)
  - [ ] **2.3 — Cardápio** ← checkpoint atual (final deste arquivo)
  - [ ] **2.4 — App do quiosque (PWA): login do dono e do atendente, instalação na tela inicial**
- Decisões de arquitetura: [ADR 0005](../adr/0005-painel-do-dono.md)

---

## Checkpoint 2.1 — Contas do dono

- **Data:** 2026-09-30
- **CI:** ✅ verde: ktlint, **170 testes de backend** (eram 98 na Fase 1), imagem Docker, smoke do compose e **10 E2E** no tamanho de celular (landing em 358 ms no 4G simulado)

### 1. O que foi feito

- **Convites no `/admin`.** Cada lead ganhou um botão "Gerar convite", e também dá para gerar um convite avulso com uma observação. O link aparece **uma vez**, com o botão "Enviar pelo WhatsApp" e a mensagem pronta para o número do lead. Uma tabela mostra os convites e a situação de cada um: aguardando, conta criada (com o e-mail) ou expirado.
- **Cadastro em `/painel/cadastro?convite=…`.** Pede nome, e-mail e senha. O convite vale 14 dias e uma conta. O e-mail é único sem diferenciar maiúsculas. A senha precisa ter de 10 a 72 caracteres e não pode ser igual ao e-mail.
- **Login em `/painel/entrar`**, separado do admin: a senha de um não entra no outro, e as duas áreas funcionam ao mesmo tempo no mesmo navegador. Limite de 10 tentativas por IP a cada 10 minutos. O último acesso fica registrado.
- **"Esqueci a senha".** Link por e-mail que vale 1 hora e uma vez só; pedir outro invalida o anterior. A tela responde igual exista ou não a conta, com limite de 5 pedidos por IP a cada 15 minutos. **Sem SMTP, o e-mail vai para o log**.
- **Painel `/painel`** (no celular primeiro):
  - **Primeiro acesso** leva para "Vamos cadastrar a barraca" (nome + praia). O código da barraca é gerado automaticamente e **não muda ao renomear**, porque os QR codes vão depender dele.
  - **Home** com o checklist "O que falta para abrir": dados ✓, chave Pix, regras, e **"Em breve"** para atendentes, guarda-sóis e cardápio, sem fingir que já existem. Traz também um resumo das regras e do Pix.
  - **Regras:**
    - quando o cliente paga: no final ou a cada pedido;
    - limite de conta aberta em reais: aceita `150`, `150,00` ou `1.250,50`, e vazio significa sem limite;
    - número de guarda-sóis: 1 a 200;
    - alerta de pedido parado: 1 a 120 minutos.
  - **Chave Pix:**
    - aceita CPF, CNPJ (inclusive o novo alfanumérico), celular, e-mail ou chave aleatória, com validação de dígito;
    - o nome do recebedor (até 25) e a cidade (até 15) ficam sem acento e em maiúsculas, que é o formato exigido pelo QR do Pix.
- **Isolamento:** nenhuma URL do painel tem id de barraca, e tudo parte de quem está logado.

### 2. Como validar

Siga a seção **"Painel do dono"** de [`docs/testar-localmente.md`](../testar-localmente.md), itens 14 a 25.

### 3. Testes automatizados

- **Unidade:** chave Pix (válidas e inválidas por tipo, máscara, texto sem acento), dinheiro (reais ↔ centavos), tokens (tamanho, alfabeto, SHA-256) e código da barraca.
- **Integração** (aplicação real + PostgreSQL):
  - painel exige login;
  - convite inválido, usado ou expirado;
  - e-mail repetido em outra caixa não gasta o convite;
  - regras de senha, com a senha nunca voltando no HTML;
  - login com e-mail em qualquer caixa e último acesso;
  - separação admin × painel;
  - limites de tentativas;
  - troca de senha: resposta igual, link de uso único, link antigo invalidado, expirado e senha curta;
  - barraca: criação com padrões, códigos únicos, validação de regras e Pix;
  - isolamento entre donos;
  - convites no admin: link único, hash no banco, WhatsApp e status "Conta criada".
- **E2E (Pixel 7):** o admin gera o convite, o dono cria a conta em outro aparelho, entra, cadastra a barraca e o Pix, e o link não serve de novo. Tudo isso sem erro no console (CSP) e sem rolagem lateral.

### 4. O que ficou de fora (de propósito)

- **SMTP em produção:** o código está pronto, mas falta você escolher o serviço e criar SPF/DKIM na Cloudflare (ver `deploy.md`).
- **Trocar e-mail ou senha estando logado:** hoje só pelo "esqueci a senha". Entra se fizer falta no piloto.
- **Um dono com várias barracas:** fora do MVP (ADR 0005).

---

## Checkpoint 2.2 — Equipe e guarda-sóis

- **Data:** 2026-09-30
- **CI:** ✅ verde na primeira tentativa: **201 testes de backend** (+31 neste checkpoint), imagem Docker, smoke do compose e 10 E2E

### 1. O que foi feito

- **Equipe em `/painel/equipe`:**
  - adicionar atendente com nome (ou apelido) e PIN;
  - trocar o PIN;
  - desativar e reativar;
  - desbloquear.
- **Regras do PIN:**
  - de 4 a 6 números;
  - recusa todos iguais (`1111`) e sequências (`1234`, `4321`);
  - guardado com BCrypt, e nunca volta preenchido na tela.
- **Bloqueio:**
  - 5 PINs errados seguidos bloqueiam o atendente por **15 minutos**;
  - durante o bloqueio, nem o PIN certo entra;
  - acertar o PIN zera a contagem;
  - o dono pode desbloquear antes, e trocar o PIN também desbloqueia;
  - a conferência trava a linha no banco, então tentativas em paralelo não furam o limite (há teste com 12 tentativas simultâneas).
- **Nomes únicos na barraca:** não diferencia maiúsculas. No login do app, o atendente vai escolher o próprio nome e digitar o PIN.
- **Guarda-sóis em `/painel/guarda-sois`:**
  - **"do número X até Y"** cria os que faltam, reativa os desativados da faixa e mantém os que já existem;
  - **nunca passa do limite**, e a mensagem diz quanto ainda cabe;
  - a tela já sugere a próxima faixa (depois de criar 1–20, propõe 21–30);
  - grade de números: um toque desativa ou reativa, e reativar também respeita o limite.
- **Regras:** o limite de guarda-sóis não pode descer abaixo dos que estão ativos.
- **Checklist da home:** passou a contar guarda-sóis e equipe. A equipe é marcada como **opcional**, porque o dono pode atender sozinho.
- **Refatoração:** todas as páginas do painel recebem o dono e a barraca pelo mesmo `PainelAdvice`. A barraca nunca vem da URL nem do formulário.

### 2. Decisões

- **O login do atendente é "código da barraca + nome + PIN"**, e não só PIN. Com o nome, o bloqueio por tentativas vale para uma pessoa, e não para a barraca inteira. Também não é preciso garantir que dois atendentes tenham PINs diferentes.
- **O bloqueio expira sozinho em 15 minutos.** Exigir o dono toda vez travaria a operação no pico. O dono ainda pode desbloquear antes.
- **A tela de login do atendente chega no checkpoint 2.4, com o app do quiosque.** A regra de conferência do PIN já está pronta e testada.

### 3. Testes automatizados novos

- **Unidade:** regras do PIN (aceitos e recusados).
- **Integração (equipe):**
  - PIN guardado em hash;
  - nome repetido recusado na mesma barraca e permitido em barracas diferentes;
  - bloqueio após 5 erros, e o PIN certo não entra enquanto bloqueado;
  - desbloqueio pelo dono;
  - acerto zera a contagem;
  - 12 tentativas em paralelo resultam em exatamente 4 erros e 8 bloqueios;
  - troca de PIN desbloqueia e invalida o antigo;
  - atendente desativado não entra;
  - PIN não vale com o id de outra barraca.
- **Integração (guarda-sóis):**
  - lote de 1 a 20 e a sugestão da próxima faixa;
  - limite com a mensagem de quanto cabe;
  - faixa repetida sem duplicar, reativando os desativados;
  - faixa inválida;
  - religar respeita o limite, e o limite não desce abaixo dos ativos.
- **Isolamento:** o dono da barraca B tenta **cada ação** (trocar PIN, desativar, desbloquear, alternar guarda-sol) com ids da barraca A. Nada muda, e ele não vê nada de A.

### 4. Como validar (na entrega final)

Itens 26 a 31 de [`docs/testar-localmente.md`](../testar-localmente.md).

---

## Checkpoint 2.3 — Cardápio

- **Data:** 2026-09-30
- **CI:** (preenchido ao final)

### 1. O que foi feito

- **Cardápio em `/painel/cardapio`**, na ordem em que o cliente vai ver:
  - **Categorias:** criar, renomear, subir ou descer, excluir. Só exclui categoria vazia, então um produto nunca fica órfão.
  - **Produtos:** nome, preço em reais (`12`, `12,50`, `1.200,00`), descrição opcional, categoria e ordem dentro da categoria.
  - **"Esgotou" com um toque:** o botão grande ao lado de cada item vira **"Voltou"** e risca o item. Depois do toque, a página volta **no mesmo item**, sem jogar o dono para o topo.
  - **"Tirar do cardápio" e "Voltar ao cardápio":** o produto some para o cliente, mas nunca é apagado, porque pedidos e relatórios antigos continuam apontando para ele.
  - **"Adicionar e cadastrar outro":** volta ao formulário na mesma categoria, para cadastrar 15 itens em sequência sem navegar.
- **Limites:** até 30 categorias e 300 produtos por barraca, e preço entre R$ 0,01 e R$ 10.000,00.
- **Integridade no banco:** uma FK composta (`categoria_id, barraca_id`) impede que um produto aponte para a categoria de outra barraca, mesmo que um bug no código tente.
- **Checklist da home:** o cardápio conta itens ativos. Agora só o **app do quiosque** (2.4) aparece como "Em breve".

### 2. Decisões

- **Produto nunca é excluído, só sai do cardápio.** A Fase 3 congela nome e preço no pedido, mas o relatório de "mais vendidos" precisa do produto.
- **Trocar o preço vale para os próximos pedidos.** Os pedidos já feitos guardam o preço da hora (snapshot da Fase 3). A tela de edição avisa isso.
- **"Esgotado" é diferente de "fora do cardápio".** O esgotado continua visível para o cliente, desabilitado (como pede a Fase 3), porque avisa "hoje tem, mas acabou agora". O fora do cardápio some.

### 3. Testes automatizados novos

- **Criação e validação:**
  - produto e categoria, com o valor em reais convertido para centavos;
  - nome vazio, preço inválido, zero ou acima do máximo;
  - categoria com nome repetido, sem diferenciar maiúsculas.
- **Uso no dia a dia:**
  - "salvar e cadastrar outro" volta com a categoria já escolhida;
  - esgotado liga e desliga, e a página volta no item;
  - subir e descer produtos e categorias (o primeiro não sobe);
  - tirar do cardápio e voltar;
  - editar muda preço e categoria;
  - só exclui categoria vazia.
- **Isolamento:**
  - o dono B não vê o cardápio de A;
  - abrir a edição do produto de A dá 404;
  - as **7 ações** de escrita com ids de A (editar, esgotado, tirar do cardápio, mover produto, renomear, mover e excluir categoria) não mudam nada;
  - criar um produto de B apontando para a categoria de A é recusado.
- **E2E (celular):** o fluxo do dono agora cria os guarda-sóis 1–30, uma categoria e um produto, e marca "Esgotou", sempre conferindo que não há rolagem lateral nem erro no console.

### 4. Como validar (na entrega final)

Itens 32 a 36 de [`docs/testar-localmente.md`](../testar-localmente.md).
