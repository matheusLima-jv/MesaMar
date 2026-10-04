-- Última sincronização de cada aparelho da equipe, para o dono ver quem ficou sem enviar (Fase 6.3, ADR 0021).
-- Uma linha por aparelho e barraca; o mesmo aparelho em outra barraca é outra linha.
CREATE TABLE sincronizacao_aparelho (
    barraca_id      UUID        NOT NULL REFERENCES barraca (id) ON DELETE CASCADE,
    aparelho_id     UUID        NOT NULL,
    usuario_id      UUID        REFERENCES usuario (id) ON DELETE CASCADE,
    atendente_id    UUID        REFERENCES atendente (id) ON DELETE CASCADE,
    tipo_aparelho   VARCHAR(20) NOT NULL CHECK (tipo_aparelho IN ('ANDROID', 'IPHONE', 'COMPUTADOR', 'OUTRO')),
    pendentes       INTEGER     NOT NULL CHECK (pendentes BETWEEN 0 AND 200),
    conflitos       INTEGER     NOT NULL CHECK (conflitos BETWEEN 0 AND 200),
    sincronizado_em TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (barraca_id, aparelho_id),
    CHECK ((usuario_id IS NULL) <> (atendente_id IS NULL))
);
CREATE INDEX sincronizacao_aparelho_em ON sincronizacao_aparelho (sincronizado_em);
