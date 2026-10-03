const { test } = require('node:test')
const assert = require('node:assert/strict')
const { IDBFactory } = require('fake-indexeddb')
const carregar = require('./carregar.cjs')
const id = n => `10000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const novo = n => ({ id: id(n), token: require('node:crypto').randomBytes(16).toString('base64url'), nome: 'Ana', guardaSolId: null, whatsapp: '', consentimento: true })
function fila() { return carregar('fila-offline', { indexedDB: new IDBFactory() }).modulo }
test('sequência é atômica entre inclusões concorrentes e identifica duplicatas', async () => {
  const f = fila()
  await Promise.all([1,2,3].map(n => f.preservar('b:a', 'checkin', id(n), novo(n))))
  await f.preservar('b:a', 'checkin', id(1), novo(1))
  const ops = await f.listarOperacoes('b:a'); assert.equal(ops.length, 3); assert.equal(new Set(ops.map(o => o.ordem)).size, 3)
  assert.equal((await f.listarOperacoes('b:outro')).length, 0)
})
test('resultado desconhecido mantém id e impede ultrapassar a primeira ação', async () => {
  const f = fila(); await f.preservar('b:a', 'checkin', id(1), novo(1)); await f.preservar('b:a', 'checkin', id(2), novo(2))
  const enviadas = []
  await f.drenar('b:a', async o => { enviadas.push(o.id); throw { status: 0, temporario: true } }, () => true)
  assert.deepEqual(enviadas, [id(1)]); assert.equal((await f.listarOperacoes('b:a')).length, 2)
  await f.drenar('b:a', async o => { enviadas.push(o.id) }, () => true)
  assert.deepEqual(enviadas, [id(1), id(1), id(2)]); assert.equal((await f.listarOperacoes('b:a')).length, 0)
})
test('409 bloqueia ordem, 429/503 não descartam; troca de contexto preserva', async () => {
  for (const status of [409, 429, 503, 401]) {
    const f = fila(); await f.preservar('b:a', 'checkin', id(1), novo(1))
    await f.drenar('b:a', async () => { throw { status, temporario: status===429 || status===503, message: 'Confira' } }, () => true)
    const [op] = await f.listarOperacoes('b:a'); assert.equal(op.estado, status===409 ? 'conflito' : 'pendente')
  }
  const f=fila(); await f.preservar('b:a','checkin',id(1),novo(1)); let vigente=true
  await f.drenar('b:a',async () => { vigente=false }, () => vigente)
  assert.equal((await f.listarOperacoes('b:a')).length,1)
})
test('recusa formato, prazo futuro, itens duplicados e versão desconhecida', () => {
  const f=fila()
  const op={ versao:1, chave:'b:a:'+id(1), contexto:'b:a', id:id(1), tipo:'checkin', criadaEm:Date.now(), ordem:1, estado:'pendente', dados:novo(1) }
  for(const v of [{...op,versao:2},{...op,criadaEm:Date.now()+3600000},{...op,dados:{...novo(1),consentimento:false}},{...op,dados:{...novo(1),id:id(2)}}]) assert.throws(()=>f.validarOperacao(v))
})
test('token local tem 128 bits e formato aceito pelo servidor', () => {
  const f=fila(), tokens=new Set(Array.from({length:100},()=>f.tokenLocal()))
  assert.equal(tokens.size,100); for(const t of tokens) {assert.match(t,/^[A-Za-z0-9_-]{22}$/);assert.ok('AQgw'.includes(t.at(-1)))}
})
