# Preparação operacional — ponto 115

Em 2026-10-03, o usuário orientou adiar configurações/validações externas porque serão alteradas e autorizou exemplos fictícios identificados. Este documento prepara a execução; não comprova envio SMTP, implantação, alertas recebidos nem treinamento realizado. Não liberar o piloto com base apenas nos testes locais. As validações de produção existentes permanecem.

## Dados fictícios — substituir antes de produção

Todos os dados abaixo são **FICTÍCIOS, NÃO CONFIGURADOS E NÃO FUNCIONAIS**; não copiar para o `.env` de produção:

- E-mail de suporte: `suporte@mesamar.invalid`.
- WhatsApp de suporte: `55 (13) 00000-0000`.
- Servidor SMTP: `smtp.mesamar.invalid`, porta de exemplo `587`.
- Remetente: `MesaMar <nao-responda@mesamar.invalid>`.
- Ambiente de exemplo: `/opt/mesamar` em VPS a definir; o host e o digest autorizado ainda precisam ser confirmados.

Credenciais nunca entram neste documento, no CSV do piloto ou no Git. O `.env` real deve conter suporte acessível, SMTP e as demais configurações descritas em [deploy](deploy.md). Os exemplos `.invalid` não são evidência de configuração válida.

## Lista de execução e evidências

| Atividade | Responsável | Critério de conclusão | Estado nesta revisão |
|---|---|---|---|
| SMTP | Operação | Configurar segredo fora do Git; convite e recuperação recebidos na caixa real; indisponibilidade/reenvio da outbox ensaiados sem expor corpo nos logs | Adiado por orientação do usuário |
| Suporte real | Responsável do piloto | Substituir os dois exemplos; testar contato e resposta; explicar que Reportar problema exige acompanhamento em `/admin`, sem prometer aviso externo automático | Adiado por orientação do usuário |
| Deploy | Operação | Digest da CI verde, backup e restauração validados, migração compatível, saúde e roteiro manual aprovados; guardar versão anterior e procedimento de rollback | Adiado por orientação do usuário |
| Monitores | Operação | Disponibilidade e backup externos separados; cron/heartbeat ativos; provocar falhas controladas e comprovar recebimento e recuperação dos dois alertas | Adiado por orientação do usuário |
| Treinamento | Dono e responsável do piloto | Equipe executar os exercícios abaixo em ambiente de ensaio; registrar participantes, data e dúvidas resolvidas | Material pronto; execução pendente |
| Validação manual | Dono e operação | Executar [roteiro 111–115](testar-localmente.md#revisão-111115) em dois aparelhos; guardar resultado por passo e correções | Roteiro pronto; execução pendente |

Registrar as evidências sem segredos: data, ambiente, commit/digest, responsável, resultado, referência restrita de comprovantes e pendências. Preencher nomes reais dos responsáveis antes de iniciar. Uma caixa marcada sem evidência não conclui a atividade.

## Treinamento de 30 minutos

1. 5 min: explicar limite antes do pedido, exemplo R$ 140 + R$ 80 com limite R$ 150, exposição com 60 unidades e ausência de teto para a equipe.
2. 5 min: pausar pedidos e lançar pela equipe; explicar o aviso de exceção, autoria e condições registradas no momento do recebimento pelo servidor. Repetir sem sinal e conferir a sincronização, sem prometer que o contexto anterior foi registrado.
3. 10 min: fechar conta, confirmar pagamento parcial, tocar em Continuar conta, conferir valor pago/saldo e fazer novo pedido. Repetir com saldo ainda acima do limite; o cliente continua bloqueado. Conta totalmente paga não é reaberta por essa ação.
4. 5 min: conferir Pix no banco; distinguir cancelamento, perda, devolução e estorno; preencher ocorrência no controle do piloto sem alterar pagamentos originais.
5. 5 min: reportar problema, usar contato real quando definido e reconhecer sinais de offline/falha. Dono confere o acompanhamento em `/admin` e sabe quem acionar.

Antes de iniciar o piloto, dono e operação registram aprovação dos seis itens e a decisão sobre pendências. Infraestrutura, firewall, SSH, DNS e Cloudflare não foram alterados nesta revisão.

## Consulta restrita das exceções

O histórico existente retém ações por 90 dias. Pedido normal da equipe continua guardando origem e autor. Só o uso efetivo de pausa/limite gera `EXCECAO`; reenvio do mesmo pedido não acrescenta outra. Para auditoria, operador autorizado consulta na própria barraca, com parâmetros vinculados pela ferramenta SQL (não interpolar entrada):

```sql
SELECT h.entidade_id AS pedido_id, h.criado_em, h.usuario_id, h.atendente_id,
       h.depois->>'pausa_ignorada' AS pausa_ignorada,
       h.depois->>'limite_ignorado' AS limite_ignorado,
       h.depois->>'saldo_antes_centavos' AS saldo_antes_centavos,
       h.depois->>'limite_centavos' AS limite_centavos
FROM historico_acao h
WHERE h.barraca_id = :barraca_id AND h.entidade = 'pedido' AND h.acao = 'EXCECAO'
ORDER BY h.criado_em DESC;
```

O registro usa as condições do servidor no recebimento, inclusive para pedido offline. Não reconstrói a condição que existia no aparelho antes do envio. A retomada parcial aparece no histórico da comanda como `AGUARDANDO_PAGAMENTO` → `ABERTA`, com autor. Ainda não existe tela de consulta desse histórico para o dono; o procedimento de auditoria acima exige acesso operacional autorizado.
