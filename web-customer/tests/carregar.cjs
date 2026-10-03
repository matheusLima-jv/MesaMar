const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
module.exports = function carregar(arquivo, globais = {}, mocks = {}) {
  const cache = new Map()
  const storage = new Map()
  const localStorage = { getItem: k => storage.get(k) ?? null, setItem: (k, v) => storage.set(k, String(v)), removeItem: k => storage.delete(k) }
  const contexto = { structuredClone, EventTarget, Event, AbortController, AbortSignal, Response, Headers, Request, URL, crypto: require('node:crypto').webcrypto, btoa, localStorage, setTimeout, clearTimeout, setInterval, clearInterval, navigator: { onLine: true }, location: { origin: 'https://mesamar.com.br', pathname: '/staff/' }, window: { setTimeout, clearTimeout, setInterval, clearInterval, addEventListener() {}, removeEventListener() {} }, ...globais }
  function ler(caminho) {
    if (cache.has(caminho)) return cache.get(caminho)
    const exports = {}; cache.set(caminho, exports)
    const fonte = fs.readFileSync(caminho, 'utf8')
    const codigo = ts.transpileModule(fonte, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
    vm.runInNewContext(codigo, { ...contexto, exports, require: id => mocks[id] ?? (id.startsWith('.') ? ler(path.resolve(path.dirname(caminho), id + '.ts')) : require(id)) }, { filename: caminho })
    return exports
  }
  return { modulo: ler(path.resolve(__dirname, '../src', arquivo + '.ts')), contexto, storage }
}
