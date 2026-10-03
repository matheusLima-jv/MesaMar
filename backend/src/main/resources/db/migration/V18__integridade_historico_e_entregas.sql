-- Autores são sempre da barraca da operação, inclusive fora da API.
ALTER TABLE comanda
    ADD CONSTRAINT fk_comanda_autor_usuario FOREIGN KEY (aberta_por_usuario_id, barraca_id) REFERENCES usuario (id, barraca_id),
    ADD CONSTRAINT fk_comanda_autor_atendente FOREIGN KEY (aberta_por_atendente_id, barraca_id) REFERENCES atendente (id, barraca_id);
ALTER TABLE pedido
    ADD CONSTRAINT fk_pedido_atendido_usuario FOREIGN KEY (atendido_por_usuario_id, barraca_id) REFERENCES usuario (id, barraca_id),
    ADD CONSTRAINT fk_pedido_atendido_atendente FOREIGN KEY (atendido_por_atendente_id, barraca_id) REFERENCES atendente (id, barraca_id),
    ADD CONSTRAINT fk_pedido_criado_usuario FOREIGN KEY (criado_por_usuario_id, barraca_id) REFERENCES usuario (id, barraca_id),
    ADD CONSTRAINT fk_pedido_criado_atendente FOREIGN KEY (criado_por_atendente_id, barraca_id) REFERENCES atendente (id, barraca_id);

-- Normaliza posições legadas antes de garantir unicidade; trocas são verificadas no commit.
WITH pos AS (SELECT id, row_number() OVER (PARTITION BY barraca_id ORDER BY ordem, id) AS n FROM categoria)
UPDATE categoria c SET ordem = pos.n FROM pos WHERE c.id = pos.id;
WITH pos AS (SELECT id, row_number() OVER (PARTITION BY barraca_id, categoria_id ORDER BY ordem, id) AS n FROM produto)
UPDATE produto p SET ordem = pos.n FROM pos WHERE p.id = pos.id;
ALTER TABLE categoria ADD CONSTRAINT uk_categoria_ordem UNIQUE (barraca_id, ordem) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE produto ADD CONSTRAINT uk_produto_ordem UNIQUE (barraca_id, categoria_id, ordem) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE produto ADD COLUMN versao BIGINT NOT NULL DEFAULT 0;
ALTER TABLE categoria ADD COLUMN versao BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_pagamento_barraca_hora ON pagamento (barraca_id, confirmado_em);
CREATE INDEX idx_comanda_barraca_fechamento ON comanda (barraca_id, fechada_em) WHERE saldo_perdoado_centavos IS NOT NULL;
CREATE INDEX idx_pedido_barraca_dia ON pedido (barraca_id, dia);
CREATE INDEX idx_reset_expiracao ON reset_senha (expira_em);
CREATE INDEX idx_convite_expiracao ON convite (expira_em);

-- Outbox: conteúdo sensível de e-mail é apagado no sucesso/expiração, sem ir ao log.
CREATE TABLE entrega_pendente (
    id UUID PRIMARY KEY,
    tipo VARCHAR(20) NOT NULL,
    destino UUID,
    conteudo TEXT NOT NULL,
    criada_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    expira_em TIMESTAMPTZ NOT NULL,
    proxima_tentativa_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    reservada_ate TIMESTAMPTZ,
    reserva UUID,
    tentativas INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_entrega_pendente ON entrega_pendente (proxima_tentativa_em, criada_em);
CREATE INDEX idx_entrega_expiracao ON entrega_pendente (expira_em);

-- Histórico sem nomes, tokens, e-mails, hashes de senha, chaves Pix ou texto livre.
CREATE TABLE historico_acao (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    barraca_id UUID,
    entidade VARCHAR(30) NOT NULL,
    entidade_id UUID NOT NULL,
    acao VARCHAR(10) NOT NULL,
    usuario_id UUID,
    atendente_id UUID,
    origem VARCHAR(20) NOT NULL,
    campos_alterados JSONB,
    antes JSONB,
    depois JSONB,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_historico_barraca_hora ON historico_acao (barraca_id, criado_em);
CREATE INDEX idx_historico_retencao ON historico_acao (criado_em);
CREATE FUNCTION registrar_historico() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a JSONB; d JSONB; registro JSONB; campos TEXT[]; alterados JSONB;
BEGIN
    campos := ARRAY['status','ativo','ativa','bloqueado_ate','pedidos_pausados','modo_pagamento',
        'limite_saldo_aberto_centavos','limite_guarda_sois','tempo_alerta_pedido_min','categoria_id',
        'ordem','preco_centavos','esgotado','cancelado','metodo','valor_centavos','saldo_perdoado_centavos',
        'aberta_por_usuario_id','aberta_por_atendente_id','atendido_por_usuario_id','atendido_por_atendente_id',
        'criado_por_usuario_id','criado_por_atendente_id','credenciais_em','sessoes_revogadas_em','tentativas_falhas'];
    IF TG_OP <> 'INSERT' THEN
        SELECT jsonb_object_agg(key, value) INTO a FROM jsonb_each(to_jsonb(OLD)) WHERE key = ANY(campos);
    END IF;
    IF TG_OP <> 'DELETE' THEN
        SELECT jsonb_object_agg(key, value) INTO d FROM jsonb_each(to_jsonb(NEW)) WHERE key = ANY(campos);
    END IF;
    IF TG_OP = 'UPDATE' THEN
        SELECT jsonb_agg(n.key) INTO alterados FROM jsonb_each(to_jsonb(NEW)) n
        WHERE n.value IS DISTINCT FROM to_jsonb(OLD)->n.key
          AND n.key NOT IN ('atualizada_em','atualizado_em','versao','ultimo_login_em','ultimo_uso_em');
        IF alterados IS NULL THEN RETURN NEW; END IF;
    END IF;
    registro := CASE WHEN TG_OP = 'DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    INSERT INTO historico_acao (barraca_id, entidade, entidade_id, acao, usuario_id, atendente_id, origem, campos_alterados, antes, depois)
    VALUES (CASE WHEN TG_TABLE_NAME = 'barraca' THEN (registro->>'id')::uuid ELSE (registro->>'barraca_id')::uuid END, TG_TABLE_NAME, (registro->>'id')::uuid, TG_OP,
        coalesce(nullif(current_setting('mesamar.usuario', true), '')::uuid,
            (registro->>'atendido_por_usuario_id')::uuid, (registro->>'aberta_por_usuario_id')::uuid,
            (registro->>'criado_por_usuario_id')::uuid, (registro->>'confirmado_por_usuario_id')::uuid),
        coalesce(nullif(current_setting('mesamar.atendente', true), '')::uuid,
            (registro->>'atendido_por_atendente_id')::uuid, (registro->>'aberta_por_atendente_id')::uuid,
            (registro->>'criado_por_atendente_id')::uuid, (registro->>'confirmado_por_atendente_id')::uuid),
        coalesce(nullif(current_setting('mesamar.origem', true), ''), 'SISTEMA'), alterados, a, d);
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;
DO $$ DECLARE tabela TEXT;
BEGIN
    FOREACH tabela IN ARRAY ARRAY['comanda','pedido','item_pedido','pagamento','barraca','atendente','categoria','produto','usuario'] LOOP
        EXECUTE format('CREATE TRIGGER historico AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION registrar_historico()', tabela);
    END LOOP;
END $$;
