-- Fase 3 · checkpoint 3.2: pedidos feitos pelo cliente na página dele (/c/{token}).

-- Alvo da FK composta do item: o produto do item é sempre da mesma barraca do pedido.
ALTER TABLE produto ADD CONSTRAINT uk_produto_barraca UNIQUE (id, barraca_id);

CREATE TABLE pedido (
    -- Gerado no celular do cliente: reenviar o mesmo pedido (sinal ruim, toque duplo) cai nesta chave
    -- e devolve o pedido que já existe, em vez de criar outro.
    id                  UUID        PRIMARY KEY,
    barraca_id          UUID        NOT NULL REFERENCES barraca (id),
    comanda_id          UUID        NOT NULL,
    -- Dia de operação no fuso da barraca (America/Sao_Paulo): o número do pedido recomeça a cada dia
    dia                 DATE        NOT NULL,
    numero_pedido       INTEGER     NOT NULL CHECK (numero_pedido > 0),
    origem              VARCHAR(10) NOT NULL DEFAULT 'CLIENTE' CHECK (origem IN ('CLIENTE', 'ATENDENTE')),
    status              VARCHAR(12) NOT NULL DEFAULT 'RECEBIDO'
        CHECK (status IN ('RECEBIDO', 'EM_PREPARO', 'ENTREGUE', 'CANCELADO')),
    -- Relógio do SERVIDOR: é ele que define a ordem da fila, nunca o do celular
    criado_em           TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_pedido_numero UNIQUE (barraca_id, dia, numero_pedido),
    CONSTRAINT uk_pedido_barraca UNIQUE (id, barraca_id),
    CONSTRAINT fk_pedido_comanda FOREIGN KEY (comanda_id, barraca_id) REFERENCES comanda (id, barraca_id)
);

CREATE INDEX idx_pedido_comanda ON pedido (comanda_id, criado_em);

CREATE TABLE item_pedido (
    id                      UUID         PRIMARY KEY,
    pedido_id               UUID         NOT NULL,
    barraca_id              UUID         NOT NULL,
    produto_id              UUID         NOT NULL,
    -- Posição do item no pedido (1, 2, 3…): a ordem em que o cliente montou o carrinho
    linha                   INTEGER      NOT NULL CHECK (linha > 0),
    -- Nome e preço congelados na hora do pedido: mudar o cardápio depois não mexe na conta
    nome_snapshot           VARCHAR(60)  NOT NULL,
    preco_snapshot_centavos BIGINT       NOT NULL CHECK (preco_snapshot_centavos BETWEEN 1 AND 1000000),
    quantidade              INTEGER      NOT NULL CHECK (quantidade BETWEEN 1 AND 99),
    observacao              VARCHAR(80),
    cancelado               BOOLEAN      NOT NULL DEFAULT FALSE,
    motivo_cancelamento     VARCHAR(80),
    CONSTRAINT uk_item_linha UNIQUE (pedido_id, linha),
    CONSTRAINT fk_item_pedido FOREIGN KEY (pedido_id, barraca_id) REFERENCES pedido (id, barraca_id),
    CONSTRAINT fk_item_produto FOREIGN KEY (produto_id, barraca_id) REFERENCES produto (id, barraca_id)
);

CREATE INDEX idx_item_pedido ON item_pedido (pedido_id);
