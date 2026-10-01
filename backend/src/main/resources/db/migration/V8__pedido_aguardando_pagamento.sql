-- Fase 3 · checkpoint 3.3: modo "pagar a cada pedido".
-- Nesse modo o pedido nasce AGUARDANDO_PAGAMENTO e só entra na fila (RECEBIDO) quando o dono confirma
-- o pagamento (Fase 5). Fora do modo, o pedido nasce RECEBIDO, como antes.
ALTER TABLE pedido ALTER COLUMN status TYPE VARCHAR(20);
ALTER TABLE pedido DROP CONSTRAINT pedido_status_check;
ALTER TABLE pedido ADD CONSTRAINT pedido_status_check
    CHECK (status IN ('AGUARDANDO_PAGAMENTO', 'RECEBIDO', 'EM_PREPARO', 'ENTREGUE', 'CANCELADO'));
