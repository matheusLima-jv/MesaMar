import { expect, test } from '@playwright/test'
import { randomWhatsapp } from './helpers'

const password = process.env.E2E_ADMIN_PASSWORD ?? ''

test.skip(!password, 'defina E2E_ADMIN_PASSWORD para testar o painel')

test('dono do projeto vê o lead no painel e baixa o CSV', async ({ page }) => {
  // cria um lead com nome único
  const nome = `Lead E2E ${Date.now()}`
  await page.goto('/#lista-de-espera')
  await page.getByLabel('Seu nome').fill(nome)
  await page.getByLabel('Nome da barraca ou quiosque').fill('Quiosque do Painel')
  await page.getByLabel('Praia').selectOption({ label: 'Tombo' })
  await page.getByLabel('WhatsApp com DDD').fill(randomWhatsapp())
  await page.getByLabel(/Aceito receber contato do MesaMar/).check()
  await page.getByRole('button', { name: 'Entrar na lista do piloto' }).click()
  await expect(page).toHaveURL(/obrigado$/)

  // login
  await page.goto('/admin')
  await expect(page).toHaveURL(/\/admin\/login$/)
  await page.getByLabel('Usuário').fill('admin')
  await page.getByLabel('Senha').fill(password)
  await page.getByRole('button', { name: 'Entrar no painel' }).click()

  await expect(page).toHaveURL(/\/admin$/)
  await expect(page.getByRole('cell', { name: nome })).toBeVisible()

  // CSV
  const download = page.waitForEvent('download')
  await page.getByRole('link', { name: 'Baixar CSV' }).first().click()
  expect((await download).suggestedFilename()).toMatch(/^mesamar-leads-\d{4}-\d{2}-\d{2}\.csv$/)

  // sair
  await page.getByRole('button', { name: 'Sair' }).click()
  await expect(page.getByText('Você saiu do painel.')).toBeVisible()
})

test('senha errada não entra', async ({ page }) => {
  await page.goto('/admin/login')
  await page.getByLabel('Usuário').fill('admin')
  await page.getByLabel('Senha').fill('senha-errada')
  await page.getByRole('button', { name: 'Entrar no painel' }).click()
  await expect(page.getByText('Usuário ou senha não conferem.')).toBeVisible()
})
