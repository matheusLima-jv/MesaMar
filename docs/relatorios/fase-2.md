# Relatório — Fase 2: painel do dono

- **Branch / PR:** `fase-2-contas` → [PR #3](https://github.com/matheusLima-jv/MesaMar/pull/3) (rascunho até o último checkpoint)
- A Fase 2 foi dividida em 3 checkpoints, validados um por vez:
  - [ ] **2.1 — Contas do dono, barraca, regras e Pix** ← checkpoint atual
  - [ ] **2.2 — Atendentes (PIN) e guarda-sóis**
  - [ ] **2.3 — Cardápio**
- Decisões de arquitetura: [ADR 0005](../adr/0005-painel-do-dono.md)

---

## Checkpoint 2.1 — Contas do dono

- **Data:** 2026-09-30
- **CI:** ver o PR (ktlint, testes de backend, imagem Docker, smoke do compose e E2E no tamanho de celular)

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
