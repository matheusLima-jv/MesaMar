# 0022 — Correções da auditoria geral

Data: 2026-10-03. Complementa as ADRs 0020 e 0021. Esquema resultante: V21.

## Contexto e autorização

A [auditoria geral](../relatorios/auditoria-geral-2026-10-03.md) confirmou dez bugs. O usuário pediu sua correção e autorizou também os dois fluxos de produto: recusar novas ações offline de outro dia e revisar/cancelar pedidos ainda não pagos. SMTP, suporte real, implantação e monitores reais continuam adiados; os exemplos fictícios permanecem identificados.

## Decisões

1. **Credencial dentro da escrita.** A comanda é travada antes de conferir o token original enviado pelo cliente. QR revogado não pode criar pedido, fechar conta, avisar pagamento ou reabrir. Reenvio de UUID já confirmado continua devolvendo o resultado gravado, sem nova escrita.
2. **Limites consistentes.** Cada produto aparece uma vez por pedido, com até 20 unidades. A tela da equipe impede ultrapassar 30 produtos e 60 unidades. Uma falha ao preparar a pendência devolve os controles ao operador.
3. **Navegação invalida respostas.** Toda troca de aba da equipe avança a geração da tela antes de chamar sua view. Respostas antigas não substituem Fila ou Pagamentos.
4. **Sincronia por autor.** A V21 muda a chave para barraca, aparelho e autor, preservando os registros V20. Acrescenta `pendente_desde`, inicializado com o último contato para filas legadas. O app informa a criação da ação pendente mais antiga. Um heartbeat não rejuvenece essa ação. Sem pendências, a idade é nula; conflitos ou mais de 15 minutos recebem atenção. Relações com autor também conferem a barraca. Retenção de 30 dias e teto de 100 linhas continuam; atenção é ordenada antes do limite.
5. **Dia da ação offline.** O app envia `criadoNoAparelhoEm` usando a data original da fila, sem renová-la nas tentativas. Para UUID novo, o servidor exige o mesmo dia em São Paulo e recusa horário mais de cinco minutos adiantado. A recusa vira conflito visível, sem criar cliente, comanda ou pedido. O operador confere a conta e descarta explicitamente a pendência; não há conversão automática para o dia novo. UUID confirmado é resolvido antes dessa regra, inclusive no dia seguinte. O instante do aparelho serve para esta restrição; horário financeiro e ordenação continuam do servidor. Para compatibilidade, requisições legadas sem esse campo continuam aceitas; navegadores antigos precisam receber o bundle atualizado para aplicar a proteção.
6. **Revisão antes de receber.** Pagamentos abre os itens dos pedidos `AGUARDANDO_PAGAMENTO` da comanda da sessão. Cancelar pedido ou item reutiliza o diálogo existente, com motivo obrigatório, autoria, histórico e proteção contra saldo negativo. A lista e o saldo são recarregados após a confirmação. Pagamentos já registrados não são estornados por esse fluxo; devoluções e estornos seguem a avaliação do piloto.
7. **Cache pela identidade do arquivo.** URLs estáticas sem hash revalidam. URLs com hash conferido pelo resolver e assets do Vite continuam imutáveis. Os dois fronts e seus service workers usam o MD5 do conteúdo de `medicoes.js` na URL; alterar o script altera o bundle publicado e a versão do cache.
8. **Cadastro concorrente.** O lead é inserido/atualizado por `ON CONFLICT (whatsapp)`, mantendo id, origem e criação do primeiro registro. A mesma pessoa não ganha duplicata nem erro de integridade na corrida.
9. **Imagem persistente.** Implantação prepara a configuração sem executar a `.env`, preserva outros campos e só troca o arquivo após migração compatível e subida saudável. Falha preserva o digest anterior. Compose independente usa o arquivo persistido; overrides do shell devem ser removidos conforme o guia. O helper acompanha o script no host.
10. **Recuperação com contrato explícito.** O comando é `restaurar.sh ARQUIVO.dump [--producao]`; argumentos inválidos são recusados antes de acessar o banco. O treino tem nome único mostrado no log e é removido explicitamente após conferir. Produção conserva o banco anterior como resgate.
11. **Toque preservado.** Atualização automática do cliente espera o fim dos ponteiros ativos antes de reconstruir o DOM. O redesenho usa o carrinho atualizado depois do click. Encerrar a página cancela os callbacks. Isso corrige a troca de botão entre pressionar e soltar, reproduzida em teste controlado.

## Consequências e validação

Aplicar V21 com backup validado; não reduzir Flyway manualmente. Foram acrescentadas regressões para concorrência, revogação, isolamento, idade da fila, upgrade com dados V20, cache, limites locais, dia da ação e revisão de itens. A matriz Playwright cobre navegação com respostas atrasadas, cancelamento antes de pagamento e os fluxos existentes. Resultados e comandos estão no [relatório das correções](../relatorios/correcoes-auditoria-geral.md).
