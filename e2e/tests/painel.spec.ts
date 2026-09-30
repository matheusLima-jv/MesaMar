import { expect, test } from '@playwright/test'
import { expectNoHorizontalScroll, failOnConsoleErrors } from './helpers'

const password = process.env.E2E_ADMIN_PASSWORD ?? ''

test.skip(!password, 'defina E2E_ADMIN_PASSWORD para testar o convite')

test('dono recebe convite, cria a conta e deixa a barraca pronta', async ({ page, browser }) => {
  // Outro "aparelho" com as mesmas opções do projeto (baseURL, tamanho de celular)
  const novoAparelho = async () => await (await browser.newContext(test.info().project.use)).newPage()
  const caminho = (url: string): string => new URL(url).pathname + new URL(url).search
  const checkConsole = failOnConsoleErrors(page)
  const email = `dono-${Date.now()}@exemplo.com`
  const senha = 'guarda-sol azul na enseada'

  // 1) Admin gera um convite avulso
  await page.goto('/admin/login')
  await page.getByLabel('Usuário').fill('admin')
  await page.getByLabel('Senha').fill(password)
  await page.getByRole('button', { name: 'Entrar no painel' }).click()
  await page.getByLabel(/Convite avulso/).fill('Quiosque E2E')
  await page.getByRole('button', { name: 'Gerar convite avulso' }).click()
  const link = await page.getByLabel('Link do convite').inputValue()
  expect(link).toContain('/painel/cadastro?convite=')
  checkConsole()

  // 2) Dono abre o link em outro aparelho
  const dono = await novoAparelho()
  const checkDono = failOnConsoleErrors(dono)
  await dono.goto(caminho(link))
  await expect(dono.getByRole('heading', { name: 'Crie a sua conta' })).toBeVisible()
  await expectNoHorizontalScroll(dono)
  await dono.getByLabel('Seu nome').fill('Zé E2E')
  await dono.getByLabel('E-mail').fill(email)
  await dono.getByLabel('Senha').fill(senha)
  await dono.getByRole('button', { name: 'Criar conta' }).click()

  // 3) Entra (o e-mail já vem preenchido)
  await expect(dono.getByText('Conta criada!')).toBeVisible()
  await expect(dono.getByLabel('E-mail')).toHaveValue(email)
  await dono.getByLabel('Senha').fill(senha)
  await dono.getByRole('button', { name: 'Entrar' }).click()

  // 4) Primeiro acesso: dados da barraca
  await expect(dono).toHaveURL(/\/painel\/barraca$/)
  await dono.getByLabel('Nome da barraca ou quiosque').fill('Quiosque E2E')
  await dono.getByLabel('Praia').selectOption({ label: 'Enseada' })
  await dono.getByRole('button', { name: 'Continuar' }).click()
  await expect(dono.getByText('Barraca criada. Agora falta a chave Pix.')).toBeVisible()
  await expectNoHorizontalScroll(dono)

  // 5) Chave Pix
  await dono.getByRole('link', { name: 'Configurar Chave Pix' }).click()
  await dono.getByText('CPF', { exact: true }).click()
  await dono.getByLabel('Chave', { exact: true }).fill('529.982.247-25')
  await dono.getByLabel('Nome de quem recebe').fill('José da Praia')
  await dono.getByRole('button', { name: 'Salvar chave Pix' }).click()
  await expect(dono.getByText('Recebendo em JOSE DA PRAIA')).toBeVisible()
  await expect(dono.getByText('529.982.247-25')).toBeVisible()

  // 6) O mesmo link não serve de novo
  const outro = await novoAparelho()
  const resposta = await outro.goto(caminho(link))
  expect(resposta?.status()).toBe(404)
  await expect(outro.getByText('Esse convite não vale mais.')).toBeVisible()

  checkDono()
})
