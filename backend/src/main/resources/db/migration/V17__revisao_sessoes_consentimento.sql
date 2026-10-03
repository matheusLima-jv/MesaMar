-- Revisão de segurança e dados pessoais (lote 3).
-- Atendente: quando as sessões abertas foram revogadas (desativar, ou "sair de todos os aparelhos"). Uma sessão iniciada
-- antes desta hora deixa de valer, e reativar o atendente depois não a faz voltar a valer.
ALTER TABLE atendente ADD COLUMN sessoes_revogadas_em TIMESTAMPTZ;

-- Cliente: a versão do texto de consentimento que o atendente leu para o cliente no check-in (NULL = anterior a este registro).
ALTER TABLE cliente ADD COLUMN consentimento_versao VARCHAR(30);

-- Comanda: o link anterior, depois de um "QR novo". Não abre mais a comanda: só serve para a página antiga descobrir que
-- um pedido que ela estava reenviando já tinha chegado (ver ClienteController.pedir).
ALTER TABLE comanda ADD COLUMN token_anterior VARCHAR(43);
CREATE UNIQUE INDEX uk_comanda_token_anterior ON comanda (token_anterior) WHERE token_anterior IS NOT NULL;
