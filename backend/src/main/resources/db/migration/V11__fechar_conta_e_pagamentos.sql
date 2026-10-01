-- Fase 5 · checkpoint 5.1: o cliente fecha a conta (ABERTA → AGUARDANDO_PAGAMENTO) e os pagamentos passam a existir.

-- O que o cliente escolheu ao fechar a conta, e quando avisou que está indo pagar.
--  · metodo_pagamento_pretendido: PIX, DINHEIRO ou CARTAO (só tem valor enquanto a comanda aguarda o pagamento)
--  · troco_para_centavos: "vou pagar com R$ 100,00" (dinheiro), para o dono separar o troco
--  · aviso_pagamento_em: a hora do "estou indo pagar" (dinheiro e cartão) ou do "já paguei" (Pix); ordena a aba Pagamentos
ALTER TABLE comanda
    ADD COLUMN metodo_pagamento_pretendido VARCHAR(10)
        CHECK (metodo_pagamento_pretendido IN ('PIX', 'DINHEIRO', 'CARTAO')),
    ADD COLUMN troco_para_centavos BIGINT CHECK (troco_para_centavos > 0),
    ADD COLUMN aviso_pagamento_em TIMESTAMPTZ;

-- Pagamento confirmado pelo dono ou por um atendente (a confirmação em si é do checkpoint 5.2).
-- O sistema nunca recebe dinheiro: a linha só registra o que o quiosque conferiu.
CREATE TABLE pagamento (
    id                        UUID        PRIMARY KEY,
    barraca_id                UUID        NOT NULL REFERENCES barraca (id),
    comanda_id                UUID        NOT NULL,
    metodo                    VARCHAR(10) NOT NULL CHECK (metodo IN ('PIX', 'DINHEIRO', 'CARTAO')),
    -- Quanto abate da conta (sempre positivo)
    valor_centavos            BIGINT      NOT NULL CHECK (valor_centavos > 0),
    -- Dinheiro: quanto o cliente entregou. O troco é recebido − valor.
    valor_recebido_centavos   BIGINT,
    -- Quem confirmou: o dono (usuário) ou um atendente, nunca os dois
    confirmado_por_usuario_id UUID,
    confirmado_por_atendente_id UUID,
    confirmado_em             TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_pagamento_recebido CHECK (valor_recebido_centavos IS NULL OR valor_recebido_centavos >= valor_centavos),
    CONSTRAINT ck_pagamento_quem CHECK ((confirmado_por_usuario_id IS NULL) <> (confirmado_por_atendente_id IS NULL)),
    -- O pagamento é sempre da mesma barraca da comanda
    CONSTRAINT fk_pagamento_comanda FOREIGN KEY (comanda_id, barraca_id) REFERENCES comanda (id, barraca_id),
    CONSTRAINT fk_pagamento_usuario FOREIGN KEY (confirmado_por_usuario_id, barraca_id) REFERENCES usuario (id, barraca_id),
    CONSTRAINT fk_pagamento_atendente FOREIGN KEY (confirmado_por_atendente_id, barraca_id) REFERENCES atendente (id, barraca_id)
);

CREATE INDEX idx_pagamento_comanda ON pagamento (comanda_id);

-- Comandas que avisaram o pagamento, na ordem do aviso (aba Pagamentos do 5.2)
CREATE INDEX idx_comanda_aguardando ON comanda (barraca_id, aviso_pagamento_em) WHERE status = 'AGUARDANDO_PAGAMENTO';
