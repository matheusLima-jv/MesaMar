// DOM mínimo para testar as telas sem navegador: o bastante para `h`, campos, botões e a árvore montada.
const carregar = require('./carregar.cjs')

function h (tag, attrs = {}, ...filhos) {
  const el = {
    tag,
    children: filhos.filter(f => f !== null && f !== undefined),
    listeners: {},
    hidden: false,
    disabled: false,
    readOnly: false,
    checked: false,
    value: '',
    addEventListener (evento, fn) { this.listeners[evento] = fn },
    replaceChildren (...n) { this.children = n },
    append (...n) { this.children.push(...n) },
    focus () {},
    getAttribute (nome) { return this[nome] ?? null },
    querySelector () { return null }
  }
  for (const [chave, valor] of Object.entries(attrs)) {
    if (chave === 'readonly') el.readOnly = valor === true
    else if (valor !== undefined) el[chave] = valor
  }
  el.textContent = el.children.filter(f => typeof f === 'string').join('')
  return el
}

/** Todos os elementos abaixo de `raiz` (inclusive). */
function nos (raiz) {
  return !raiz || typeof raiz !== 'object' ? [] : [raiz, ...(raiz.children ?? []).flatMap(nos)]
}

/** O `api.ts` de verdade (ApiError, recusaDefinitiva, reais…), sem rede nem fila. */
function apiReal () {
  return carregar('api', { fetch: async () => new Response(null, { status: 204 }) }, {
    './fila-offline': { mudancasOffline: new EventTarget(), esperaSincronia: () => 1000, listarOperacoes: async () => [], drenar: async () => {} }
  }).modulo
}

/** `ligarErro` como o de verdade faz para o teste: o texto aparece no elemento de erro. */
function ligarErro (_campo, erro, mensagem) {
  erro.textContent = mensagem ?? ''
  erro.hidden = mensagem === null
}

module.exports = { h, nos, apiReal, ligarErro }
