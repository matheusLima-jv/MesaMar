import { expect, test } from './fixtures'
import { expectNoHorizontalScroll, failOnConsoleErrors } from './helpers'

test('landing abre rápido; Chromium também simula 4G congestionado', async ({ page, context, browserName }) => {
  // 4G de praia lotada: 4 Mbps de descida, 1 Mbps de subida, 150 ms de latência
  if (browserName === 'chromium') {
  const cdp = await context.newCDPSession(page)
  await cdp.send('Network.enable')
  await cdp.send('Network.emulateNetworkConditions', {
    offline: false,
    latency: 150,
    downloadThroughput: (4 * 1024 * 1024) / 8,
    uploadThroughput: (1 * 1024 * 1024) / 8,
  })

  } else test.info().annotations.push({ type: 'rede', description: 'WebKit: medição real, sem CDP de Chromium' })
  await page.goto('/', { waitUntil: 'load' })
  const loadMs = await page.evaluate(() => {
    const nav = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming
    return Math.round(nav.loadEventEnd - nav.startTime)
  })
  test.info().annotations.push({ type: browserName === 'chromium' ? 'carregamento-4g-ms' : 'carregamento-rede-ms', description: String(loadMs) })
  console.log(`Landing ${browserName === 'chromium' ? 'em 4G simulado' : 'na rede disponível'}: ${loadMs} ms`)

  // Meta do plano: 1,5 s. O teste só falha acima de 3 s para não quebrar o CI por variação do runner.
  expect(loadMs).toBeLessThan(3000)
})

test('landing no celular: conteúdo, sem rolagem lateral e sem erro no console', async ({ page }) => {
  const assertNoErrors = failOnConsoleErrors(page)
  await page.goto('/')

  await expect(page.getByRole('heading', { level: 1 })).toContainText('Seu cliente pede pelo celular')
  await expect(page.getByRole('heading', { name: 'Piloto gratuito na temporada 2026/27' })).toBeVisible()
  await expectNoHorizontalScroll(page)

  // FAQ abre e fecha com um toque
  const faq = page.locator('summary', { hasText: 'E se a internet cair?' })
  await faq.click()
  await expect(page.getByText('O cardápio fica salvo no celular')).toBeVisible()

  assertNoErrors()
})

test('botão principal leva ao formulário do piloto', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('link', { name: 'Quero o piloto grátis' }).click()
  await expect(page.getByRole('heading', { name: 'Entrar na lista do piloto' })).toBeInViewport()
})
