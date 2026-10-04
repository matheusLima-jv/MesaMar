const { test } = require('node:test')
const assert = require('node:assert/strict')
const carregar = require('./carregar.cjs')
const tick = () => new Promise(r => setImmediate(r))
test('reenvio mantém instante original e informe usa a ação mais antiga mesmo após 503', async () => {
  const autor = '10000000-0000-4000-8000-000000000001'
  const sessao = { logado: true, csrf: 'teste', usuario: { id: autor, nome: 'A', papel: 'DONO' }, barraca: { nome: 'Teste', codigo: 'teste', praia: 'ENSEADA' } }
  const antiga = Date.now() - 3_600_000
  const op = { id: '10000000-0000-4000-8000-000000000002', tipo: 'checkin', estado: 'pendente', criadaEm: antiga, dados: { id: '10000000-0000-4000-8000-000000000002', nome: 'Ana', consentimento: true } }
  const ops = [op]
  const corpos = []
  const { modulo: api } = carregar('api', {
    window: { setTimeout: () => 1, clearTimeout() {}, addEventListener() {}, removeEventListener() {} },
    fetch: async (url, init) => {
      if (url.endsWith('/sessao')) return Response.json(sessao)
      corpos.push({ url, body: JSON.parse(init.body) })
      if (url.endsWith('/clientes')) return Response.json({ erro: 'temporario', mensagem: 'Indisponível' }, { status: 503 })
      return new Response(null, { status: 204 })
    }
  }, {
    './fila-offline': {
      mudancasOffline: new EventTarget(), esperaSincronia: () => 1000,
      listarOperacoes: async () => ops,
      drenar: async (_ctx, enviar) => { for (const o of ops) { try { await enviar(o) } catch { break } } }
    }
  })
  api.prepararOffline(sessao)
  await tick(); await tick()
  await api.sincronizar()
  const envios = corpos.filter(c => c.url.endsWith('/clientes'))
  assert.equal(envios.length, 2)
  assert.equal(envios[0].body.criadoNoAparelhoEm, new Date(antiga).toISOString())
  // O envio leva o relógio do aparelho naquele momento: o servidor calcula a idade sem depender do acerto do relógio
  envios.forEach(e => assert.ok(Date.parse(e.body.enviadoNoAparelhoEm) - antiga >= 3_600_000))
  const semEnvio = ({ enviadoNoAparelhoEm: _enviado, ...resto }) => resto
  assert.deepEqual(semEnvio(envios[1].body), semEnvio(envios[0].body))
  const informe = corpos.find(c => c.url.endsWith('/sincronizacao')).body
  assert.equal(informe.pendentes, 1)
  assert.equal(informe.pendenteDesde, new Date(antiga).toISOString())
  op.criadaEm = antiga + 1000 // a fila avançou: nova idade muda o informe mesmo antes de 1 minuto
  await api.sincronizar()
  const informes = corpos.filter(c => c.url.endsWith('/sincronizacao'))
  assert.equal(informes.length, 2)
  assert.equal(informes[1].body.pendenteDesde, new Date(antiga + 1000).toISOString())
  api.suspenderOffline()
})
