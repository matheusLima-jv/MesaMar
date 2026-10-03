import { test, expect, permitirFalhaRede } from './fixtures'
import { montarQuiosque } from './quiosque'
import { createServer, request as httpRequest } from 'node:http'
import { pendencias, checkin } from './offline-helpers'

test('corte real da resposta após cabeçalhos e commit mantém identificador e confirma uma única comanda', async ({ page, browser }) => {
  test.setTimeout(90000)
  const { app } = await montarQuiosque(page, browser)
  const destino = new URL(test.info().project.use.baseURL as string || process.env.E2E_BASE_URL || 'http://localhost:8080')
  let cortou = false
  const ids: string[] = []
  const proxy = createServer((req, res) => {
    const pedaco: Buffer[] = []
    req.on('data', b => pedaco.push(b))
    req.on('end', () => {
      const corpo = Buffer.concat(pedaco)
      const corte = req.method === 'POST' && req.url === '/api/staff/clientes' && !cortou
      if (req.method === 'POST' && req.url === '/api/staff/clientes') ids.push(JSON.parse(corpo.toString()).id)
      const remoto = httpRequest(new URL(req.url!, destino), { method: req.method, headers: { ...req.headers, host: destino.host, 'accept-encoding': 'identity' } }, resposta => {
        if (corte && resposta.statusCode === 201) {
          cortou = true
          const partes: Buffer[] = []
          resposta.on('data', b => partes.push(b))
          resposta.on('end', () => {
            const completo = Buffer.concat(partes)
            const headers = { ...resposta.headers, 'content-length': completo.length }
            delete headers['transfer-encoding']
            res.writeHead(201, headers); res.flushHeaders(); res.write(completo.subarray(0, 8))
            setTimeout(() => res.destroy(), 40)
          })
        } else { res.writeHead(resposta.statusCode!, resposta.headers); resposta.pipe(res) }
      })
      remoto.on('error', () => res.destroy()); remoto.end(corpo)
    })
  })
  await new Promise<void>(resolve => proxy.listen(0, '127.0.0.1', resolve))
  const porta = (proxy.address() as { port: number }).port
  const recuperacao = await app.context().newPage()
  permitirFalhaRede(/\/api\/staff\/clientes$/, app.context())
  try {
    await recuperacao.goto(`http://localhost:${porta}/staff/`)
    await expect(recuperacao.getByRole('heading', { name: 'Clientes' })).toBeVisible()
    await checkin(recuperacao, 'Resposta cortada')
    await expect.poll(() => cortou).toBe(true)
    await expect.poll(async () => (await pendencias(recuperacao)).length, { timeout: 25000 }).toBe(0)
    const clientes = await (await app.request.get('/api/staff/clientes?pagina=0')).json()
    expect(clientes.filter((c: { id: string }) => c.id === ids[0])).toHaveLength(1)
    expect(ids.length).toBeGreaterThanOrEqual(2)
    expect(new Set(ids).size).toBe(1)
  } finally { await recuperacao.close(); proxy.closeAllConnections(); await new Promise<void>(resolve => proxy.close(() => resolve())) }
})
