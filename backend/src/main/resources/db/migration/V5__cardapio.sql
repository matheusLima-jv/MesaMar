-- Fase 2 · checkpoint 2.3: cardápio (categorias e produtos).

CREATE TABLE categoria (
    id         UUID        PRIMARY KEY,
    barraca_id UUID        NOT NULL REFERENCES barraca (id),
    nome       VARCHAR(40) NOT NULL,
    ordem      INTEGER     NOT NULL,
    criada_em  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Alvo da FK composta do produto (garante produto e categoria na mesma barraca)
    CONSTRAINT uk_categoria_barraca UNIQUE (id, barraca_id)
);

CREATE UNIQUE INDEX uk_categoria_nome ON categoria (barraca_id, lower(nome));

CREATE TABLE produto (
    id             UUID         PRIMARY KEY,
    barraca_id     UUID         NOT NULL REFERENCES barraca (id),
    categoria_id   UUID         NOT NULL,
    nome           VARCHAR(60)  NOT NULL,
    descricao      VARCHAR(140),
    preco_centavos BIGINT       NOT NULL CHECK (preco_centavos BETWEEN 1 AND 1000000),
    -- ativo = aparece no cardápio; esgotado = aparece, mas não dá para pedir
    ativo          BOOLEAN      NOT NULL DEFAULT TRUE,
    esgotado       BOOLEAN      NOT NULL DEFAULT FALSE,
    ordem          INTEGER      NOT NULL,
    criado_em      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    atualizado_em  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Um produto nunca pode apontar para a categoria de outra barraca, nem por bug.
    CONSTRAINT fk_produto_categoria FOREIGN KEY (categoria_id, barraca_id) REFERENCES categoria (id, barraca_id)
);

CREATE INDEX idx_produto_barraca ON produto (barraca_id, categoria_id, ordem);
