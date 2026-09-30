import { expect, type Page } from '@playwright/test'

/** Número de celular válido e único por execução (DDD 13). */
export function randomWhatsapp (): string {
  const n = Math.floor(10_000_000 + Math.random() * 89_999_999)
  return `(13) 9${String(n).slice(0, 4)}-${String(n).slice(4, 8)}`
}

/** Falha o teste se a página logar erro no console (inclui bloqueios de CSP). */
export function failOnConsoleErrors (page: Page): () => void {
  const errors: string[] = []
  page.on('console', (msg) => { if (msg.type() === 'error') errors.push(msg.text()) })
  page.on('pageerror', (err) => errors.push(err.message))
  return () => expect(errors, 'erros no console do navegador').toEqual([])
}

/** Garante que nada vaza para os lados no celular (sem rolagem horizontal). */
export async function expectNoHorizontalScroll (page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(overflow, 'a página não pode rolar para o lado').toBeLessThanOrEqual(1)
}
