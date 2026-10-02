-- Fase 7 · checkpoint 7.3: o botão "reportar problema" dos dois apps.
-- Guarda o que a pessoa escreveu e o contexto que ajuda a entender (tela, hora, estado da conexão), sem dado pessoal além
-- do necessário: nada de nome do cliente; a comanda e a barraca já identificam de onde veio.
CREATE TABLE problema_reportado (
    id            UUID         PRIMARY KEY,
    barraca_id    UUID         NOT NULL REFERENCES barraca (id),
    origem        VARCHAR(10)  NOT NULL CHECK (origem IN ('QUIOSQUE', 'CLIENTE')),
    -- Quem reportou no quiosque (dono ou atendente); no cliente, a comanda
    usuario_id    UUID,
    atendente_id  UUID,
    comanda_id    UUID,
    tela          VARCHAR(40)  NOT NULL,
    mensagem      VARCHAR(500) NOT NULL,
    -- "online", "offline" e, se o navegador disser, o tipo de rede (ex.: "online · 3g")
    conexao       VARCHAR(60)  NOT NULL,
    agente        VARCHAR(200),
    criado_em     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_problema_criado ON problema_reportado (criado_em DESC);
CREATE INDEX idx_problema_barraca ON problema_reportado (barraca_id, criado_em DESC);
