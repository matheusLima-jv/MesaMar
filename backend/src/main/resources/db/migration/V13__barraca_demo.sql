-- Fase 7 · checkpoint 7.2: a barraca de demonstração, para treinar a equipe sem mexer nos dados de ninguém.
-- Marcada no banco (e não só pelo nome) para o painel, o app e a página do cliente avisarem que nada ali é de verdade.
ALTER TABLE barraca ADD COLUMN demo BOOLEAN NOT NULL DEFAULT FALSE;

-- Só pode existir uma barraca de demonstração
CREATE UNIQUE INDEX uk_barraca_demo ON barraca (demo) WHERE demo;
