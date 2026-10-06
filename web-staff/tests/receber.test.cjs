const { test } = require('node:test')
const assert = require('node:assert/strict')
const carregar = require('./carregar.cjs')
const { h, nos, apiReal, ligarErro } = require('./dom-falso.cjs')
const tick = () => new Promise(r => setImmediate(r))

const conta = { comandaId: '30000000-0000-4000-8000-000000000001', cliente: 'Ana', guardaSol: 3, status: 'AGUARDANDO_PAGAMENTO', pedidoCentavos: 5000, pagoCentavos: 0, saldoCentavos: 5000, metodo: 'PIX', trocoParaCentavos: null, avisoEm: null, abertaEm: '2026-10-06T12:00:00Z', pedidosAguardando: 0, saldoPerdoadoCentavos: null }

/**
 * O diálogo "Receber" com um servidor de mentira: `respostas` diz o que acontece em cada envio. O servidor registra o
 * pagamento pelo id, como o de verdade: um reenvio do mesmo id devolve o que já foi gravado.
 */
function dialogo (respostas) {
  const api = apiReal()
  const gravados = new Map()
  const envios = []
  const avisos = []
  let tela
  const receberPagamento = async (_comandaId, envio) => {
    envios.push({ ...envio })
    const resposta = respostas[envios.length - 1]
    if (resposta.recusa !== undefined) throw new api.ApiError(resposta.recusa, 'recusado', 'Recusado pelo servidor.')
    if (!gravados.has(envio.id)) gravados.set(envio.id, { ...envio })
    if (resposta.semResposta === true) throw new api.ApiError(0, 'rede', 'Sem conexão com o servidor.')
    const p = gravados.get(envio.id)
    return { conta: { ...conta, saldoCentavos: conta.saldoCentavos - p.valorCentavos }, pagamento: { ...p, trocoCentavos: 0 }, fechada: false }
  }
  const memoria = new Map()
  const { modulo: view } = carregar('views/receber', {}, {
    '../api': { ...api, receberPagamento },
    '../a11y': { ativarModal: (t) => { tela = t; return () => {} }, ligarErro },
    '../dom': { h, lembrar: { get: k => memoria.get(k) ?? null, set: (k, v) => memoria.set(k, v) }, registrarSobreposicao: () => () => {} },
    '../toast': { avisar: (m) => avisos.push(m) }
  })
  view.dialogoReceber(conta, () => {})
  const todos = nos(tela)
  const achar = (fn) => todos.find(fn)
  return {
    envios,
    avisos,
    valor: achar(n => n.id === 'rec-valor'),
    radios: todos.filter(n => n.type === 'radio'),
    erro: achar(n => n.id === 'rec-erro'),
    confirmar: achar(n => n.tag === 'button' && n.type === 'submit'),
    tocar: () => achar(n => n.tag === 'form').onsubmit({ preventDefault () {} }),
    enviar: async () => { achar(n => n.tag === 'form').onsubmit({ preventDefault () {} }); await tick(); await tick() }
  }
}

test('resposta perdida trava o pagamento e a confirmação mostra o valor que o servidor registrou', async () => {
  const d = dialogo([{ semResposta: true }, {}])
  d.valor.value = '20'
  await d.enviar()
  assert.equal(d.erro.hidden, false)
  assert.equal(d.valor.readOnly, true, 'o valor enviado fica travado até a resposta')
  assert.deepEqual(d.radios.filter(r => !r.disabled).map(r => r.value), ['PIX'])

  // Mesmo que o campo mude (antes ele ficava editável), o reenvio repete exatamente o primeiro pagamento
  d.valor.value = '30'
  await d.enviar()
  assert.equal(d.envios.length, 2)
  assert.deepEqual(d.envios[1], d.envios[0])
  assert.equal(d.envios[1].valorCentavos, 2000)
  assert.match(d.avisos[0], /Recebido R\$ 20,00 de Ana/)
})

test('recusa avaliada pelo servidor destrava para corrigir, e a mensagem vem do que foi registrado', async () => {
  const d = dialogo([{ recusa: 409 }, {}])
  d.valor.value = '45'
  await d.enviar()
  assert.equal(d.valor.readOnly, false, 'nada foi gravado: dá para corrigir')
  assert.equal(d.radios.every(r => !r.disabled), true)
  d.valor.value = '10'
  await d.enviar()
  assert.equal(d.envios[1].valorCentavos, 1000)
  assert.match(d.avisos[0], /Recebido R\$ 10,00 de Ana/)
})

test('segundo toque enquanto o primeiro ainda viaja não sai de novo', async () => {
  const d = dialogo([{}, {}])
  d.valor.value = '20'
  d.tocar()
  assert.equal(d.confirmar.disabled, true)
  d.tocar()
  await tick(); await tick()
  assert.equal(d.envios.length, 1)
  assert.equal(d.avisos.length, 1)
})
