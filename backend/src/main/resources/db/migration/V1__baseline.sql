-- Migração inicial do MesaMar.
-- Registra o marco zero do schema; as tabelas de negócio chegam a partir da Fase 1.
CREATE TABLE app_metadata (
    key        VARCHAR(64)  PRIMARY KEY,
    value      VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO app_metadata (key, value) VALUES ('schema_baseline', 'fase-0');
