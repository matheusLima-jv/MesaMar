-- Fase 2 · checkpoint 2.1: contas dos donos, barraca, convites e recuperação de senha.

CREATE TABLE barraca (
    id                           UUID         PRIMARY KEY,
    nome                         VARCHAR(80)  NOT NULL,
    slug                         VARCHAR(60)  NOT NULL,
    praia                        VARCHAR(20)  NOT NULL,
    -- Pix: guardado já normalizado (só dígitos para CPF/CNPJ, +55… para telefone, minúsculo para e-mail/aleatória)
    tipo_chave_pix               VARCHAR(12),
    chave_pix                    VARCHAR(77),
    nome_recebedor_pix           VARCHAR(25),
    cidade_pix                   VARCHAR(15),
    -- Regras de operação
    limite_guarda_sois           INTEGER      NOT NULL DEFAULT 30 CHECK (limite_guarda_sois BETWEEN 1 AND 200),
    modo_pagamento               VARCHAR(12)  NOT NULL DEFAULT 'FINAL',
    limite_saldo_aberto_centavos BIGINT       CHECK (limite_saldo_aberto_centavos IS NULL OR limite_saldo_aberto_centavos > 0),
    tempo_alerta_pedido_min      INTEGER      NOT NULL DEFAULT 10 CHECK (tempo_alerta_pedido_min BETWEEN 1 AND 120),
    pedidos_pausados             BOOLEAN      NOT NULL DEFAULT FALSE,
    ativa                        BOOLEAN      NOT NULL DEFAULT TRUE,
    criada_em                    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    atualizada_em                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_barraca_slug UNIQUE (slug),
    CONSTRAINT ck_barraca_pix_completo CHECK (
        (chave_pix IS NULL AND tipo_chave_pix IS NULL)
        OR (chave_pix IS NOT NULL AND tipo_chave_pix IS NOT NULL AND nome_recebedor_pix IS NOT NULL AND cidade_pix IS NOT NULL)
    )
);

CREATE TABLE usuario (
    id              UUID         PRIMARY KEY,
    barraca_id      UUID         REFERENCES barraca (id),
    nome            VARCHAR(80)  NOT NULL,
    email           VARCHAR(254) NOT NULL,
    senha_hash      VARCHAR(100) NOT NULL,
    papel           VARCHAR(10)  NOT NULL DEFAULT 'OWNER',
    criado_em       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ultimo_login_em TIMESTAMPTZ
);

-- E-mail único sem diferenciar maiúsculas (Ana@x.com = ana@x.com)
CREATE UNIQUE INDEX uk_usuario_email ON usuario (lower(email));
CREATE INDEX idx_usuario_barraca ON usuario (barraca_id);

-- Convites para o piloto: só quem recebe um link consegue criar conta.
-- Guardamos apenas o hash do token (quem vê o banco não consegue usar o convite).
CREATE TABLE convite (
    id         UUID        PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL,
    lead_id    UUID        REFERENCES lead (id),
    observacao VARCHAR(80),
    criado_em  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expira_em  TIMESTAMPTZ NOT NULL,
    usado_em   TIMESTAMPTZ,
    usado_por  UUID        REFERENCES usuario (id),
    CONSTRAINT uk_convite_token UNIQUE (token_hash)
);

CREATE INDEX idx_convite_lead ON convite (lead_id);

CREATE TABLE reset_senha (
    id         UUID        PRIMARY KEY,
    usuario_id UUID        NOT NULL REFERENCES usuario (id),
    token_hash VARCHAR(64) NOT NULL,
    criado_em  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expira_em  TIMESTAMPTZ NOT NULL,
    usado_em   TIMESTAMPTZ,
    CONSTRAINT uk_reset_senha_token UNIQUE (token_hash)
);

CREATE INDEX idx_reset_senha_usuario ON reset_senha (usuario_id);
