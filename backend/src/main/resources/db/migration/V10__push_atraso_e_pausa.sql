-- Fase 4 · checkpoint 4.2: Web Push, alerta de pedido atrasado e "Pausar pedidos".

-- Alvos das FKs compostas: quem está inscrito é sempre da mesma barraca da inscrição.
ALTER TABLE atendente ADD CONSTRAINT uk_atendente_barraca UNIQUE (id, barraca_id);
ALTER TABLE usuario ADD CONSTRAINT uk_usuario_barraca UNIQUE (id, barraca_id);

-- Um aparelho (navegador) inscrito para receber avisos de pedido. O `endpoint` é a URL secreta do serviço de push
-- (Google, Mozilla, Apple…) e `p256dh` + `auth` são as chaves para cifrar a mensagem (RFC 8291).
CREATE TABLE push_subscription (
    id            UUID         PRIMARY KEY,
    barraca_id    UUID         NOT NULL REFERENCES barraca (id),
    -- Quem ativou os avisos: o dono (usuário) ou um atendente, nunca os dois
    usuario_id    UUID,
    atendente_id  UUID,
    endpoint      VARCHAR(600) NOT NULL,
    p256dh        VARCHAR(100) NOT NULL,
    auth          VARCHAR(40)  NOT NULL,
    criado_em     TIMESTAMPTZ  NOT NULL,
    ultimo_uso_em TIMESTAMPTZ,
    CONSTRAINT uk_push_endpoint UNIQUE (endpoint),
    CONSTRAINT ck_push_quem CHECK ((usuario_id IS NULL) <> (atendente_id IS NULL)),
    CONSTRAINT fk_push_usuario FOREIGN KEY (usuario_id, barraca_id) REFERENCES usuario (id, barraca_id),
    CONSTRAINT fk_push_atendente FOREIGN KEY (atendente_id, barraca_id) REFERENCES atendente (id, barraca_id)
);

CREATE INDEX idx_push_barraca ON push_subscription (barraca_id);

-- O alerta de atraso sai uma vez só por pedido: quando sai, a hora fica aqui.
ALTER TABLE pedido ADD COLUMN alerta_atraso_em TIMESTAMPTZ;

-- Texto opcional que o cliente vê quando o quiosque pausa os pedidos ("Voltamos em 10 minutos").
ALTER TABLE barraca ADD COLUMN mensagem_pausa VARCHAR(80);
