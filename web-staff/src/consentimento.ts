// O texto de consentimento do check-in e a sua versão. O app manda a versão junto com o check-in, e o servidor a grava:
// para qualquer cadastro dá para saber qual texto foi apresentado. Mudou o sentido do texto? Crie uma versão nova aqui,
// em CheckinService.CONSENTIMENTO_VERSAO_ATUAL e em docs/consentimento.md (um teste confere que as três batem).
// Nunca edite o texto de uma versão que já foi usada.
export const CONSENTIMENTO_VERSAO = '2026-10-v1'

export const CONSENTIMENTO_TEXTO = 'O cliente concorda em informar o nome (e o WhatsApp) só para identificar a comanda e receber o link.'
