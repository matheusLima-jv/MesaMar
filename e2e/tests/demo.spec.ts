import { expect, test } from './fixtures'
import { expectNoHorizontalScroll, failOnConsoleErrors } from './helpers'

const password = process.env.E2E_ADMIN_PASSWORD ?? ''


test('o projeto cria a barraca de demonstração e a equipe treina nela, com aviso de treino em todo lugar', async ({ page, browser }) => {
  const novoAparelho = async () => await (await browser.newContext(test.info().project.use)).newPage()
  const checkAdmin = failOnConsoleErrors(page)

  // 1) O admin cria (ou reinicia) a barraca e anota as credenciais, que aparecem uma vez
  await page.goto('/admin/login')
  await page.getByLabel('Usuário').fill('admin')
  await page.getByLabel('Senha').fill(password)
  await page.getByRole('button', { name: 'Entrar no painel' }).click()
  await page.getByRole('button', { name: /(Criar|Reiniciar) a barraca de demonstração/ }).click()
  await expect(page.getByText('Barraca de demonstração pronta')).toBeVisible()
  const valor = async (rotulo: string): Promise<string> =>
    ((await page.locator('.invite-created .kv div', { hasText: rotulo }).locator('dd').textContent()) ?? '').trim()
  const email = await valor('E-mail do dono')
  const senha = await valor('Senha do dono')
  const codigo = await valor('Código da barraca')
  const pin = await valor('PIN da atendente')
  expect(senha).toHaveLength(12)
  expect(pin).toMatch(/^\d{6}$/)
  await page.reload()
  await expect(page.getByText(senha)).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Reiniciar a barraca de demonstração' })).toBeVisible()
  checkAdmin()

  // 2) O dono entra no painel: faixa de treino, materiais para imprimir
  const dono = await novoAparelho()
  const checkDono = failOnConsoleErrors(dono)
  await dono.goto('/painel/entrar')
  await dono.getByLabel('E-mail').fill(email)
  await dono.getByLabel('Senha').fill(senha)
  await dono.getByRole('button', { name: 'Entrar' }).click()
  await expect(dono.getByText('Barraca de treino: nada aqui é de verdade')).toBeVisible()
  await dono.getByRole('link', { name: 'Materiais' }).click()
  await expect(dono.getByRole('heading', { name: 'Materiais da barraca' })).toBeVisible()
  await expectNoHorizontalScroll(dono)

  // O guia e a plaquinha abrem com o nome da barraca e o botão de imprimir
  await dono.goto('/painel/materiais/guia')
  await expect(dono.getByRole('heading', { name: 'Guia rápido da equipe' })).toBeVisible()
  await expect(dono.getByText('Barraca Demo (treino)').first()).toBeVisible()
  await expect(dono.getByText(codigo).first()).toBeVisible()
  await expect(dono.getByRole('button', { name: 'Imprimir (A4)' })).toBeVisible()
  await dono.goto('/painel/materiais/plaquinha')
  await expect(dono.getByRole('heading', { name: 'Peça pelo celular' })).toBeVisible()
  await expect(dono.getByText('Faça seu cadastro aqui e receba seu QR')).toBeVisible()
  checkDono()

  // 3) A atendente entra no app com o PIN mostrado: faixa de treino e os pedidos de exemplo já na fila
  const app = await novoAparelho()
  const checkApp = failOnConsoleErrors(app)
  await app.goto('/staff/')
  await app.getByRole('tab', { name: 'Sou da equipe' }).click()
  await app.getByLabel('Código da barraca').fill(codigo)
  await app.getByRole('button', { name: 'Continuar' }).click()
  await app.getByRole('button', { name: 'Carla (treino)' }).click()
  await app.getByLabel('Seu PIN').fill(pin)
  await app.getByRole('button', { name: 'Entrar' }).click()
  await expect(app.getByRole('heading', { name: 'Clientes' })).toBeVisible()
  await expect(app.getByText('Barraca de treino: nada aqui é de verdade')).toBeVisible()
  await app.getByRole('link', { name: /^Fila/ }).click()
  await expect(app.locator('.pedido')).toHaveCount(2)
  await expect(app.locator('.pedido').first()).toContainText('Ana (treino)')
  await expectNoHorizontalScroll(app)

  // 4) A atendente reporta um problema (Mais → Reportar problema) e o admin vê com a tela e a conexão
  const aviso = `A fila demorou para atualizar ${Date.now()}`
  await app.getByRole('link', { name: 'Mais' }).click()
  await expect(app.getByRole('heading', { name: 'Reportar problema' })).toBeVisible()
  await app.getByLabel('O que aconteceu?').fill(aviso)
  await app.getByRole('button', { name: 'Enviar aviso' }).click()
  await expect(app.getByText('Aviso enviado. Obrigado!')).toBeVisible()
  await expectNoHorizontalScroll(app)
  checkApp()
  await page.goto('/admin')
  await page.reload() // a mesma página com outro # não recarrega sozinha
  const linha = page.getByRole('row', { name: new RegExp(aviso) })
  await expect(linha).toContainText('Barraca Demo (treino)')
  await expect(linha).toContainText('Quiosque')
  await expect(linha).toContainText('fila')
  await expect(linha).toContainText('online')
})
