const { test } = require('node:test')
const assert = require('node:assert/strict')
const carregar = require('./carregar.cjs')
const tick = () => new Promise(resolve => setTimeout(resolve, 10))
function preparar() {
  class Elemento extends EventTarget {
    contains() { return false }
    replaceChildren(...filhos) { this.filhos = filhos }
  }
  const document = new EventTarget()
  const { modulo: a } = carregar('a11y', { HTMLElement: Elemento, document, window: { setTimeout, scrollY: 0, scrollTo() {} } })
  const root = new Elemento()
  const controle = new AbortController()
  a.acompanharInteracao(root, controle.signal)
  const emitir = (alvo, tipo, pointerId = 1) => alvo.dispatchEvent(Object.assign(new Event(tipo), { pointerId }))
  return { a, root, document, controle, emitir }
}
test('atualização entre início e fim do toque mantém o botão até o click e reconstrói com valor novo', async () => {
  const { a, root, document, controle, emitir } = preparar()
  let carrinho = 0
  root.replaceChildren('botão original')
  emitir(root, 'pointerdown')
  a.mountPreservando(root, () => a.mountPreservando(root, () => {}, `carrinho:${carrinho}`), 'botão novo')
  assert.deepEqual(root.filhos, ['botão original'])
  emitir(document, 'pointerup')
  carrinho++ // click executado antes do redesenho agendado
  await tick()
  assert.deepEqual(root.filhos, ['carrinho:1'])
  controle.abort()
})
test('cancelamento do toque libera atualização e encerramento da página descarta callback', async () => {
  const { a, root, document, controle, emitir } = preparar()
  let chamadas = 0
  emitir(root, 'pointerdown')
  a.mountPreservando(root, () => chamadas++)
  emitir(document, 'pointercancel'); await tick()
  assert.equal(chamadas, 1)
  emitir(root, 'pointerdown')
  a.mountPreservando(root, () => chamadas++)
  emitir(document, 'pointerup'); controle.abort(); await tick()
  assert.equal(chamadas, 1)
})
