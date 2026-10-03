import { expect, type Page } from '@playwright/test'

/** Número de celular válido e único por execução (DDD 13). */
export function randomWhatsapp (): string {
  const n = Math.floor(10_000_000 + Math.random() * 89_999_999)
  return `(13) 9${String(n).slice(0, 4)}-${String(n).slice(4, 8)}`
}

/** Falha o teste se a página logar erro no console (inclui bloqueios de CSP). */
export function failOnConsoleErrors (_page: Page): () => void {
  // Compatibilidade com cenários antigos: a fixture automática verifica todos os contextos no teardown.
  return () => undefined
}

/** Garante que nada vaza para os lados no celular (sem rolagem horizontal). */
export async function expectNoHorizontalScroll (page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  const fora = overflow > 1 ? await page.evaluate(() => [...document.querySelectorAll('body *')].map(e => ({ tag: e.tagName, classe: e.className, direita: e.getBoundingClientRect().right, largura: e.getBoundingClientRect().width })).filter(e => e.direita > window.innerWidth + 1).slice(0, 12)) : []
  expect(overflow, 'a página não pode rolar para o lado: ' + JSON.stringify(fora)).toBeLessThanOrEqual(1)
}
