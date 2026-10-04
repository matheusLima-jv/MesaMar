-- O mesmo aparelho mantém as filas de cada operador separadas, inclusive depois da troca de sessão.
ALTER TABLE sincronizacao_aparelho
    ADD COLUMN autor_id UUID GENERATED ALWAYS AS (coalesce(usuario_id, atendente_id)) STORED,
    ADD COLUMN pendente_desde TIMESTAMPTZ;
UPDATE sincronizacao_aparelho SET pendente_desde = sincronizado_em WHERE pendentes > 0;
ALTER TABLE sincronizacao_aparelho DROP CONSTRAINT sincronizacao_aparelho_pkey;
ALTER TABLE sincronizacao_aparelho
    ADD PRIMARY KEY (barraca_id, aparelho_id, autor_id),
    ADD CONSTRAINT fk_sincronia_usuario_barraca FOREIGN KEY (usuario_id, barraca_id) REFERENCES usuario (id, barraca_id),
    ADD CONSTRAINT fk_sincronia_atendente_barraca FOREIGN KEY (atendente_id, barraca_id) REFERENCES atendente (id, barraca_id),
    ADD CONSTRAINT ck_sincronia_idade CHECK ((pendentes = 0) = (pendente_desde IS NULL));
