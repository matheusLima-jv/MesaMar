const vm = require('node:vm')
const fs = require('node:fs')
const path = require('node:path')
module.exports = function carregar(app, { quota = false, fetch = async () => new Response('rede') } = {}) {
  const lojas = new Map(), ouvintes = new Map()
  const url = valor => new URL(typeof valor === 'string' ? valor : valor.url, 'https://mesamar.com.br').href
  const caches = {
    async open(nome) {
      if (!lojas.has(nome)) lojas.set(nome, new Map())
      const m = lojas.get(nome)
      return { async addAll(chaves) { if (quota) throw new Error('QuotaExceededError'); for (const c of chaves) m.set(url(c), new Response('shell')) },
        async put(c, r) { if (quota) throw new Error('QuotaExceededError'); m.set(url(c), r.clone()) },
        async match(c) { return m.get(url(c))?.clone() }, async keys() { return [...m.keys()].map(c => new Request(c)) }, async delete(c) { return m.delete(url(c)) } }
    }, async keys() { return [...lojas.keys()] }, async delete(n) { return lojas.delete(n) }
  }
  let claimed = false, skipped = false
  const self = { location: { origin: 'https://mesamar.com.br' }, addEventListener: (n, f) => ouvintes.set(n, f),
    async skipWaiting() { skipped = true }, clients: { async claim() { claimed = true }, async matchAll() { return [] }, async openWindow() {} } }
  vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../../', app, 'public/sw.js'), 'utf8'), { self, caches, fetch, URL, Response, Headers, Date })
  async function evento(tipo, dados = {}) {
    const promessas = []; let resposta
    ouvintes.get(tipo)({ ...dados, waitUntil: p => promessas.push(p), respondWith: p => { resposta = p } })
    const r = resposta === undefined ? undefined : await resposta
    await Promise.all(promessas)
    return r
  }
  return { caches, lojas, evento, claimed: () => claimed, skipped: () => skipped }
}
