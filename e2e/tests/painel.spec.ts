import { expect, test, permitirHttp } from './fixtures'
import { expectNoHorizontalScroll, randomWhatsapp } from './helpers'
import { montarQuiosque, criarAna, criarBruno } from './quiosque'

test('configuração da barraca e convite consumido', async ({ page, browser }) => {
  test.setTimeout(90000)
  const { link, novoAparelho, caminho, dono } = await montarQuiosque(page, browser)
  await dono.goto('/painel')
  await expect(dono.getByRole('link', { name: 'Abrir o app do quiosque' })).toBeVisible()
  permitirHttp(/\/painel\/cadastro\?convite=/, 404)
  const outro = await novoAparelho()
  expect((await outro.goto(caminho(link)))?.status()).toBe(404)
  await expect(outro.getByText('Esse convite não vale mais.')).toBeVisible()
})

test('pedido, reenvio offline, limites, fila e cancelamento', async ({ page, browser }) => {
  test.setTimeout(120000)
  const q = await montarQuiosque(page, browser)
  const { app, novoAparelho, caminho } = q
  const cliente = await criarAna(q)
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

  // 9) Fila do quiosque (Fase 4): o Bruno faz check-in e pede, e a fila da atendente se atualiza sozinha
  await app.getByRole('link', { name: 'Clientes' }).click()
  await app.getByRole('button', { name: '+ Novo cliente' }).click()
  await app.getByLabel('Nome do cliente').fill('Bruno')
  await app.getByRole('radio', { name: 'Guarda-sol 5', exact: true }).click()
  await app.getByLabel(/^WhatsApp/).fill(randomWhatsapp())
  await app.getByLabel(/O cliente concorda/).check()
  await app.getByRole('button', { name: 'Gerar QR' }).click()
  const qrBruno = app.getByRole('dialog', { name: 'Bruno' })
  const zapBruno = await qrBruno.getByRole('link', { name: /Enviar pelo WhatsApp/ }).getAttribute('href') ?? ''
  const linkBruno = /https?:\/\/\S+\/c\/[A-Za-z0-9_-]{22}/.exec(decodeURIComponent(zapBruno))?.[0] ?? ''
  expect(linkBruno).not.toBe('')
  await qrBruno.getByRole('button', { name: 'Pronto' }).click()

  // A fila mostra os 3 pedidos da Ana por ordem de chegada, com o guarda-sol em destaque
  await app.getByRole('link', { name: /^Fila/ }).click()
  await expect(app.getByRole('heading', { name: 'Fila' })).toBeVisible()
  await expect(app.getByText('Ao vivo')).toBeVisible()
  const cartoes = app.locator('.pedido')
  await expect(cartoes).toHaveCount(3)
  await expect(cartoes.first()).toContainText('#1')
  await expect(cartoes.first()).toContainText('Guarda-sol 12')
  await expect(cartoes.first()).toContainText('2× Água de coco')
  await expect(cartoes.first()).toContainText('sem gelo')
  await expect(cartoes.last()).toContainText('#3')
  await expectNoHorizontalScroll(app)

  // O Bruno pede em outro celular: o pedido aparece no fim da fila, sem recarregar nada
  const bruno = await novoAparelho()
  await bruno.goto(caminho(linkBruno))
  await expect(bruno.getByRole('heading', { name: 'Olá, Bruno · Guarda-sol 5' })).toBeVisible()
  await bruno.getByRole('button', { name: 'Adicionar Pastel' }).click()
  await bruno.getByRole('link', { name: /Ver pedido/ }).click()
  await bruno.getByRole('button', { name: 'Enviar pedido' }).click()
  await expect(bruno.getByRole('heading', { name: 'Pedido nº 4 enviado' })).toBeVisible()
  await expect(cartoes).toHaveCount(4)
  await expect(cartoes.last()).toContainText('Bruno')
  await expect(cartoes.last()).toContainText('Guarda-sol 5')
  await expect(cartoes.last()).toContainText('Novo')
  await expect(app.getByRole('link', { name: /^Fila/ })).toContainText('4')

  // Filtro por guarda-sol
  await app.locator('#filtro-gs').selectOption({ label: 'Guarda-sol 5' })
  await expect(cartoes).toHaveCount(1)
  await app.locator('#filtro-gs').selectOption({ label: 'Todos' })
  await expect(cartoes).toHaveCount(4)

  // "Em preparo": a Ana, em outro celular, vê na hora e sem recarregar (tempo real, não os 15 s do plano B)
  await app.getByRole('button', { name: 'Em preparo: pedido 1' }).click()
  await expect(cliente.locator('.order').last()).toContainText('Em preparo')
  await app.getByRole('button', { name: 'Pendentes (3)' }).click()
  await expect(cartoes).toHaveCount(3)
  await app.getByRole('button', { name: 'Todos (4)' }).click()

  // Cancelar um item exige motivo. A Ana recebe o aviso com o motivo, a conta baixa de R$ 99 para R$ 74
  // e o limite de R$ 90 deixa de bloquear
  await app.getByRole('button', { name: 'Cancelar: pedido 1' }).click()
  const dialogo = app.getByRole('dialog', { name: /Cancelar o pedido nº 1/ })
  await dialogo.getByLabel('1× Pastel').check()
  await dialogo.getByRole('button', { name: 'Confirmar cancelamento' }).click()
  await expect(dialogo.getByText('Escolha o motivo do cancelamento.')).toBeVisible()
  await dialogo.getByLabel('Esgotou').check()
  await dialogo.getByRole('button', { name: 'Confirmar cancelamento' }).click()
  await expect(app.getByText('Item cancelado no pedido nº 1.')).toBeVisible()
  await expect(cliente.getByText('O quiosque cancelou 1× Pastel do pedido nº 1: Esgotou.')).toBeVisible()
  await expect(cliente.getByText('Sua conta: R$ 74,00')).toBeVisible()
  await expect(cliente.getByText('Sua conta chegou a R$ 90,00')).toBeHidden()
  await expect(cliente.locator('.order').last()).toContainText('Cancelado: Esgotou')
  await cliente.getByRole('button', { name: 'Entendi' }).click()
  await expect(cliente.getByText('O quiosque cancelou 1× Pastel')).toBeHidden()

  // Entregue: sai da fila e a Ana vê
  await app.getByRole('button', { name: 'Entregue: pedido 1' }).click()
  await expect(cliente.locator('.order').last()).toContainText('Entregue')
  await expect(cartoes).toHaveCount(3)

  // Cancelar o pedido inteiro, com "Outro" e um detalhe
  await app.getByRole('button', { name: 'Cancelar: pedido 3' }).click()
  const dialogo3 = app.getByRole('dialog', { name: /Cancelar o pedido nº 3/ })
  await dialogo3.getByLabel('Outro').check()
  await dialogo3.getByLabel('Detalhe').fill('cliente foi embora')
  await dialogo3.getByRole('button', { name: 'Confirmar cancelamento' }).click()
  await expect(cliente.getByText('O quiosque cancelou o pedido nº 3: Outro: cliente foi embora.')).toBeVisible()
  await expect(cliente.getByText('Sua conta: R$ 49,00')).toBeVisible()
  await expect(cartoes).toHaveCount(2)
  await expectNoHorizontalScroll(app)

  // 10) Avisos de pedido (4.2): sem as chaves VAPID no servidor, o app diz o que falta em vez de mostrar um botão que não funciona
  await app.getByRole('link', { name: 'Mais' }).click()
  await expect(app.getByRole('heading', { name: 'Avisos de pedido' })).toBeVisible()
  if (test.info().project.name === 'iphone') await expect(app.getByText('No iPhone, os avisos só funcionam', { exact: false })).toBeVisible()
  else await expect(app.getByText('ainda não foram configurados no servidor')).toBeVisible()
  await expectNoHorizontalScroll(app)

  // 11) "Pausar pedidos": a Ana, que está em "Meus pedidos", vê o recado na hora, e ao voltar ele some
  await app.getByRole('link', { name: /^Fila/ }).click()
  await app.getByRole('button', { name: 'Pausar pedidos' }).click()
  const dialogoPausa = app.getByRole('dialog', { name: 'Pausar pedidos' })
  await dialogoPausa.getByLabel(/Recado para os clientes/).fill('Voltamos em 10 minutos')
  await dialogoPausa.getByRole('button', { name: 'Pausar pedidos' }).click()
  await expect(app.getByRole('button', { name: 'Voltar a receber pedidos' })).toBeVisible()
  await expect(cliente.getByText('O quiosque pausou os pedidos: Voltamos em 10 minutos')).toBeVisible()
  await app.getByRole('button', { name: 'Voltar a receber pedidos' }).click()
  await expect(cliente.getByText('O quiosque pausou os pedidos')).toBeHidden()
  await expect(app.getByRole('button', { name: 'Pausar pedidos' })).toBeVisible()
  await expectNoHorizontalScroll(app)

})

test('Pix, pagamento parcial, troco e relatório', async ({ page, browser }) => {
  test.setTimeout(120000)
  const q = await montarQuiosque(page, browser)
  const { app, dono } = q
  const cliente = await criarAna(q)
  await criarBruno(q)
  // Um cancelamento independente mantém o relatório de cancelamentos coberto.
  const sessao = await (await app.request.get('/api/staff/sessao')).json()
  const comandas = await (await app.request.get('/api/staff/clientes')).json()
  const secoes = await (await app.request.get('/api/staff/cardapio')).json()
  const produtos = secoes.flatMap((s: { produtos: Array<{ id: string, nome: string }> }) => s.produtos)
  const pedido = await app.request.post(`/api/staff/comandas/${comandas.find((c: { nome: string }) => c.nome === 'Bruno').id}/pedidos`, { headers: { 'X-CSRF-TOKEN': sessao.csrf }, data: { id: crypto.randomUUID(), itens: [{ produtoId: produtos.find((p: { nome: string }) => p.nome === 'Pastel').id, quantidade: 1 }] } })
  expect(pedido.status()).toBe(201)
  const idPedido = (await pedido.json()).id
  expect((await app.request.post(`/api/staff/pedidos/${idPedido}/cancelar`, { headers: { 'X-CSRF-TOKEN': sessao.csrf }, data: { motivo: 'ESGOTOU', detalhe: '' } })).status()).toBe(200)
  // 12) Fechar conta (5.1): a Ana tem R$ 49,00 a pagar. Dinheiro com troco, desiste, e depois escolhe o Pix
  await cliente.getByRole('link', { name: 'Conta', exact: true }).click()
  await expect(cliente.getByRole('heading', { name: 'Conta', exact: true })).toBeVisible()
  await expect(cliente.locator('.resumo-linha.forte')).toContainText('R$ 49,00')
  await expect(cliente.getByRole('button', { name: 'Fechar a conta' })).toBeDisabled()
  await cliente.getByRole('radio', { name: /^Dinheiro/ }).click()
  await cliente.getByLabel(/Vou pagar com R\$/).fill('abc')
  await cliente.getByRole('button', { name: 'Fechar a conta' }).click()
  await expect(cliente.getByText('Não entendemos o valor do troco')).toBeVisible()
  await cliente.getByLabel(/Vou pagar com R\$/).fill('100')
  await cliente.getByRole('button', { name: 'Fechar a conta' }).click()
  await expect(cliente.getByRole('heading', { name: 'Aguardando confirmação do quiosque' })).toBeVisible()
  await expect(cliente.getByText('Vá ao quiosque pagar R$ 49,00 em dinheiro (troco para R$ 100,00).')).toBeVisible()
  await expectNoHorizontalScroll(cliente)

  // Com a conta fechando, o cardápio não deixa pedir e explica o caminho de volta
  await cliente.getByRole('link', { name: 'Cardápio' }).click()
  await expect(cliente.getByText('Sua conta está sendo fechada.')).toBeVisible()
  await expect(cliente.getByRole('button', { name: /^Adicionar / })).toHaveCount(0)

  await cliente.getByRole('link', { name: 'Conta', exact: true }).click()
  await cliente.getByRole('button', { name: 'Voltar e pedir mais' }).click()
  await expect(cliente.getByRole('button', { name: 'Fechar a conta' })).toBeVisible()
  await cliente.getByRole('link', { name: 'Cardápio' }).click()
  await expect(cliente.getByText('Sua conta está sendo fechada.')).toBeHidden()
  await expect(cliente.getByRole('button', { name: /^Adicionar / }).first()).toBeVisible()

  // Pix: o QR e o código aparecem no celular da Ana, com o valor da conta e o recebedor da barraca
  await cliente.getByRole('link', { name: 'Conta', exact: true }).click()
  await cliente.getByRole('radio', { name: /^Pix/ }).click()
  await cliente.getByRole('button', { name: 'Fechar a conta' }).click()
  await expect(cliente.getByRole('heading', { name: 'Aguardando confirmação do quiosque' })).toBeVisible()
  await expect(cliente.locator('.pix svg.qr')).toBeVisible()
  await expect(cliente.locator('.pix-valor')).toHaveText('R$ 49,00')
  await expect(cliente.getByRole('button', { name: 'Copiar código Pix' })).toBeVisible()
  await cliente.getByText('Ver o código').click()
  await expect(cliente.getByLabel('Código Pix copia e cola')).toHaveValue(/^000201.*6304[0-9A-F]{4}$/)
  await cliente.getByRole('button', { name: 'Já paguei' }).click()
  await expect(cliente.getByText(/Você avisou às \d\d:\d\d\./)).toBeVisible()
  await expectNoHorizontalScroll(cliente)

  // 13) Pagamentos (5.2): a conta da Ana chega sozinha à aba, a atendente recebe em duas partes (Pix e dinheiro
  // com troco) e a Ana vê, em outro celular e sem recarregar, o abate e depois "Conta paga"
  await expect(app.getByRole('link', { name: /^Pagamentos/ })).toContainText('1')
  await app.getByRole('link', { name: /^Pagamentos/ }).click()
  await expect(app.getByRole('heading', { name: 'Pagamentos', exact: true })).toBeVisible()
  const contaAna = app.locator('.pag-aguarda')
  await expect(contaAna).toHaveCount(1)
  await expect(contaAna).toContainText('Guarda-sol 12')
  await expect(contaAna).toContainText('R$ 49,00')
  await expect(contaAna).toContainText('avisou que pagou')
  await expect(contaAna).toContainText('Confira no app do banco antes de confirmar.')
  await expect(app.getByRole('heading', { name: 'Contas abertas com saldo (1)' })).toBeVisible()
  await expectNoHorizontalScroll(app)

  await app.getByRole('button', { name: 'Receber de Ana' }).click()
  const receberPix = app.getByRole('dialog', { name: 'Receber de Ana' })
  await expect(receberPix.getByText('Confira no app do seu banco antes de confirmar.')).toBeVisible()
  await receberPix.getByLabel(/Valor recebido agora/).fill('20')
  await receberPix.getByRole('button', { name: 'Confirmar R$ 20,00 recebido' }).click()
  await expect(app.getByText('Recebido R$ 20,00 de Ana. Ainda falta R$ 29,00.')).toBeVisible()
  await expect(cliente.locator('.resumo-linha.forte')).toContainText('R$ 29,00')
  await expect(cliente.getByText('Pago em pix')).toBeVisible()

  await app.getByRole('button', { name: 'Receber de Ana' }).click()
  const receberDinheiro = app.getByRole('dialog', { name: 'Receber de Ana' })
  await receberDinheiro.getByLabel('Dinheiro').check()
  await receberDinheiro.getByLabel(/O cliente entregou/).fill('50')
  await expect(receberDinheiro.getByText('Devolver de troco: R$ 21,00')).toBeVisible()
  await receberDinheiro.getByRole('button', { name: 'Confirmar R$ 29,00 recebido' }).click()
  await expect(app.getByText('Conta de Ana paga e encerrada. Troco: R$ 21,00.')).toBeVisible()
  await expect(cliente.getByRole('heading', { name: 'Conta paga' })).toBeVisible()
  await expect(contaAna).toHaveCount(0)
  await expectNoHorizontalScroll(cliente)

  // 14) Relatório do dia (5.3): o dono vê o que entrou (R$ 20,00 no Pix + R$ 29,00 em dinheiro) e quem ainda deve (Bruno)
  await dono.goto('/painel/relatorio')
  await expect(dono.getByRole('heading', { name: /^Hoje, \d\d\/\d\d\/\d{4}$/ })).toBeVisible()
  await expect(dono.locator('.kv').first()).toContainText('Recebido no dia')
  await expect(dono.locator('.kv').first()).toContainText('R$ 49,00')
  await expect(dono.getByRole('row', { name: /Pix 1 pagamento\(s\) R\$ 20,00/ })).toBeVisible()
  await expect(dono.getByRole('row', { name: /Dinheiro 1 pagamento\(s\) R\$ 29,00/ })).toBeVisible()
  await expect(dono.getByRole('row', { name: /Bruno.*R\$ 25,00/ })).toBeVisible()
  await expect(dono.getByRole('row', { name: /Esgotou/ })).toBeVisible()
  await expect(dono.getByRole('link', { name: 'Baixar em CSV' })).toHaveAttribute('href', /relatorio\.csv\?dia=\d{4}-\d\d-\d\d$/)
  await expectNoHorizontalScroll(dono)

})

test('lançamento da equipe, teclado, modais, telas estreitas e aviso', async ({ page, browser }) => {
  test.setTimeout(120000)
  const q = await montarQuiosque(page, browser)
  const { app, dono } = q
  const cliente = await criarAna(q)
  const bruno = await criarBruno(q)
  // 14b) Lançar pedido para cliente (6.1): o Bruno está sem celular na mão, a atendente lança 2 águas de coco pela comanda dele
  await app.getByRole('link', { name: 'Clientes' }).click()
  await app.getByRole('button', { name: 'Lançar pedido para Bruno' }).click()
  await expect(app.getByRole('heading', { name: 'Lançar pedido' })).toBeVisible()
  await expect(app.getByRole('button', { name: 'Lançar pedido', exact: true })).toBeDisabled()
  await app.getByRole('button', { name: 'Mais um Água de coco' }).click()
  await app.getByRole('button', { name: 'Mais um Água de coco' }).click()
  await expect(app.getByText('2 itens · R$ 24,00')).toBeVisible()
  await expectNoHorizontalScroll(app)
  await app.getByRole('button', { name: 'Lançar pedido', exact: true }).click()
  await expect(app.getByText('lançado para Bruno.')).toBeVisible()
  await app.getByRole('link', { name: /^Fila/ }).click()
  await expect(app.locator('.pedido').last()).toContainText('Bruno')
  await expect(app.locator('.pedido').last()).toContainText('2× Água de coco')
  // O Bruno vê o pedido na página dele, sem recarregar
  await bruno.getByRole('link', { name: 'Meus pedidos', exact: true }).click()
  await expect(bruno.locator('.order')).toHaveCount(2)

  // 14c) Acessibilidade, busca de clientes e texto longo (revisão, lote 3)
  const tamanhoOriginal = app.viewportSize() ?? { width: 412, height: 915 }
  const checkIn = async (nome: string): Promise<void> => {
    await app.getByRole('button', { name: '+ Novo cliente' }).click()
    await app.getByLabel('Nome do cliente').fill(nome)
    await app.getByLabel(/O cliente concorda/).check()
    await app.getByRole('button', { name: 'Gerar QR' }).click()
  }
  await app.getByRole('link', { name: 'Clientes' }).click()

  // Os guarda-sóis são radios de verdade: as setas movem e escolhem
  await app.getByRole('button', { name: '+ Novo cliente' }).click()
  const balcao = app.getByRole('radio', { name: 'Sem guarda-sol (balcão)' })
  await balcao.focus()
  await app.keyboard.press('ArrowRight')
  await expect(app.getByRole('radio', { name: 'Guarda-sol 1', exact: true })).toHaveAttribute('aria-checked', 'true')
  await expect(app.getByRole('radio', { name: 'Guarda-sol 1', exact: true })).toBeFocused()
  await app.keyboard.press('ArrowLeft')
  await expect(balcao).toHaveAttribute('aria-checked', 'true')
  await app.getByRole('button', { name: 'Cancelar' }).click()

  // Nomes longos: com espaços e sem espaço nenhum
  const nomeLongo = 'Maria Aparecida dos Santos Albuquerque'
  const nomeSemEspaco = 'M'.repeat(40)
  await checkIn(nomeLongo)
  const qrLongo = app.getByRole('dialog', { name: nomeLongo })

  // Modal: o resto do app fica inerte, o Tab não sai dele e o Esc devolve o foco a quem abriu
  await expect(qrLongo).toBeVisible()
  await expect(app.locator('#app')).toHaveAttribute('inert', '')
  for (let i = 0; i < 6; i++) await app.keyboard.press('Tab')
  expect(await app.evaluate(() => document.activeElement?.closest('[role="dialog"]') !== null), 'o foco ficou preso no QR').toBe(true)
  await app.keyboard.press('Shift+Tab')
  expect(await app.evaluate(() => document.activeElement?.closest('[role="dialog"]') !== null)).toBe(true)

  // QR novo: troca o link e a comanda continua. O link antigo vale só até aqui
  await qrLongo.getByRole('button', { name: 'Trocar o QR deste cliente' }).click()
  await expect(qrLongo.getByText('O QR e o link atuais deixam de abrir.')).toBeVisible()
  const svgAntes = await qrLongo.locator('svg.qr').innerHTML()
  await qrLongo.getByRole('button', { name: 'Gerar QR novo agora' }).click()
  await expect.poll(async () => await app.getByRole('dialog', { name: nomeLongo }).locator('svg.qr').innerHTML(), 'o QR mudou').not.toBe(svgAntes)
  await expectNoHorizontalScroll(app)
  await app.keyboard.press('Escape')
  await expect(app.getByRole('dialog')).toHaveCount(0)
  await expect(app.locator('#app')).not.toHaveAttribute('inert', '')
  await expect(app.getByRole('heading', { name: 'Clientes' })).toBeVisible()

  await checkIn(nomeSemEspaco)
  await app.getByRole('button', { name: 'Pronto' }).click()

  // Busca por nome (sem acento e sem maiúscula), por guarda-sol e filtro de situação
  await app.getByLabel('Buscar cliente').fill('aparecida')
  await expect(app.locator('.people li')).toHaveCount(1)
  await expect(app.locator('.people li')).toContainText(nomeLongo)
  await expect(app.locator('#resultado-clientes')).toContainText('1 de')
  await app.getByLabel('Buscar cliente').fill('zzzz')
  await expect(app.getByText('Ninguém combina com essa busca.')).toBeVisible()
  await app.getByRole('button', { name: 'Limpar a busca' }).click()
  await expect(app.locator('.people li').first()).toBeVisible()
  await app.getByLabel('Buscar cliente').fill('5')
  await expect(app.locator('.people li').filter({ hasText: 'Bruno' })).toHaveCount(1)
  await app.getByLabel('Buscar cliente').fill('')
  await app.getByLabel('Situação da comanda').selectOption({ label: 'Bloqueadas' })
  await expect(app.getByText('Ninguém combina com essa busca.')).toBeVisible()
  await app.getByRole('button', { name: 'Limpar a busca' }).click()

  // Telas estreitas e com zoom de 200% (um celular de 360px com zoom vira ~180px): nada estoura para os lados
  // Com o cursor num campo, o teclado está aberto e a barra de abas se esconde de propósito: tira o foco antes
  await app.evaluate(() => { (document.activeElement as HTMLElement | null)?.blur() })
  await expect(app.locator('body')).not.toHaveClass(/teclado/)
  for (const largura of [320, 190]) {
    await app.setViewportSize({ width: largura, height: 640 })
    await expectNoHorizontalScroll(app)
    await app.getByRole('link', { name: /^Fila/ }).click()
    await expectNoHorizontalScroll(app)
    await app.getByRole('link', { name: 'Clientes' }).click()
    await app.getByRole('button', { name: `Mostrar o QR de ${nomeSemEspaco}` }).click()
    await expectNoHorizontalScroll(app)
    await app.keyboard.press('Escape')
    await app.getByRole('button', { name: `Lançar pedido para ${nomeSemEspaco}` }).click()
    await expect(app.getByRole('heading', { name: 'Lançar pedido' })).toBeVisible()
    await expectNoHorizontalScroll(app)
    await app.getByRole('button', { name: 'Cancelar' }).click()
  }
  await app.setViewportSize(tamanhoOriginal)

  // Ao trocar de aba, a página volta ao topo (a rolagem é da página, não de um contêiner)
  await app.getByRole('link', { name: /^Fila/ }).click()
  await app.evaluate(() => window.scrollTo(0, 400))
  await app.getByRole('link', { name: 'Clientes' }).click()
  // A troca de aba roda no hashchange, que o navegador dispara depois do clique: espera a aba nova antes de conferir
  await expect(app.getByRole('link', { name: 'Clientes' })).toHaveAttribute('aria-current', 'page')
  await expect.poll(async () => await app.evaluate(() => window.scrollY)).toBe(0)

  // Os avisos importantes não somem sozinhos como o toast: um pedido parado fica na tela, em qualquer aba
  await expect(app.getByRole('region', { name: 'Avisos importantes' })).toBeHidden()

  // 15) Reportar problema na página do cliente (7.3): uma frase, e o aviso sai
  await cliente.getByRole('link', { name: 'Reportar um problema' }).click()
  await expect(cliente.getByRole('heading', { name: 'Reportar um problema' })).toBeVisible()
  await cliente.getByLabel('O que aconteceu?').fill('Não consegui ver o Pix')
  await cliente.getByRole('button', { name: 'Enviar aviso' }).click()
  await expect(cliente.getByText('Aviso enviado. Obrigado!')).toBeVisible()
  await expectNoHorizontalScroll(cliente)

  // O dono vê o aviso da Ana no painel dele (menu Avisos)
  await dono.goto('/painel/problemas')
  await expect(dono.getByRole('heading', { name: 'Avisos de problema' })).toBeVisible()
  await expect(dono.getByText('Não consegui ver o Pix')).toBeVisible()
  await expect(dono.getByText('Cliente', { exact: true })).toBeVisible()
  await expectNoHorizontalScroll(dono)

  await dono.reload()
  await expect(dono.getByRole('link', { name: 'Equipe', exact: true })).toBeVisible()
  await dono.goto('/painel/cardapio')
  await expect(dono.getByRole('button', { name: 'Marcar Água de coco como esgotado' })).toBeVisible()

})
