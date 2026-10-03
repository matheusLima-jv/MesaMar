# Procedimento: correção e exclusão de dados pessoais (LGPD)

Para o MVP este procedimento é **manual**, feito pelo responsável pelo MesaMar na VPS, com scripts que tornam cada passo conferível. Prazo prometido na política de privacidade: **até 15 dias** depois do pedido.

## Onde os dados pessoais ficam

| Lugar | O que tem | Quanto tempo | Como corrigir ou apagar |
|---|---|---|---|
| **Banco de dados** (`cliente`, `comanda`, `pedido`, `item_pedido`, `problema_reportado`) | Nome, WhatsApp, link da comanda, observações e motivos em texto livre, avisos de problema | Anonimização automática aos **90 dias** (nome, WhatsApp e textos livres); avisos de problema apagados aos 90 dias | `deploy/lgpd/corrigir-cliente.sh` e `deploy/lgpd/excluir-cliente.sh` |
| **Cópias de segurança** (`/opt/mesamar/backups`) | Tudo do banco, na data da cópia | 14 dias (rotação) | Não se mexe nas cópias. A exclusão é **reaplicada** se uma cópia for restaurada (abaixo) |
| **Registro de exclusões** (`/opt/mesamar/lgpd/exclusoes.txt`) | Só **UUIDs de cliente** e a data (não identifica ninguém sozinho) | Para sempre (é o que permite refazer a exclusão) | Faça cópia dele junto com a dos backups |
| **Logs da aplicação** | Sem nome, WhatsApp, link da comanda nem texto digitado. Sem SMTP, o e-mail **não** é mais escrito no log em produção | Rotação do Docker (5 arquivos de 10 MB) | Nada a apagar |
| **Log de acesso do Nginx** | O IP e o caminho. O link da comanda, o convite e a troca de senha saem como `***` | O que o Nginx já rotaciona | Nada a apagar (ver "Nginx" em `docs/deploy.md`) |
| **Celular do cliente** (navegador) | Carrinho, pedido em envio, avisos lidos e uma cópia da comanda e do cardápio (nome, guarda-sol, saldo, pedidos) | A página apaga o que for de comanda sem uso há mais de 7 dias | Só o próprio cliente apaga na hora (limpar os dados do site). Avise-o |
| **Celular da equipe** | Check-in ou pedido ainda não confirmado, até o envio | Até o servidor responder (e 3 dias no máximo) | Sai sozinho |
| **Notificações push** | Só "novo pedido", "indo pagar" e "há um aviso" (sem nome do cliente, sem texto digitado) | Na tela do celular do dono | — |

## 1. Receber o pedido

1. Confirme **quem pede** (o próprio cliente, na barraca ou por WhatsApp). Não apague a pedido de terceiro.
2. Ache o cliente. A forma mais segura é pelo **link da comanda** (`.../c/<código>`) que ele tem no celular; sem ele, pelo WhatsApp informado no cadastro, ou pelo painel do dono da barraca (nome, guarda-sol e dia).
3. Anote a data do pedido: o prazo de 15 dias conta dela.

## 2. Correção (nome ou WhatsApp errado)

```bash
export MESAMAR_COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env"
deploy/lgpd/corrigir-cliente.sh --comanda <UUID da comanda> --nome "Nome certo"
deploy/lgpd/corrigir-cliente.sh --comanda <UUID da comanda> --whatsapp +5513999998888     # ou: --whatsapp apagar
```

O UUID da comanda aparece no painel do dono (ou pergunte ao dono) e no banco. Cliente **já anonimizado** não é corrigido (não há mais o dado).

## 3. Exclusão (ou retirada do consentimento)

```bash
deploy/lgpd/excluir-cliente.sh --link "https://mesamar.0mega.fun/c/<código>"
deploy/lgpd/excluir-cliente.sh --whatsapp +5513999998888      # pode achar mais de um cliente com o mesmo número
deploy/lgpd/excluir-cliente.sh --comanda <UUID>
```

O script mostra quem vai ser anonimizado e pede que você digite `EXCLUIR`. Ele:
- troca o **nome** por "Cliente" e apaga o **WhatsApp**;
- apaga os **textos livres** (observações dos itens, detalhe dos motivos de cancelamento e do fechamento sem receber) e os avisos de problema ligados à comanda;
- troca o **link da comanda** por outro aleatório (o QR e o link antigos deixam de abrir);
- **mantém** pedidos, itens, preços e pagamentos, sem identificar a pessoa (a barraca precisa deles para as contas e relatórios; a política de privacidade explica);
- grava o UUID do cliente em `/opt/mesamar/lgpd/exclusoes.txt`.

O SQL fica em `deploy/lgpd/anonimizar-cliente.sql` e é testado no CI contra o esquema real (`LgpdSqlTest`): se uma migração mudar uma tabela, o teste quebra antes de o procedimento enferrujar.

## 4. Depois de executar (confira e responda)

- [ ] Rodou sem erro e o cliente aparece como "Cliente" no painel da barraca.
- [ ] **Cache do celular dele:** avise que o link antigo agora dá "não vale mais", mas a cópia salva no navegador só some quando ele **limpar os dados do site** (ou em 7 dias sem uso).
- [ ] **Backups:** as cópias dos últimos 14 dias ainda têm o dado. Se uma delas for restaurada, a exclusão é refeita (seção abaixo).
- [ ] **Logs:** nada a apagar. Se um dia um log novo passar a ter dado pessoal, trate como incidente e corrija o log.
- [ ] Responda ao cliente em até 15 dias dizendo o que foi feito e o que fica (os valores dos pedidos, sem identificar).
- [ ] Guarde a data e o motivo do pedido (não o dado) onde você controla o atendimento.

## 5. Restaurar uma cópia de segurança

`deploy/backup/restaurar.sh` chama `deploy/lgpd/reaplicar-exclusoes.sh` **logo depois** de restaurar (no banco de treino e no de produção). Ele refaz a anonimização de todos os UUIDs do registro. Se isso falhar, o script diz **"NÃO suba a aplicação"** e para: não ligue a aplicação com dados que já deveriam ter sido apagados.

Para refazer à mão: `deploy/lgpd/reaplicar-exclusoes.sh [banco]`.

## 6. Quando o registro se perde

Se o arquivo `/opt/mesamar/lgpd/exclusoes.txt` for perdido junto com o servidor, as exclusões feitas **antes da última cópia do banco** continuam valendo (estão no banco). As feitas entre a última cópia e a perda precisam ser refeitas a partir dos pedidos que você guardou. Por isso o registro entra na cópia que você leva para fora da VPS (`docs/deploy.md`).
