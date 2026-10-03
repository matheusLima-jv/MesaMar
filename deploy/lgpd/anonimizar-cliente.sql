-- Anonimiza UM cliente na hora (pedido de exclusão ou de retirada do consentimento), sem esperar os 90 dias.
-- Uso: psql -v cliente=<UUID do cliente> -f anonimizar-cliente.sql   (os scripts excluir-cliente.sh e
-- reaplicar-exclusoes.sh fazem isso). Pode rodar de novo sem problema (idempotente).
--
-- O que muda: nome vira "Cliente", o WhatsApp é apagado, os textos livres somem (observação do item; detalhe do motivo de
-- cancelamento e do fechamento sem receber, que podem citar pessoas), os avisos de "reportar problema" ligados à comanda
-- são apagados e o LINK da comanda é trocado por um valor aleatório (o QR e o link antigos deixam de abrir).
-- O que NÃO muda: pedidos, itens, preços e pagamentos (a barraca precisa deles para as contas e relatórios).
BEGIN;

UPDATE item_pedido
SET observacao = NULL
WHERE observacao IS NOT NULL
  AND pedido_id IN (SELECT p.id FROM pedido p JOIN comanda c ON c.id = p.comanda_id WHERE c.cliente_id = :'cliente'::uuid);

UPDATE item_pedido
SET motivo_cancelamento = trim(split_part(motivo_cancelamento, ':', 1))
WHERE motivo_cancelamento LIKE '%:%'
  AND pedido_id IN (SELECT p.id FROM pedido p JOIN comanda c ON c.id = p.comanda_id WHERE c.cliente_id = :'cliente'::uuid);

UPDATE pedido
SET motivo_cancelamento = trim(split_part(motivo_cancelamento, ':', 1))
WHERE motivo_cancelamento LIKE '%:%'
  AND comanda_id IN (SELECT id FROM comanda WHERE cliente_id = :'cliente'::uuid);

DELETE FROM problema_reportado
WHERE comanda_id IN (SELECT id FROM comanda WHERE cliente_id = :'cliente'::uuid);

UPDATE comanda
SET fechamento_forcado_motivo = CASE WHEN fechamento_forcado_motivo IS NULL THEN NULL ELSE 'Removido (LGPD)' END,
    token_anterior = NULL,
    token_acesso = replace(gen_random_uuid()::text, '-', '')
WHERE cliente_id = :'cliente'::uuid;

UPDATE cliente
SET nome = 'Cliente', whatsapp_e164 = NULL, anonimizado_em = COALESCE(anonimizado_em, now())
WHERE id = :'cliente'::uuid;

COMMIT;
