import type { Page } from '@playwright/test'
import { test, expect } from './fixtures'
import { montarQuiosque } from './quiosque'
import { pendencias, checkin } from './offline-helpers'

// Fase 6.3: testes obrigatórios do modo offline da equipe.

interface PedidoFila { id: string, cliente: string, itens: Array<{ quantidade: number, cancelado: boolean }> }

async function lancarSemSinal (app: Page, cliente: string, unidades: number): Promise<void> {
  await app.getByRole('button', { name: `Lançar pedido para ${cliente}` }).click()
  for (let i = 0; i < unidades; i++) await app.getByRole('button', { name: 'Mais um Pastel' }).click()
  await expect(app.getByText(`${unidades} itens · `, { exact: false })).toBeVisible()
  await app.getByRole('button', { name: /Lançar pedido/ }).click()
  await expect(app.getByText('Pedido preservado neste aparelho.', { exact: false })).toBeVisible()
  await app.getByRole('button', { name: 'Voltar', exact: true }).click()
  await expect(app.getByRole('heading', { name: 'Clientes' })).toBeVisible()
}

/** Com sinal, abre o Cardápio: é daí que o aparelho guarda a cópia usada para lançar pedidos sem sinal. */
async function guardarCardapio (app: Page): Promise<void> {
  await app.getByRole('link', { name: 'Cardápio' }).click()
  await expect(app.getByRole('heading', { name: 'Cardápio' })).toBeVisible()
  await app.getByRole('link', { name: 'Clientes' }).click()
  await expect(app.getByRole('heading', { name: 'Clientes' })).toBeVisible()
}

async function fila (app: Page): Promise<PedidoFila[]> {
  return (await (await app.request.get('/api/staff/fila')).json()).pedidos
}

const unidadesDe = (pedidos: PedidoFila[]): number =>
  pedidos.flatMap(p => p.itens).filter(i => !i.cancelado).reduce((soma, i) => soma + i.quantidade, 0)

test('dez itens lançados sem sinal chegam ao servidor exatamente uma vez e o dono vê o aparelho sincronizado', async ({ page, browser }) => {
  test.setTimeout(120000)
  const { app, dono } = await montarQuiosque(page, browser)
  for (const nome of ['Dez Um', 'Dez Dois']) {
    await checkin(app, nome)
    await app.getByRole('button', { name: 'Pronto' }).click()
  }
  await expect(app.getByRole('button', { name: 'Lançar pedido para Dez Dois' })).toBeVisible()

  await guardarCardapio(app)
  await app.context().setOffline(true)
  await lancarSemSinal(app, 'Dez Um', 6)
  await lancarSemSinal(app, 'Dez Dois', 4)
  const guardados = await pendencias(app)
  expect(guardados.map(p => p.tipo)).toEqual(['lancamento', 'lancamento'])
  // A consulta de conferência sai pelo teste, não pelo celular: confirma que nada chegou enquanto o aparelho estava sem sinal
  expect((await fila(app)).filter(p => p.cliente === 'Dez Um' || p.cliente === 'Dez Dois')).toEqual([])

  await app.context().setOffline(false)
  await expect.poll(async () => (await pendencias(app)).length, { timeout: 30000 }).toBe(0)

  const pedidos = (await fila(app)).filter(p => p.cliente === 'Dez Um' || p.cliente === 'Dez Dois')
  expect(pedidos.map(p => p.id).sort()).toEqual(guardados.map(p => p.id).sort())
  expect(unidadesDe(pedidos)).toBe(10)

  // O dono vê o aparelho da Carla com a última sincronização e nada guardado nele
  await dono.goto('/painel/equipe')
  const aparelho = dono.locator('section[aria-labelledby="t-aparelhos"] li').filter({ hasText: 'Carla · ' })
  await expect(aparelho).toHaveCount(1)
  await expect(aparelho).toContainText('Última sincronização:')
  await expect(aparelho).toContainText('Tudo enviado')
})

test('dois aparelhos sem sinal lançando na mesma comanda convergem sem duplicar', async ({ page, browser }) => {
  test.setTimeout(150000)
  const q = await montarQuiosque(page, browser)
  const { app, dono, email, senha, novoAparelho } = q
  await checkin(app, 'Mesa Dupla')
  await app.getByRole('button', { name: 'Pronto' }).click()

  // Segundo aparelho: o dono entra no app do quiosque no próprio celular
  const app2 = await novoAparelho()
  await app2.goto('/staff/')
  await app2.getByRole('tab', { name: 'Sou o dono' }).click()
  await app2.getByLabel('E-mail').fill(email)
  await app2.getByLabel('Senha').fill(senha)
  await app2.getByRole('button', { name: 'Entrar' }).click()
  await expect(app2.getByRole('heading', { name: 'Clientes' })).toBeVisible()
  await expect(app2.getByRole('button', { name: 'Lançar pedido para Mesa Dupla' })).toBeVisible()

  await guardarCardapio(app)
  await guardarCardapio(app2)
  await app.context().setOffline(true)
  await app2.context().setOffline(true)
  await lancarSemSinal(app, 'Mesa Dupla', 2)
  await lancarSemSinal(app2, 'Mesa Dupla', 3)
  const [doPrimeiro] = await pendencias(app)
  const [doSegundo] = await pendencias(app2)
  expect(doPrimeiro.comandaId).toBe(doSegundo.comandaId)
  expect(doPrimeiro.id).not.toBe(doSegundo.id)

  // Voltam em momentos diferentes; o primeiro ainda cai e volta de novo antes de terminar
  await app2.context().setOffline(false)
  await expect.poll(async () => (await pendencias(app2)).length, { timeout: 30000 }).toBe(0)
  await app.context().setOffline(false)
  await app.context().setOffline(true)
  await app.context().setOffline(false)
  await expect.poll(async () => (await pendencias(app)).length, { timeout: 30000 }).toBe(0)

  const daMesa = (): Promise<PedidoFila[]> => fila(app).then(ps => ps.filter(p => p.cliente === 'Mesa Dupla'))
  expect((await daMesa()).map(p => p.id).sort()).toEqual([doPrimeiro.id, doSegundo.id].sort())
  expect(unidadesDe(await daMesa())).toBe(5)

  // O mesmo lançamento reenviado de novo é reconhecido, não vira um terceiro pedido
  const sessao = await (await app.request.get('/api/staff/sessao')).json()
  const repetido = await app.request.post(`/api/staff/comandas/${doPrimeiro.comandaId}/pedidos`, {
    headers: { 'X-CSRF-TOKEN': sessao.csrf },
    data: doPrimeiro.dados
  })
  expect(repetido.status()).toBe(200)
  expect(await daMesa()).toHaveLength(2)
  expect(unidadesDe(await daMesa())).toBe(5)

  // O dono vê os dois aparelhos, cada um com a própria última sincronização
  await dono.goto('/painel/equipe')
  const aparelhos = dono.locator('section[aria-labelledby="t-aparelhos"] li')
  await expect(aparelhos).toHaveCount(2)
  await expect(aparelhos.filter({ hasText: 'Carla · ' })).toContainText('Tudo enviado')
  await expect(aparelhos.filter({ hasText: 'Zé E2E · ' })).toContainText('Tudo enviado')
})
