-- Fase 2 · checkpoint 2.2: atendentes (login por PIN) e guarda-sóis.

CREATE TABLE atendente (
    id                UUID         PRIMARY KEY,
    barraca_id        UUID         NOT NULL REFERENCES barraca (id),
    nome              VARCHAR(40)  NOT NULL,
    pin_hash          VARCHAR(100) NOT NULL,
    ativo             BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Bloqueio por tentativas: 5 PINs errados seguidos bloqueiam por 15 minutos
    tentativas_falhas INTEGER      NOT NULL DEFAULT 0 CHECK (tentativas_falhas >= 0),
    bloqueado_ate     TIMESTAMPTZ,
    criado_em         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    atualizado_em     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Na tela de login o atendente escolhe o próprio nome: não pode haver dois iguais na mesma barraca.
CREATE UNIQUE INDEX uk_atendente_nome ON atendente (barraca_id, lower(nome));

CREATE TABLE guarda_sol (
    id         UUID        PRIMARY KEY,
    barraca_id UUID        NOT NULL REFERENCES barraca (id),
    numero     INTEGER     NOT NULL CHECK (numero BETWEEN 1 AND 999),
    ativo      BOOLEAN     NOT NULL DEFAULT TRUE,
    criado_em  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_guarda_sol_numero UNIQUE (barraca_id, numero)
);
