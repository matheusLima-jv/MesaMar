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
  await dono.getByRole('radio', { name: 'CPF' }).check()
  await dono.getByLabel('Chave', { exact: true }).fill('529.982.247-25')
  await dono.getByLabel('Nome de quem recebe').fill('José da Praia')
  await dono.getByRole('button', { name: 'Salvar chave Pix' }).click()
  await expect(dono.getByText('Recebendo em JOSE DA PRAIA')).toBeVisible()
  await expect(dono.getByText('529.982.247-25')).toBeVisible()

  // 6) Guarda-sóis: aceita a sugestão (1 ao limite padrão, 30)
  await dono.getByRole('link', { name: 'Guarda-sóis', exact: true }).click()
  await dono.getByRole('button', { name: 'Criar guarda-sóis' }).click()
  await expect(dono.getByText('Pronto: 30 criados.')).toBeVisible()
  await expectNoHorizontalScroll(dono)

  // 7) Cardápio: categoria, produto e "esgotado" com um toque
  await dono.getByRole('link', { name: 'Cardápio', exact: true }).click()
  await dono.getByLabel('Comece pela primeira categoria').fill('Bebidas')
  await dono.getByRole('button', { name: 'Criar categoria' }).click()
  await dono.getByRole('link', { name: 'Adicionar produto em Bebidas' }).click()
  await dono.getByLabel('Nome', { exact: true }).fill('Água de coco')
  await dono.getByLabel('Preço (R$)').fill('12')
  await dono.getByRole('button', { name: 'Adicionar ao cardápio' }).click()
  await expect(dono.getByText('Água de coco (R$ 12,00) entrou no cardápio.')).toBeVisible()
  await dono.getByRole('link', { name: 'Adicionar produto em Bebidas' }).click()
  await dono.getByLabel('Nome', { exact: true }).fill('Pastel')
  await dono.getByLabel('Preço (R$)').fill('25')
  await dono.getByRole('button', { name: 'Adicionar ao cardápio' }).click()
  await expect(dono.getByText('Pastel (R$ 25,00) entrou no cardápio.')).toBeVisible()
  await dono.getByRole('button', { name: 'Marcar Água de coco como esgotado' }).click()
  await expect(dono.getByRole('button', { name: 'Voltar a vender Água de coco' })).toHaveAttribute('aria-pressed', 'true')
  await expectNoHorizontalScroll(dono)

  // 7b) Regras: limite de conta aberta de R$ 90 (vale para o cliente, mais abaixo)
  await dono.getByRole('link', { name: 'Regras', exact: true }).click()
  await dono.getByLabel('Limite de conta aberta (R$)').fill('90')
  await dono.getByRole('button', { name: 'Salvar regras' }).click()
  await expect(dono.getByText('Regras salvas.')).toBeVisible()
  await expectNoHorizontalScroll(dono)

  // 8) Equipe + app do quiosque em outro celular: atendente entra com código, nome e PIN
  await dono.getByRole('link', { name: 'Equipe', exact: true }).click()
  await dono.getByLabel('Nome ou apelido').fill('Carla')
  await dono.getByLabel('PIN (4 a 6 números)').fill('2580')
  await dono.getByRole('button', { name: 'Adicionar à equipe' }).click()
  await expect(dono.getByText('Carla foi adicionado.')).toBeVisible()
  const codigo = (await dono.locator('.app-lead .mono').first().textContent())?.trim() ?? ''
  expect(codigo).not.toBe('')

  const app = await novoAparelho()
  const checkApp = failOnConsoleErrors(app)
  await app.goto('/staff/')
  await app.getByRole('tab', { name: 'Sou da equipe' }).click()
  await app.getByLabel('Código da barraca').fill(codigo)
  await app.getByRole('button', { name: 'Continuar' }).click()
  await app.getByRole('button', { name: 'Carla' }).click()
  await app.getByLabel('Seu PIN').fill('2580')
  await app.getByRole('button', { name: 'Entrar' }).click()
  await expect(app.getByRole('heading', { name: 'Clientes' })).toBeVisible()
  await expectNoHorizontalScroll(app)

  // 8b) Check-in da Ana no guarda-sol 12: QR em tela cheia, WhatsApp e lista por ordem de chegada
  await app.getByRole('button', { name: '+ Novo cliente' }).click()
  await app.getByLabel('Nome do cliente').fill('Ana')
  await app.getByRole('button', { name: 'Guarda-sol 12', exact: true }).click()
  await app.getByLabel(/^WhatsApp/).fill('(13) 99999-8888')
  await app.getByRole('button', { name: 'Gerar QR' }).click()
  await expect(app.getByText('Confirme que o cliente concordou.')).toBeVisible()
  await app.getByLabel(/O cliente concorda/).check()
  await expectNoHorizontalScroll(app)
  await app.getByRole('button', { name: 'Gerar QR' }).click()
  const qr = app.getByRole('dialog', { name: 'Ana' })
  await expect(qr.getByRole('img', { name: 'QR da comanda de Ana' })).toBeVisible()
  await expect(qr.getByText('Guarda-sol 12 · chegada nº 1')).toBeVisible()
  const whatsapp = await qr.getByRole('link', { name: /Enviar pelo WhatsApp/ }).getAttribute('href') ?? ''
  expect(whatsapp).toContain('https://wa.me/5513999998888?text=')
  const linkCliente = /https?:\/\/\S+\/c\/[A-Za-z0-9_-]{22}/.exec(decodeURIComponent(whatsapp))?.[0] ?? ''
  expect(linkCliente).not.toBe('')
  await expectNoHorizontalScroll(app)
  await qr.getByRole('button', { name: 'Pronto' }).click()
  await expect(app.getByText('Guarda-sol 12 ·')).toBeVisible()
  await app.getByRole('button', { name: 'Mostrar o QR de Ana' }).click()
  await expect(app.getByRole('img', { name: 'QR da comanda de Ana' })).toBeVisible()
  await app.getByRole('button', { name: 'Pronto' }).click()

  // O link do QR abre a página do cliente em outro celular. A água de coco ficou esgotada no passo 7:
  // aparece, mas sem botão de pedir.
  const cliente = await novoAparelho()
  const checkCliente = failOnConsoleErrors(cliente)
  await cliente.goto(caminho(linkCliente))
  await expect(cliente.locator('body[data-app="web-customer"]')).toBeAttached()
  await expect(cliente.getByRole('heading', { name: 'Olá, Ana · Guarda-sol 12' })).toBeVisible()
  await expect(cliente.getByText('Quiosque E2E', { exact: true })).toBeVisible()
  await expect(cliente.getByText('Esgotado', { exact: true })).toBeVisible()
  await expect(cliente.getByRole('button', { name: 'Adicionar Água de coco' })).toHaveCount(0)
  await expect(cliente.getByRole('link', { name: /Ver pedido/ })).toBeHidden()
  await expect(cliente.getByText('Sua conta: R$ 0,00')).toBeVisible()
  await expectNoHorizontalScroll(cliente)

  // A atendente devolve a água de coco ao cardápio com um toque.
  await app.getByRole('link', { name: 'Cardápio' }).click()
  await expect(app.getByRole('heading', { name: 'Cardápio' })).toBeVisible()
  await app.getByRole('button', { name: 'Voltar a vender Água de coco' }).click()
  await expect(app.getByText('Água de coco voltou.')).toBeVisible()

  // 8c) A Ana pede: 2 águas de coco (sem gelo) e 1 pastel. Total R$ 49,00
  await cliente.reload()
  await cliente.getByRole('button', { name: 'Adicionar Água de coco' }).click()
  await cliente.getByRole('button', { name: 'Mais um Água de coco' }).click()
  await cliente.getByRole('button', { name: 'Adicionar Pastel' }).click()
  await cliente.getByRole('link', { name: /Ver pedido · 3 itens · R\$ 49,00/ }).click()
  await expect(cliente.getByRole('heading', { name: 'Seu pedido' })).toBeVisible()
  await cliente.getByLabel(/Observação de Água de coco/).fill('sem gelo')
  await expect(cliente.getByText('R$ 49,00', { exact: true })).toBeVisible()
  await expectNoHorizontalScroll(cliente)
  await cliente.getByRole('button', { name: 'Enviar pedido' }).click()
  await expect(cliente.getByRole('heading', { name: 'Pedido nº 1 enviado' })).toBeVisible()
  await expect(cliente.getByText('2× Água de coco')).toBeVisible()
  await expect(cliente.getByText('sem gelo')).toBeVisible()
  await expect(cliente.getByText('Sua conta: R$ 49,00')).toBeVisible()
  await expectNoHorizontalScroll(cliente)
  checkCliente()

  // 8d) Sem sinal: o pedido espera e sai sozinho quando o sinal volta, uma vez só (nº 2, e o próximo é o 3)
  await cliente.getByRole('button', { name: 'Voltar ao cardápio' }).click()
  await cliente.getByRole('button', { name: 'Adicionar Pastel' }).click()
  await cliente.getByRole('link', { name: /Ver pedido · 1 item · R\$ 25,00/ }).click()
  await cliente.context().setOffline(true)
  await cliente.getByRole('button', { name: 'Enviar pedido' }).click()
  await expect(cliente.getByText('Sem sinal. Tentaremos de novo. Se preferir, chame um atendente.')).toBeVisible()
  await cliente.context().setOffline(false)
  await expect(cliente.getByRole('heading', { name: 'Pedido nº 2 enviado' })).toBeVisible()
  await cliente.getByRole('button', { name: 'Voltar ao cardápio' }).click()
  await cliente.getByRole('button', { name: 'Adicionar Pastel' }).click()
  await cliente.getByRole('link', { name: /Ver pedido/ }).click()
  await cliente.getByRole('button', { name: 'Enviar pedido' }).click()
  await expect(cliente.getByRole('heading', { name: 'Pedido nº 3 enviado' })).toBeVisible()

  // 8e) A conta chegou a R$ 99, acima do limite de R$ 90: o cardápio avisa e não deixa pedir mais.
  // "Meus pedidos" mostra os 3 pedidos, do mais novo para o mais antigo, com o saldo.
  await expect(cliente.getByText('Sua conta: R$ 99,00')).toBeVisible()
  await cliente.getByRole('button', { name: 'Voltar ao cardápio' }).click()
  await expect(cliente.getByText('Sua conta chegou a R$ 90,00. Para pedir mais, pague uma parte com um atendente.')).toBeVisible()
  await expect(cliente.getByRole('button', { name: /^Adicionar / })).toHaveCount(0)
  await expect(cliente.getByRole('link', { name: /Ver pedido/ })).toBeHidden()
  await cliente.getByRole('link', { name: 'Meus pedidos' }).click()
  await expect(cliente.getByRole('heading', { name: 'Meus pedidos' })).toBeVisible()
  await expect(cliente.locator('.order')).toHaveCount(3)
  await expect(cliente.locator('.order').first()).toContainText('Pedido nº 3')
  await expect(cliente.locator('.order').last()).toContainText('Pedido nº 1')
  await expect(cliente.locator('.order').last()).toContainText('2× Água de coco')
  await expect(cliente.locator('.order').last()).toContainText('Recebido')
  await expect(cliente.getByText('Total da conta R$ 99,00')).toBeVisible()
  await expectNoHorizontalScroll(cliente)

  await dono.reload()
  await expect(dono.getByRole('link', { name: 'Equipe', exact: true })).toBeVisible()
  await dono.goto('/painel/cardapio')
  await expect(dono.getByRole('button', { name: 'Marcar Água de coco como esgotado' })).toBeVisible()
  checkApp()

  // 9) O mesmo link não serve de novo
  const outro = await novoAparelho()
  const resposta = await outro.goto(caminho(link))
  expect(resposta?.status()).toBe(404)
  await expect(outro.getByText('Esse convite não vale mais.')).toBeVisible()

  checkDono()
})
