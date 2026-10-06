const { test } = require('node:test')
const assert = require('node:assert/strict')
const { IDBFactory } = require('fake-indexeddb')
const carregar = require('./carregar.cjs')
const tick = () => new Promise(r => setImmediate(r))

const autor = '10000000-0000-4000-8000-000000000001'
const sessao = { logado: true, csrf: 'teste', usuario: { id: autor, nome: 'A', papel: 'DONO' }, barraca: { nome: 'Teste', codigo: 'teste', praia: 'ENSEADA' } }
const ctx = `teste:${autor}`
const id = n => `20000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const checkin = (n, whatsapp = '') => ({ id: id(n), token: require('node:crypto').randomBytes(16).toString('base64url'), nome: 'Ana', guardaSolId: null, whatsapp, consentimento: true })
const comanda = body => ({ id: body.id, numeroChegada: 1, nome: body.nome, guardaSol: null, whatsapp: null, whatsappExibicao: null, link: `https://mesamar.com.br/c/${body.token}`, hora: '10:00', status: 'ABERTA' })

/** O app com a fila de verdade (IndexedDB em memória) e um servidor que responde por `responder(body)`. */
async function app (responder) {
  const indexedDB = new IDBFactory()
  const corpos = []
  const fila = carregar('fila-offline', { indexedDB }).modulo
  const { modulo: api } = carregar('api', {
    indexedDB,
    window: { setTimeout: () => 1, clearTimeout () {}, addEventListener () {}, removeEventListener () {} },
    fetch: async (url, init) => {
      if (url.endsWith('/sessao')) return Response.json(sessao)
      if (url.endsWith('/sincronizacao')) return new Response(null, { status: 204 })
      const body = JSON.parse(init.body)
      corpos.push(body)
      return responder(body)
    }
  }, { './fila-offline': fila })
  api.prepararOffline(sessao)
  await tick(); await tick()
  return { api, fila, corpos }
}

test('recusa mostrada na tela sai da fila e o check-in corrigido leva os dados novos', async () => {
  const { api, corpos } = await app(body => body.whatsapp === '123'
    ? Response.json({ erro: 'validacao', mensagem: 'Confira os campos.', campos: { whatsapp: 'WhatsApp inválido.' } }, { status: 400 })
    : Response.json(comanda(body), { status: 201 }))
  const errado = checkin(1, '123')
  const e = await api.novoCliente(errado).then(() => null, e => e)
  assert.equal(e.status, 400)
  assert.equal(e.campos.whatsapp, 'WhatsApp inválido.')
  assert.equal((await api.estadoSincronia()).operacoes[0].estado, 'conflito')

  await api.recusaVista(errado.id)
  await tick(); await tick()
  assert.equal((await api.estadoSincronia()).operacoes.length, 0)

  const certo = await api.novoCliente({ ...errado, whatsapp: '13 99999-8888' })
  assert.equal(certo.id, errado.id)
  assert.equal(corpos.at(-1).whatsapp, '13 99999-8888')
  api.suspenderOffline()
})

test('recusa que ninguém viu continua no indicador e não deixa o mesmo id voltar com dados antigos escondidos', async () => {
  const { api, corpos } = await app(() => Response.json({ erro: 'validacao', mensagem: 'Confira os campos.', campos: { whatsapp: 'WhatsApp inválido.' } }, { status: 400 }))
  const errado = checkin(2, '123')
  await assert.rejects(api.novoCliente(errado))
  // Sem a tela tirar a recusa da fila, a correção esbarraria no conflito antigo (era o que acontecia antes)
  const e = await api.novoCliente({ ...errado, whatsapp: '13 99999-8888' }).then(() => null, e => e)
  assert.equal(e.codigo, 'conflito-offline')
  assert.equal(corpos.length, 1)
  assert.equal((await api.estadoSincronia()).operacoes[0].estado, 'conflito')
  api.suspenderOffline()
})

test('recusa de outra ação da fila não vira a resposta deste check-in', async () => {
  const anterior = checkin(3)
  const { api, fila } = await app(body => body.id === anterior.id
    ? Response.json({ erro: 'dia-encerrado', mensagem: 'Ação de outro dia.' }, { status: 409 })
    : Response.json(comanda(body), { status: 201 }))
  // Uma ação guardada antes (sem sinal) está na frente da fila e vai ser recusada agora
  await fila.preservar(ctx, 'checkin', anterior.id, anterior)
  const novo = checkin(4)
  // Antes, a recusa da anterior chegava a esta tela como se fosse deste check-in (e quem tentasse de novo duplicava)
  const local = await api.novoCliente(novo)
  assert.equal(local.id, novo.id)
  assert.equal(local.numeroChegada, 0, 'continua guardado no aparelho, atrás do conflito')
  const ops = (await api.estadoSincronia()).operacoes
  assert.deepEqual(Array.from(ops, o => [o.id, o.estado]), [[anterior.id, 'conflito'], [novo.id, 'pendente']])
  api.suspenderOffline()
})
