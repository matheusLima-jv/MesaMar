const { test } = require('node:test')
const assert = require('node:assert/strict')
const vm = require('node:vm')
const fs = require('node:fs')
const path = require('node:path')
test('medir novamente faz nova leitura e registra todas as falhas; dado ausente permanece null', async () => {
  const elementos = new Map(); let clique
  const el = id => { if (!elementos.has(id)) elementos.set(id, { value: id === 'praia' ? 'ENSEADA' : '', dataset: {}, focus() {}, addEventListener(n, f) { if (id === 'medir') clique = f } }); return elementos.get(id) }
  const medidas = [], chamadas = []; let tempo = 0
  vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../../backend/src/main/resources/static/js/teste-sinal.js'), 'utf8'), {
    document: { getElementById: el }, localStorage: { setItem() {} }, performance: { getEntriesByType: () => [], now: () => tempo += 10 },
    crypto: require('node:crypto').webcrypto, AbortSignal, navigator: {}, window: { mesaMarMedicoes: { registrar(v) { medidas.push(v); return true } } },
    fetch: async url => { chamadas.push(url); if (url.includes('/ping')) throw new Error('offline'); return new Response('pagina') }
  })
  clique(); await new Promise(resolve => setImmediate(resolve))
  assert.equal(medidas.length, 1); assert.equal(medidas[0].carregamentoMs, null); assert.equal(medidas[0].pingMs, null)
  assert.equal(medidas[0].amostras.length, 5); assert(medidas[0].amostras.every(a => !a.sucesso))
  clique(); await new Promise(resolve => setImmediate(resolve))
  assert.equal(medidas.length, 2); assert.equal(medidas[1].carregamentoMs, 10)
  assert.equal(chamadas.filter(u => u.startsWith('/teste-sinal?')).length, 1)
  assert.equal(medidas[1].sucesso, false)
})
