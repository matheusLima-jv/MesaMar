-- Fase 1 · checkpoint 1.2: lista de espera do piloto e medições de sinal nas praias.

CREATE TABLE lead (
    id               UUID         PRIMARY KEY,
    nome             VARCHAR(80)  NOT NULL,
    nome_barraca     VARCHAR(80)  NOT NULL,
    praia            VARCHAR(20)  NOT NULL,
    whatsapp         VARCHAR(16)  NOT NULL, -- E.164, ex.: +5513999998888
    consentimento_em TIMESTAMPTZ  NOT NULL,
    origem           VARCHAR(40)  NOT NULL,
    criado_em        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    atualizado_em    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_lead_whatsapp UNIQUE (whatsapp)
);

CREATE INDEX idx_lead_criado_em ON lead (criado_em DESC);

-- Medições anônimas do /teste-sinal: nenhum dado pessoal nem IP é guardado.
CREATE TABLE medicao_sinal (
    id              UUID        PRIMARY KEY,
    praia           VARCHAR(20) NOT NULL,
    carregamento_ms INTEGER     NOT NULL CHECK (carregamento_ms >= 0),
    ping_ms         INTEGER     NOT NULL CHECK (ping_ms >= 0),
    tipo_conexao    VARCHAR(20),
    criada_em       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_medicao_sinal_praia ON medicao_sinal (praia, criada_em DESC);
