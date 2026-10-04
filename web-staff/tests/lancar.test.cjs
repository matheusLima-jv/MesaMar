const { test } = require('node:test')
const assert = require('node:assert/strict')
const crypto = require('node:crypto').webcrypto
const carregar = require('./carregar.cjs')
const tick = () => new Promise(resolve => setImmediate(resolve))

async function tela(falharAoGuardar = false) {
  function h(tag, attrs, ...children) {
    return { tag, ...attrs, children, listeners: {}, textContent: children.filter(c => typeof c === 'string').join(''), addEventListener(event, fn) { this.listeners[event] = fn } }
  }
  const dom = { h, mount(root, ...children) { root.children = children }, abrirTela: () => () => true }
  const produtos = Array.from({ length: 31 }, (_, i) => ({ id: crypto.randomUUID(), nome: `Produto ${i}`, precoCentavos: 100, preco: 'R$ 1,00', esgotado: false }))
  const enviados = []
  class ApiError extends Error { constructor(status, codigo, texto) { super(texto); this.status = status; this.codigo = codigo } }
  const { modulo: view } = carregar('views/lancar', {}, {
    '../dom': dom,
    '../api': { ApiError, cardapio: async () => [{ nome: 'Cardapio', produtos }], novoId: () => crypto.randomUUID(), reais: n => `R$ ${n / 100}`, lancarPedido: (_id, p) => { enviados.push(p); return Promise.resolve({ numero: 1 }) } },
    '../pendencias': { pendencias: { ler: () => null, guardar() { if (falharAoGuardar) throw new Error('Armazenamento indisponível') }, apagar() {} } },
    '../fila-store': { cardapioMudou: new EventTarget() }, '../toast': { avisar() {} }
  })
  const root = {}
  view.lancar(root, { id: crypto.randomUUID(), nome: 'Ana', guardaSol: null }, () => {})
  await tick()
  function flatten(v) { return !v || typeof v !== 'object' ? [] : [v, ...(v.children ?? []).flatMap(flatten)] }
  const nodes = root.children.flatMap(flatten)
  return { enviados, mais: nodes.filter(n => n.tag === 'button' && n.textContent === '+'), valores: nodes.filter(n => n.tag === 'output'), erro: nodes.find(n => n.role === 'alert'), botao: nodes.find(n => n.tag === 'button' && n.textContent === 'Lançar pedido') }
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
