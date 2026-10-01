-- Fase 3 · checkpoint 3.1: check-in do cliente (cliente + comanda com o token do QR).

-- Alvo das FKs compostas da comanda: guarda-sol e comanda sempre da mesma barraca.
ALTER TABLE guarda_sol ADD CONSTRAINT uk_guarda_sol_barraca UNIQUE (id, barraca_id);

CREATE TABLE cliente (
    id               UUID        PRIMARY KEY,
    barraca_id       UUID        NOT NULL REFERENCES barraca (id),
    nome             VARCHAR(40) NOT NULL,
    whatsapp_e164    VARCHAR(16),
    -- LGPD: o cliente concordou no check-in (finalidade: identificar a comanda e enviar o link)
    consentimento_em TIMESTAMPTZ NOT NULL,
    criado_em        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Preenchido pelo job de anonimização (nome → "Cliente", WhatsApp apagado) depois de 90 dias
    anonimizado_em   TIMESTAMPTZ,
    CONSTRAINT uk_cliente_barraca UNIQUE (id, barraca_id)
);

CREATE TABLE comanda (
    id                      UUID        PRIMARY KEY,
    barraca_id              UUID        NOT NULL REFERENCES barraca (id),
    cliente_id              UUID        NOT NULL,
    guarda_sol_id           UUID,
    -- Dia de operação no fuso da barraca (America/Sao_Paulo): o número de chegada recomeça a cada dia
    dia                     DATE        NOT NULL,
    numero_chegada          INTEGER     NOT NULL CHECK (numero_chegada > 0),
    -- Credencial do cliente: vai no QR. 128 bits aleatórios em Base64 URL-safe (22 caracteres).
    token_acesso            VARCHAR(43) NOT NULL,
    status                  VARCHAR(24) NOT NULL DEFAULT 'ABERTA'
        CHECK (status IN ('ABERTA', 'AGUARDANDO_PAGAMENTO', 'FECHADA', 'CANCELADA', 'BLOQUEADA')),
    -- Quem fez o check-in: o dono (usuário) ou um atendente, nunca os dois
    aberta_por_usuario_id   UUID        REFERENCES usuario (id),
    aberta_por_atendente_id UUID        REFERENCES atendente (id),
    aberta_em               TIMESTAMPTZ NOT NULL,
    fechada_em              TIMESTAMPTZ,
    CONSTRAINT ck_comanda_aberta_por CHECK ((aberta_por_usuario_id IS NULL) <> (aberta_por_atendente_id IS NULL)),
    CONSTRAINT uk_comanda_token UNIQUE (token_acesso),
    CONSTRAINT uk_comanda_chegada UNIQUE (barraca_id, dia, numero_chegada),
    CONSTRAINT uk_comanda_barraca UNIQUE (id, barraca_id),
    CONSTRAINT fk_comanda_cliente FOREIGN KEY (cliente_id, barraca_id) REFERENCES cliente (id, barraca_id),
    CONSTRAINT fk_comanda_guarda_sol FOREIGN KEY (guarda_sol_id, barraca_id) REFERENCES guarda_sol (id, barraca_id)
);

-- Lista de clientes ativos do app (por ordem de chegada)
CREATE INDEX idx_comanda_ativas ON comanda (barraca_id, aberta_em) WHERE status NOT IN ('FECHADA', 'CANCELADA');

-- Contadores sequenciais por barraca e dia (chegada agora; número do pedido no 3.2).
-- Incremento atômico com INSERT … ON CONFLICT DO UPDATE … RETURNING.
CREATE TABLE contador_diario (
    barraca_id UUID        NOT NULL REFERENCES barraca (id),
    dia        DATE        NOT NULL,
    tipo       VARCHAR(12) NOT NULL CHECK (tipo IN ('CHEGADA', 'PEDIDO')),
    ultimo     INTEGER     NOT NULL,
    PRIMARY KEY (barraca_id, dia, tipo)
);
