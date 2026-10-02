# ADR 0015 — Barraca de demonstração e materiais impressos

- **Status:** aceito
- **Data:** 2026-10-02

## Contexto

O piloto começa com um treino: o dono e os atendentes precisam **errar à vontade** (fazer check-in, cancelar, receber, bloquear) sem mexer em dado de ninguém e sem medo de cobrar alguém de verdade. E o balcão precisa de dois papéis impressos: um guia de uma página e a plaquinha que explica ao cliente como pedir pelo celular.

## Decisão

**Uma barraca de demonstração de verdade, marcada no banco.**
- Coluna `barraca.demo` (V13), com um índice único parcial: **só existe uma**. Não é um modo "de mentira" do código: é uma barraca como as outras (mesmos serviços, mesmas telas, mesmos testes), o que garante que o treino mostra exatamente o que o piloto vai fazer.
- **Tudo avisa que é treino:** uma faixa amarela fixa no painel do dono, no app do quiosque e na página do cliente ("Barraca de treino: nada aqui é de verdade. Não pague."). O dado vem do servidor (`demo` na sessão do app e na resposta da comanda), não de uma convenção de nome.
- **A chave Pix é fictícia** (uma chave aleatória que não pertence a ninguém, `00000000-0000-4000-8000-000000000000`): o QR Pix da demonstração é um QR válido, para treinar o fluxo, mas não leva dinheiro a lugar nenhum. Testado.
- **O e-mail do dono demo usa o domínio reservado `.invalid`** (`demo@treino.invalid`, RFC 2606): "esqueci a senha" nunca chega a um estranho.

**Quem cria e como.** Só o projeto, pelo **/admin** (botão "Criar a barraca de demonstração", que vira "Reiniciar…"). Mostra **uma vez** o e-mail e a senha do dono, o código da barraca e o PIN da atendente; no banco ficam só os hashes (como em qualquer conta). Ficou no /admin e não no painel do dono porque reiniciar **troca a senha e o PIN**, e quem treina não deveria se trancar para fora.

**O que ela traz:** 6 produtos em 2 categorias, 20 guarda-sóis, a atendente "Carla (treino)" e **dois clientes de exemplo já com pedido** ("Ana (treino)" e "Beto (treino)"), para a fila e a aba Pagamentos não começarem vazias.

**Reiniciar apaga o que o treino gerou** (push, pagamentos, itens, pedidos, comandas, clientes, contadores, equipe, guarda-sóis, cardápio, na ordem das chaves estrangeiras), **volta as regras ao padrão** (pagar no final, sem limite, sem pausa, alerta de 10 min) e recria os dados de exemplo, com credenciais novas. **Não toca em nenhuma outra barraca** (testado: uma barraca de verdade ao lado, com pedidos, fica idêntica). A barraca e o dono permanecem (mesmo id, mesmo endereço).

**Materiais impressos como páginas do painel**, não como PDF gerado: `/painel/materiais` (índice), `/painel/materiais/guia` e `/painel/materiais/plaquinha`. Cada uma leva o nome e o código **da barraca de quem está logado** e uma folha de estilo de impressão em **A4** (`@media print`, sem a barra de navegação). O dono usa "Imprimir" do navegador, ou "Salvar como PDF". Motivos: nada de biblioteca de PDF, o texto acompanha o produto (o guia usa o endereço do app e o código da barraca), e a CSP restrita continua valendo (o botão "Imprimir" está num arquivo `js/imprimir.js`, não na página).
- **O guia** (1 página): entrar no app, check-in em 20 segundos, atender a fila, receber o pagamento (com o aviso do Pix e a regra "só o dono fecha sem receber") e o que fazer se algo não parecer certo.
- **A plaquinha**: "Peça pelo celular" e "Faça seu cadastro aqui e receba seu QR", em três passos, com o nome da barraca. O "aqui" é o balcão: o cadastro (check-in) é feito pela equipe. O autocadastro do cliente por um QR fixo é da Fase 8 e não existe ainda.

## Consequências

- **Os números do piloto não se misturam com o treino** só enquanto a barraca de demonstração for o único lugar do treino. O relatório do dia é por barraca, então o treino não aparece no relatório de uma barraca de verdade, mas **uma contagem global** (por exemplo, "quantas comandas o sistema teve") conta o treino junto. A Fase 7 não tem nenhuma; se uma aparecer, filtre por `barraca.demo = false`.
- **A demonstração roda no mesmo servidor e no mesmo banco.** Um treino pesado compete por recursos com a barraca de verdade, o que, para uma barraca-piloto, é irrelevante. Treine fora do horário de pico.
- **O guia não fala do botão "reportar problema"**: ele entra no checkpoint 7.3, e o guia ganha a linha quando o botão existir.
- O guia imprime numa folha, mas **usa 3/4 da altura**: sobra espaço no rodapé para o Matheus escrever à mão o telefone de suporte.
