const { test } = require('node:test')
const assert = require('node:assert/strict')
const carregar = require('./carregar.cjs')
const { h, nos, apiReal, ligarErro } = require('./dom-falso.cjs')
const tick = () => new Promise(r => setImmediate(r))

const sessao = { logado: true, csrf: 'x', usuario: { id: '10000000-0000-4000-8000-000000000001', nome: 'A', papel: 'DONO' }, barraca: { nome: 'Teste', codigo: 'teste', praia: 'ENSEADA' } }
const ID = '40000000-0000-4000-8000-000000000001'

/**
 * A aba Clientes até o formulário "Novo cliente". `pendente` é um check-in que ficou sem resposta (o formulário abre
 * travado com ele). `respostas` diz o que acontece em cada envio.
 */
async function formulario ({ pendente = null, respostas = [], falharAoGuardar = false } = {}) {
  const api = apiReal()
  const envios = []
  const vistas = []
  let guardado = pendente
  const novoCliente = (dados) => {
    envios.push({ ...dados })
    const r = respostas[envios.length - 1] ?? { espera: true }
    if (r.recusa !== undefined) return Promise.reject(new api.ApiError(r.recusa, 'validacao', 'Confira os campos.', undefined, r.campos ?? {}))
    return new Promise(() => {}) // fica esperando: o teste não segue para a tela do QR
  }
  const root = {}
  const { modulo: view } = carregar('views/clientes', {}, {
    '../fila-offline': { tokenLocal: () => 'abcdefghijklmnopqrstuv' },
    '../api': { ...api, clientes: async () => [], guardaSois: async () => [], novoCliente, novoId: () => ID, recusaVista: async (id) => { vistas.push(id) } },
    '../a11y': { ativarModal: () => () => {}, ligarErro, tecladoDoGrupo () {} },
    '../consentimento': { CONSENTIMENTO_TEXTO: 'O cliente concorda.', CONSENTIMENTO_VERSAO: 'v1' },
    '../dom': { abrirTela: () => () => true, h, mount (r, ...filhos) { r.children = filhos }, registrarSobreposicao: () => () => {} },
    '../pendencias': {
      pendencias: {
        ler: () => (guardado === null ? null : structuredClone(guardado)),
        guardar (_tipo, _escopo, id, dados) { if (falharAoGuardar) throw new Error('Dados incompatíveis.'); guardado = { id, dados } },
        apagar () { guardado = null }
      }
    },
    '../qr': { qrSvg: () => h('svg') },
    './lancar': { lancar () {} }
  })
  view.abaClientes(root, sessao)
  await tick(); await tick()
  nos({ children: root.children }).find(n => n.textContent === '+ Novo cliente').onclick()
  await tick()
  const todos = () => nos({ children: root.children })
  const achar = (fn) => todos().find(fn)
  return {
    envios,
    vistas,
    guardado: () => guardado,
    nome: achar(n => n.id === 'nome'),
    whatsapp: achar(n => n.id === 'whatsapp'),
    consentimento: achar(n => n.id === 'consentimento'),
    botao: achar(n => n.tag === 'button' && n.type === 'submit'),
    erroDe: (campo) => achar(n => n.id === `erro-${campo}`),
    erroGeral: () => achar(n => n.tag === 'p' && n.class === 'alert' && n.role === 'alert'),
    enviar: async () => { achar(n => n.tag === 'form').onsubmit({ preventDefault () {} }); for (let i = 0; i < 4; i++) await tick() }
  }
}

test('WhatsApp longo demais mostra o erro no campo e não trava o botão', async () => {
  const f = await formulario()
  assert.equal(f.whatsapp.maxlength, 25)
  f.nome.value = 'Ana'
  f.consentimento.checked = true
  f.whatsapp.value = '+55 (13) 99999-8888 ramal 12'
  await f.enviar()
  assert.equal(f.botao.disabled, false)
  assert.equal(f.erroDe('whatsapp').hidden, false)
  assert.equal(f.envios.length, 0)
})

test('falha ao guardar o check-in no aparelho devolve o botão', async () => {
  const f = await formulario({ falharAoGuardar: true })
  f.nome.value = 'Ana'
  f.consentimento.checked = true
  await f.enviar()
  assert.equal(f.botao.disabled, false)
  assert.equal(f.botao.textContent, 'Gerar QR')
  assert.equal(f.erroGeral().hidden, false)
  assert.equal(f.envios.length, 0)
})

test('recusa do servidor tira a ação da fila e o envio corrigido leva os dados novos', async () => {
  const f = await formulario({ respostas: [{ recusa: 400, campos: { whatsapp: 'WhatsApp inválido.' } }] })
  f.nome.value = 'Ana'
  f.consentimento.checked = true
  f.whatsapp.value = '123'
  await f.enviar()
  assert.deepEqual(f.vistas, [ID], 'a recusa foi vista nesta tela')
  assert.equal(f.erroDe('whatsapp').textContent, 'WhatsApp inválido.')
  assert.equal(f.botao.disabled, false)
  assert.equal(f.guardado(), null)
  f.whatsapp.value = '13 99999-8888'
  await f.enviar()
  assert.equal(f.envios[1].whatsapp, '13 99999-8888')
  assert.equal(f.envios[1].id, ID)
})

test('check-in pendente recusado pelo servidor volta a poder ser corrigido', async () => {
  const pendente = { id: ID, dados: { id: ID, token: 'abcdefghijklmnopqrstuv', nome: 'Ana', guardaSolId: null, whatsapp: '123', consentimento: true, consentimentoVersao: 'v1' } }
  const f = await formulario({ pendente, respostas: [{ recusa: 400, campos: { whatsapp: 'WhatsApp inválido.' } }] })
  assert.equal(f.whatsapp.readOnly, true, 'abre travado com o envio sem resposta')
  await f.enviar()
  assert.equal(f.whatsapp.readOnly, false, 'nada foi criado: os dados voltam a ser editáveis')
  assert.equal(f.consentimento.disabled, false)
  f.whatsapp.value = '13 99999-8888'
  await f.enviar()
  assert.equal(f.envios[1].whatsapp, '13 99999-8888')
})

test('sem resposta (sessão vencida) o check-in continua guardado com o mesmo id', async () => {
  const f = await formulario({ respostas: [{ recusa: 401 }] })
  f.nome.value = 'Ana'
  f.consentimento.checked = true
  await f.enviar()
  assert.deepEqual(f.vistas, [])
  assert.equal(f.guardado()?.id, ID, '401 não diz se um envio anterior chegou: o id fica para repetir')
})
