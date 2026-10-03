const { test } = require('node:test')
const assert = require('node:assert/strict')
const carregar = require('./carregar.cjs')
const id = '10000000-0000-4000-8000-000000000001'
const produtoId = '10000000-0000-4000-8000-000000000002'
function carrinho(bruto) {
  let salvo = JSON.stringify(bruto)
  const { modulo } = carregar('carrinho', {}, { './dom': { lembrar: { get: () => salvo, set: (_, v) => { salvo = v } } } })
  return { ...modulo, salvo: () => JSON.parse(salvo) }
}
test('restaura legado validado e salva envelope de versão', () => {
  const m = carrinho([{ produtoId, quantidade: 2, observacao: '' }]); const c = new m.Carrinho('t')
  assert.equal(c.unidades(), 2); c.definir(produtoId, 3)
  assert.equal(m.salvo().versao, 1); assert.equal(m.salvo().dados[0].quantidade, 3)
})
test('recusa versões desconhecidas, duplicatas e limites inválidos', () => {
  const linha = { produtoId, quantidade: 2, observacao: '' }
  for (const v of [{ versao: 2, dados: [linha] }, [linha, linha], [{ ...linha, quantidade: 21 }], [{ ...linha, quantidade: 1.5 }], [{ ...linha, produtoId: 'arbitrario' }], [{ ...linha, observacao: 'a'.repeat(81) }], Array(31).fill(linha)]) {
    assert.equal(new (carrinho(v).Carrinho)('t').unidades(), 0)
  }
})
test('não introduz NaN/fração e preserva cópia imutável do pedido', () => {
  const m = carrinho([]), c = new m.Carrinho('t'); c.definir(produtoId, NaN); c.definir(produtoId, 0.5); assert.equal(c.unidades(), 0)
  const p = { id, itens: [{ produtoId, quantidade: 2, observacao: '', precoCentavos: 100 }] }
  m.pendente.guardar('t', p); p.itens[0].quantidade = 20
  assert.equal(m.pendente.ler('t').itens[0].quantidade, 2)
  const lida = m.pendente.ler('t'); lida.itens[0].quantidade = 1; assert.equal(m.pendente.ler('t').itens[0].quantidade, 2)
})
test('contrato verifica corpo e classifica interrupção depois dos cabeçalhos como resultado desconhecido', async () => {
  const corpo = []; let tentativa = 0
  const pedido = { id, numero: 1, status: 'RECEBIDO', hora: '12:00', itens: [{ id: produtoId, produtoId, nome: 'Água', precoCentavos: 100, quantidade: 1, observacao: null, subtotalCentavos: 100, cancelado: false, motivoCancelamento: null }], totalCentavos: 100, motivoCancelamento: null }
  const { modulo: api } = carregar('api', { fetch: async (_, op) => { corpo.push(op.body); tentativa++; return tentativa === 1 ? { ok: true, json: async () => { throw new TypeError('corpo interrompido') } } : new Response(JSON.stringify(pedido), { status: 201 }) } })
  const p = { id, itens: [{ produtoId, quantidade: 1, observacao: '', precoCentavos: 100 }] }
  await assert.rejects(api.enviarPedido('t', p), e => e.status === 0 && e.temporario)
  assert.equal((await api.enviarPedido('t', p)).id, id); assert.equal(corpo[0], corpo[1])
})
test('JSON 2xx estruturalmente inválido não vira confirmação', async () => {
  const { modulo: api } = carregar('api', { fetch: async () => new Response('{}', { status: 200 }) })
  await assert.rejects(api.conta('t'), e => e.codigo === 'contrato')
})
