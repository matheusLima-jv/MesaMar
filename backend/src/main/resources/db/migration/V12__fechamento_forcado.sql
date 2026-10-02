-- Fase 5 · checkpoint 5.2: fechamento da conta pelo quiosque.
-- Quando o dono fecha uma conta que ficou com saldo (o cliente foi embora sem pagar), o motivo e o valor ficam
-- gravados para a auditoria e para o relatório do dia (5.3). Só o dono pode fazer isso.
-- Pedido que esperava o pagamento (modo "pagar a cada pedido"): a hora em que o pagamento o colocou na fila.
-- Sem isso, o tempo de espera e o alerta de atraso contariam desde que o cliente pediu, e não desde que o pedido
-- ficou liberado para o quiosque atender.
ALTER TABLE pedido ADD COLUMN na_fila_em TIMESTAMPTZ;

ALTER TABLE comanda
    ADD COLUMN fechamento_forcado_motivo VARCHAR(120),
    ADD COLUMN saldo_perdoado_centavos BIGINT CHECK (saldo_perdoado_centavos > 0),
    ADD COLUMN fechada_por_usuario_id UUID,
    ADD CONSTRAINT ck_comanda_fechamento_forcado CHECK (
        (fechamento_forcado_motivo IS NULL AND saldo_perdoado_centavos IS NULL AND fechada_por_usuario_id IS NULL)
        OR (fechamento_forcado_motivo IS NOT NULL AND saldo_perdoado_centavos IS NOT NULL AND fechada_por_usuario_id IS NOT NULL)
    ),
    -- Quem fechou é um dono da mesma barraca da comanda
    ADD CONSTRAINT fk_comanda_fechada_por FOREIGN KEY (fechada_por_usuario_id, barraca_id) REFERENCES usuario (id, barraca_id);
