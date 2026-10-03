import type { Page } from '@playwright/test'
export async function pendencias(page: Page): Promise<Array<{ id: string, tipo: string, dados: { token: string }, comandaId?: string }>> {
  return await page.evaluate(async () => await new Promise((resolve, reject) => {
    const r = indexedDB.open('mesamar-equipe-offline', 1)
    r.onerror = () => reject(r.error)
    r.onsuccess = () => {
      const db = r.result
      const q = db.transaction('operacoes').objectStore('operacoes').getAll()
      q.onsuccess = () => { db.close(); resolve(q.result.sort((a, b) => a.ordem - b.ordem)) }
      q.onerror = () => { db.close(); reject(q.error) }
    }
  }))
}
export async function checkin(app: Page, nome: string) {
  await app.getByRole('button', { name: '+ Novo cliente' }).click()
  await app.getByLabel('Nome do cliente').fill(nome)
  await app.getByLabel(/O cliente concorda/).check()
  await app.getByRole('button', { name: 'Gerar QR' }).click()
}

