-- Fase 4 · checkpoint 4.1: fila do dono. O pedido passa a guardar quando entrou em preparo, quando foi
-- entregue ou cancelado (e por quê), e quem atendeu.
ALTER TABLE pedido
    ADD COLUMN em_preparo_em             TIMESTAMPTZ,
    ADD COLUMN entregue_em               TIMESTAMPTZ,
    ADD COLUMN cancelado_em              TIMESTAMPTZ,
    -- Esgotou / Cliente desistiu / Outro: detalhe. O cliente vê este texto na página dele.
    ADD COLUMN motivo_cancelamento       VARCHAR(80),
    -- Quem fez a última ação no pedido: o dono (usuário) ou um atendente, nunca os dois
    ADD COLUMN atendido_por_usuario_id   UUID REFERENCES usuario (id),
    ADD COLUMN atendido_por_atendente_id UUID REFERENCES atendente (id),
    ADD CONSTRAINT ck_pedido_atendido_por CHECK (atendido_por_usuario_id IS NULL OR atendido_por_atendente_id IS NULL);

-- A fila só olha o que está pendente, por ordem de chegada (horário do servidor)
CREATE INDEX idx_pedido_fila ON pedido (barraca_id, criado_em, numero_pedido) WHERE status IN ('RECEBIDO', 'EM_PREPARO');
