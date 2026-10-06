const { test } = require('node:test')
const assert = require('node:assert/strict')
const crypto = require('node:crypto').webcrypto
const carregar = require('./carregar.cjs')
const { apiReal } = require('./dom-falso.cjs')
const tick = () => new Promise(resolve => setImmediate(resolve))

async function tela(falharAoGuardar = false, recusa = null) {
  function h(tag, attrs, ...children) {
    return { tag, ...attrs, children, listeners: {}, textContent: children.filter(c => typeof c === 'string').join(''), addEventListener(event, fn) { this.listeners[event] = fn } }
  }
  const dom = { h, mount(root, ...children) { root.children = children }, abrirTela: () => () => true }
  const produtos = Array.from({ length: 31 }, (_, i) => ({ id: crypto.randomUUID(), nome: `Produto ${i}`, precoCentavos: 100, preco: 'R$ 1,00', esgotado: false }))
  const enviados = []
  const vistas = []
  const { ApiError } = apiReal()
  const lancarPedido = (_id, p) => {
    enviados.push(p)
    return recusa === null ? Promise.resolve({ numero: 1 }) : Promise.reject(new ApiError(recusa.status, recusa.codigo, 'Recusado.'))
  }
  const { modulo: view } = carregar('views/lancar', {}, {
    '../dom': dom,
    '../api': { ApiError, cardapio: async () => [{ nome: 'Cardapio', produtos }], novoId: () => crypto.randomUUID(), reais: n => `R$ ${n / 100}`, lancarPedido, recusaVista: async id => { vistas.push(id) }, descartada: () => false },
    '../pendencias': { pendencias: { ler: () => null, guardar() { if (falharAoGuardar) throw new Error('Armazenamento indisponível') }, apagar() {} } },
    '../fila-store': { cardapioMudou: new EventTarget() }, '../toast': { avisar() {} }
  })
  const root = {}
  view.lancar(root, { id: crypto.randomUUID(), nome: 'Ana', guardaSol: null }, () => {})
  await tick()
  function flatten(v) { return !v || typeof v !== 'object' ? [] : [v, ...(v.children ?? []).flatMap(flatten)] }
  const nodes = root.children.flatMap(flatten)
  return { enviados, vistas, root, mais: nodes.filter(n => n.tag === 'button' && n.textContent === '+'), valores: nodes.filter(n => n.tag === 'output'), erro: nodes.find(n => n.role === 'alert'), botao: nodes.find(n => n.tag === 'button' && n.textContent === 'Lançar pedido') }
}

test('31º produto mostra o limite, mantém controles e permite lançar os 30 escolhidos', async () => {
  const t = await tela()
  t.mais.forEach(n => n.onclick())
  assert.equal(t.valores[30].textContent, '0')
  assert.match(t.erro.textContent, /30 produtos/)
  assert.equal(t.botao.disabled, false)
  t.botao.listeners.click(); await tick()
  assert.equal(t.enviados.length, 1)
  assert.equal(t.enviados[0].itens.length, 30)
})

test('61ª unidade é recusada sem perder as 60 escolhidas', async () => {
  const t = await tela()
  for (const b of t.mais.slice(0,3)) for (let n = 0; n < 20; n++) b.onclick()
  t.mais[3].onclick()
  assert.equal(t.valores[3].textContent, '0')
  assert.match(t.erro.textContent, /60 unidades/)
  t.botao.listeners.click(); await tick()
  assert.equal(t.enviados[0].itens.reduce((s,i) => s+i.quantidade,0),60)
})

test('falha síncrona ao guardar devolve os controles e não envia pedido', async () => {
  const t = await tela(true)
  t.mais[0].onclick()
  assert.doesNotThrow(() => t.botao.listeners.click())
  assert.equal(t.botao.disabled, false)
  assert.equal(t.botao.textContent, 'Lançar pedido')
  assert.equal(t.enviados.length, 0)
  assert.equal(t.erro.hidden, false)
})

test('recusa mostrada na tela sai da fila antes de montar o próximo pedido', async () => {
  const t = await tela(false, { status: 409, codigo: 'comanda-fechada' })
  t.mais[0].onclick()
  t.botao.listeners.click(); await tick(); await tick()
  assert.deepEqual(t.vistas, [t.enviados[0].id])
  assert.equal(t.botao.disabled, false)
  assert.equal(t.erro.hidden, false)
})

test('sem resposta (sessão vencida) o pedido continua na fila e a tela mostra o envio pendente', async () => {
  const t = await tela(false, { status: 401, codigo: 'sessao' })
  t.mais[0].onclick()
  t.botao.listeners.click(); await tick(); await tick()
  assert.deepEqual(t.vistas, [])
  const textos = JSON.stringify(t.root.children)
  assert.match(textos, /Pedido preservado neste aparelho/)
})
