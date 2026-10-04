import { test, expect } from './fixtures'
import { montarQuiosque } from './quiosque'

test('respostas atrasadas de Clientes e Cardápio não substituem Fila ou Pagamentos', async ({ page, browser }) => {
  test.setTimeout(90_000)
  const { app } = await montarQuiosque(page, browser)
  for (const origem of ['Clientes', 'Cardápio']) {
    for (const destino of ['Fila', 'Pagamentos']) {
      await app.getByRole('link', { name: origem === 'Clientes' ? 'Cardápio' : 'Clientes', exact: true }).click()
      let liberar!: () => void
      let entrou!: () => void
      let finalizou!: () => void
      const aguardar = new Promise<void>(r => { liberar = r })
      const iniciada = new Promise<void>(r => { entrou = r })
      const entregue = new Promise<void>(r => { finalizou = r })
      const rota = origem === 'Clientes' ? '/api/staff/clientes' : '/api/staff/cardapio'
      const combinar = (url: URL): boolean => url.pathname === rota
      await app.route(combinar, async route => {
        const resposta = await route.fetch()
        entrou()
        await aguardar
        await route.fulfill({ response: resposta })
        finalizou()
      })
      await app.getByRole('link', { name: origem, exact: true }).click()
      await iniciada
      await app.getByRole('link', { name: new RegExp(`^${destino}`) }).click()
      await expect(app.getByRole('heading', { name: destino, exact: true })).toBeVisible()
      liberar()
      await entregue
      await expect(app.getByRole('heading', { name: destino, exact: true })).toBeVisible()
      await expect(app.getByRole('heading', { name: origem, exact: true })).toHaveCount(0)
      await app.unroute(combinar)
    }
  }
})
