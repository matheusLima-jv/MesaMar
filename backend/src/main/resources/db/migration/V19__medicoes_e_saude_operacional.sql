ALTER TABLE medicao_sinal ALTER COLUMN carregamento_ms DROP NOT NULL;
ALTER TABLE medicao_sinal ALTER COLUMN ping_ms DROP NOT NULL;
ALTER TABLE medicao_sinal ADD COLUMN metrica VARCHAR(30) NOT NULL DEFAULT 'TESTE_SINAL';
ALTER TABLE medicao_sinal ADD COLUMN sucesso BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE medicao_sinal ADD COLUMN amostras TEXT NOT NULL DEFAULT '[]';
CREATE TABLE saude_operacional (
    tarefa VARCHAR(40) PRIMARY KEY,
    ultimo_sucesso TIMESTAMPTZ,
    ultima_falha TIMESTAMPTZ,
    sucessos BIGINT NOT NULL DEFAULT 0,
    falhas BIGINT NOT NULL DEFAULT 0
);
