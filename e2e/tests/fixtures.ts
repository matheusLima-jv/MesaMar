import { test as base, expect, type BrowserContext, type Page } from '@playwright/test'
type Esperado = { contexto?: BrowserContext, caminho: RegExp, status: number }
export const test = base.extend<{ vigiarConsole: void }>({
  vigiarConsole: [async ({ browser, context }, use, info) => {
    const esperados: Esperado[] = []
    const transportes: Array<{ caminho: RegExp, contexto: BrowserContext }> = []
    const erros: string[] = []
    const contextos = new Set<BrowserContext>()
    const respostasEsperadas = new Set<string>()
    const offline = new Set<BrowserContext>()
    const falhasOffline: Array<{ contexto: BrowserContext, url: string, em: number }> = []
    const consolePendente: Array<{ contexto: BrowserContext, url: string, texto: string, em: number }> = []
    let contador = 1
    const observar = async (ctx: BrowserContext): Promise<void> => {
      if (contextos.has(ctx)) return
      contextos.add(ctx)
      const hash = [...info.testId].reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 0)
      const ip = `198.18.${hash % 250}.${contador++}`
      await ctx.setExtraHTTPHeaders({ 'X-Forwarded-For': ip })
      const setOffline = ctx.setOffline.bind(ctx)
      ctx.setOffline = async ativo => { if (ativo) offline.add(ctx); await setOffline(ativo); if (!ativo) offline.delete(ctx) }
      function pagina(p: Page): void {
        p.on('response', r => {
          if (r.status() < 400) return
          const e = esperados.find(x => (!x.contexto || x.contexto === ctx) && x.status === r.status() && x.caminho.test(r.url()))
          if (e) respostasEsperadas.add(r.url())
          else erros.push(`HTTP inesperado ${r.status()} ${new URL(r.url()).pathname}`)
        })
        p.on('requestfailed', r => { if (offline.has(ctx) || transportes.some(t => t.contexto === ctx && t.caminho.test(r.url()))) falhasOffline.push({ contexto: ctx, url: r.url(), em: Date.now() }) })
        p.on('pageerror', e => { erros.push(e.message) })
        p.on('console', msg => {
          if (msg.type() !== 'error') return
          const url = msg.location().url
          const texto = msg.text()
          const recurso = /^Failed to load resource:/.test(texto)
          if (recurso && (respostasEsperadas.has(url) || esperados.some(e => (!e.contexto || e.contexto === ctx) && e.caminho.test(url) && texto.includes(String(e.status))))) return
          consolePendente.push({ contexto: ctx, url, texto, em: Date.now() })
        })
      }
      ctx.pages().forEach(pagina); ctx.on('page', pagina)
    }
    const original = browser.newContext.bind(browser)
    browser.newContext = async opcoes => { const ctx = await original(opcoes); await observar(ctx); return ctx }
    await observar(context)
    // A lista é disponibilizada antes de qualquer navegação do cenário.
    falhasRede.set(info.testId, (caminho, contexto) => transportes.push({ caminho, contexto }))
    permissoes.set(info.testId, (caminho, status, contexto) => esperados.push({ caminho, status, contexto }))
    try { await use() } finally {
      browser.newContext = original
      for (const ctx of contextos) { if (ctx !== context) await ctx.close() }
      permissoes.delete(info.testId)
      falhasRede.delete(info.testId)
      // WebKit também descreve uma falha offline como access control/internal error.
      // Console e requestfailed podem chegar fora de ordem: correlacionar somente ao final,
      // pela mesma conexão, URL e janela de um segundo. CORS online continua sendo erro.
      for (const c of consolePendente) {
        const mensagemRede = /^Failed to load resource:/.test(c.texto)
          ? /net::ERR_|Load failed|network|connection|incomplete|terminated|partial file|WebKit encountered an internal error/i.test(c.texto)
          : /due to access control checks\.$/.test(c.texto)
        const esperado = mensagemRede && falhasOffline.some(f => f.contexto === c.contexto && Math.abs(f.em - c.em) <= 1000 && (f.url === c.url || c.texto.includes(f.url) || c.texto.includes(new URL(f.url).host + new URL(f.url).pathname)))
        if (!esperado) erros.push(c.texto)
      }
      expect(erros, 'console e HTTP de todos os contextos, até a última ação').toEqual([])
    }
  }, { auto: true }],
})
const permissoes = new Map<string, (caminho: RegExp, status: number, contexto?: BrowserContext) => void>()
export function permitirHttp(caminho: RegExp, status: number, contexto?: BrowserContext): void {
  const permissao = permissoes.get(test.info().testId)
  if (!permissao) throw new Error('Use o test de fixtures para registrar erros HTTP esperados')
  permissao(caminho, status, contexto)
}
const falhasRede = new Map<string, (caminho: RegExp, contexto: BrowserContext) => void>()
export function permitirFalhaRede(caminho: RegExp, contexto: BrowserContext): void {
  const permissao = falhasRede.get(test.info().testId)
  if (!permissao) throw new Error('Monitor não inicializado')
  permissao(caminho, contexto)
}
export { expect }
