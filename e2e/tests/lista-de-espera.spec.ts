import { expect, test } from '@playwright/test'
import { failOnConsoleErrors, randomWhatsapp } from './helpers'

test('envio vazio mostra uma mensagem em cada campo', async ({ page }) => {
  await page.goto('/#lista-de-espera')
  await page.getByRole('button', { name: 'Entrar na lista do piloto' }).click()

  await expect(page.getByText('Conta pra gente o seu nome.')).toBeVisible()
  await expect(page.getByText('Qual é o nome da sua barraca ou quiosque?')).toBeVisible()
  await expect(page.getByText('Escolha a praia onde você trabalha.')).toBeVisible()
  await expect(page.getByText('Deixe um WhatsApp para a gente falar com você.')).toBeVisible()
  await expect(page.getByText('é preciso concordar com o contato pelo WhatsApp')).toBeVisible()
  // depois do erro, a página volta já no formulário
  await expect(page.getByRole('heading', { name: 'Entrar na lista do piloto' })).toBeInViewport()
})

test('barraqueiro entra na lista de espera pelo celular', async ({ page }) => {
  const assertNoErrors = failOnConsoleErrors(page)
  await page.goto('/#lista-de-espera')

  await page.getByLabel('Seu nome').fill('Teste E2E')
  await page.getByLabel('Nome da barraca ou quiosque').fill('Barraca E2E')
  await page.getByLabel('Praia').selectOption({ label: 'Enseada' })
  const whatsapp = page.getByLabel('WhatsApp com DDD')
  await expect(whatsapp).toHaveAttribute('inputmode', 'tel') // abre o teclado numérico
  await whatsapp.fill(randomWhatsapp())
  await page.getByLabel(/Aceito receber contato do MesaMar/).check()
  await page.getByRole('button', { name: 'Entrar na lista do piloto' }).click()

  await expect(page).toHaveURL(/\/lista-de-espera\/obrigado(#conteudo)?$/)
  await expect(page.getByRole('heading', { name: 'Pronto, você está na lista.' })).toBeVisible()
  assertNoErrors()
})
