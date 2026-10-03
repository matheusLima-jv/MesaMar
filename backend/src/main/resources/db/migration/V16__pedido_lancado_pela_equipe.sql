-- Fase 6 · checkpoint 6.1: pedido lançado pela equipe (origem = ATENDENTE) guarda quem lançou.
-- O dono (usuário) ou um atendente, nunca os dois; nos pedidos do cliente, nenhum dos dois.
ALTER TABLE pedido
    ADD COLUMN criado_por_usuario_id   UUID REFERENCES usuario (id),
    ADD COLUMN criado_por_atendente_id UUID REFERENCES atendente (id),
    ADD CONSTRAINT ck_pedido_criado_por CHECK (criado_por_usuario_id IS NULL OR criado_por_atendente_id IS NULL),
    ADD CONSTRAINT ck_pedido_origem_autor CHECK (
        (origem = 'CLIENTE' AND criado_por_usuario_id IS NULL AND criado_por_atendente_id IS NULL)
        OR (origem = 'ATENDENTE' AND (criado_por_usuario_id IS NOT NULL OR criado_por_atendente_id IS NOT NULL))
    );
