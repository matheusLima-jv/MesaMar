const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const ts = require('typescript')

function carregar (arquivo, api = {}) {
  const timers = new Map()
  let proximo = 0
  const contexto = {
    exports: {}, require: () => api,
    window: { setTimeout: (fn) => { timers.set(++proximo, fn); return proximo }, clearTimeout: (id) => timers.delete(id) },
    location: { hash: '#pedidos' }, document: { visibilityState: 'visible' }
  }
  const fonte = fs.readFileSync(`${__dirname}/../src/${arquivo}.ts`, 'utf8')
  vm.runInNewContext(ts.transpileModule(fonte, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, contexto)
  return { ...contexto.exports, contexto, timers, avancar: () => { const tarefas = [...timers.values()]; timers.clear(); tarefas.forEach(fn => fn()) } }
}

test('reiniciar e parar encerra SSE e todos os timers', () => {
  let conexoes = 0
  const m = carregar('sincronia', { ouvirComanda: () => { conexoes++; return () => { conexoes-- } } })
  const s = new m.SincroniaCliente(async () => {}, () => {}, () => {})
  s.iniciar('a', () => {})
  s.agendar()
  s.acompanhar()
  assert.equal(conexoes, 1)
  assert.equal(m.timers.size, 2)
  s.iniciar('b', () => {})
  assert.equal(conexoes, 1)
  s.parar()
  assert.equal(conexoes, 0)
  assert.equal(m.timers.size, 0)
})

test('avisos durante uma busca produzem apenas uma repeticao', async () => {
  let chamadas = 0
  let concluir
  const m = carregar('sincronia', { ouvirComanda: () => () => {} })
  const s = new m.SincroniaCliente(() => { chamadas++; return new Promise(resolve => { concluir = resolve }) }, () => {}, () => {})
  s.iniciar('a', () => {})
  s.agendar(); m.avancar()
  s.agendar(); m.avancar()
  s.agendar(); m.avancar()
  assert.equal(chamadas, 1)
  concluir(); await new Promise(resolve => setImmediate(resolve))
  m.avancar()
  assert.equal(chamadas, 2)
  s.parar()
  concluir(); await new Promise(resolve => setImmediate(resolve))
  assert.equal(m.timers.size, 0)
})

test('resposta de conta que chega depois de parar nao altera estado nem desenha', async () => {
  let concluir
  let desenhos = 0
  const m = carregar('conta-store', { conta: () => new Promise(resolve => { concluir = resolve }) })
  const ctx = { dados: { saldoCentavos: 10 } }
  const store = m.criarContaStore(() => 'a', () => ctx, () => { desenhos++ }, async () => {})
  const busca = store.carregar()
  store.parar()
  concluir({ saldoCentavos: 999, status: 'ABERTA', metodo: null })
  await busca
  assert.equal(ctx.dados.saldoCentavos, 10)
  assert.equal(store.estado.conta, null)
  assert.equal(desenhos, 0)
})

test('estado pertence a instancia de aplicacao', () => {
  const { EstadoCliente } = carregar('estado')
  const a = new EstadoCliente()
  const b = new EstadoCliente()
  a.token = 'a'
  a.avisados.add('pedido-a')
  assert.equal(b.token, '')
  assert.equal(b.avisados.size, 0)
})
