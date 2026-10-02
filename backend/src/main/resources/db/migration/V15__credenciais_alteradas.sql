-- Fase 7 · checkpoint 7.4 (revisão final): trocar a senha do dono derruba as sessões abertas antes da troca.
-- O login guarda a hora em que aconteceu; se a senha mudou depois disso, a sessão deixa de valer.
ALTER TABLE usuario ADD COLUMN credenciais_em TIMESTAMPTZ;
