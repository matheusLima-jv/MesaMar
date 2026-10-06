const { test } = require('node:test')
const assert = require('node:assert/strict')
const carregar = require('./carregar.cjs')

test('pagamento incerto só é esquecido quando o servidor avaliou e recusou', () => {
  const { modulo: api } = carregar('api', { fetch: async () => new Response(null, { status: 204 }) }, {
    './fila-offline': { mudancasOffline: new EventTarget(), esperaSincronia: () => 1000, listarOperacoes: async () => [], drenar: async () => {} }
  })
  const recusas = [[400, 'validacao'], [404, 'comanda'], [409, 'conflito'], [403, 'proibido']]
  recusas.forEach(([status, codigo]) => assert.equal(new api.ApiError(status, codigo, 'x').recusaDefinitiva, true, `${status} ${codigo}`))
  // Sem avaliação do servidor: o envio anterior pode ter sido gravado, então o mesmo id precisa ser repetido
  const incertos = [[0, 'rede'], [401, 'sessao'], [403, 'csrf'], [408, 'tempo'], [429, 'limite'], [503, 'temporario'], [400, 'descartada']]
  incertos.forEach(([status, codigo]) => assert.equal(new api.ApiError(status, codigo, 'x').recusaDefinitiva, false, `${status} ${codigo}`))
})
