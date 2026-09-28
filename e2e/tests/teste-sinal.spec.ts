import { expect, test } from '@playwright/test'
import { failOnConsoleErrors } from './helpers'

test('mede o sinal e salva a medição', async ({ page }) => {
  const assertNoErrors = failOnConsoleErrors(page)
  await page.goto('/teste-sinal')

  await page.getByLabel('Em qual praia você está?').selectOption({ label: 'Pitangueiras' })
  await page.getByRole('button', { name: 'Medir sinal agora' }).click()

  await expect(page.locator('#veredito')).toContainText('Medição salva', { timeout: 15_000 })
  await expect(page.locator('#r-ping')).toHaveText(/\d+ ms|\d+,\d s/)
  assertNoErrors()
})

test('pede a praia antes de medir', async ({ page }) => {
  await page.goto('/teste-sinal')
  await page.getByRole('button', { name: 'Medir sinal agora' }).click()
  await expect(page.locator('#veredito')).toHaveText('Escolha a praia antes de medir.')
})
