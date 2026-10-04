import { conferir, escolha, inteiro, itensEnvio, novoCliente, objeto, opcional, registro, texto, uuid } from '../../web-shared/src/validacao'

export interface Operacao {
  versao: 1
  chave: string
  contexto: string
  id: string
  tipo: 'checkin' | 'lancamento'
  comandaId?: string
  dados: unknown
  criadaEm: number
  ordem: number
  estado: 'pendente' | 'conflito'
  motivo?: string
}
const regra = objeto({ versao: escolha(1), chave: texto(300, 1), contexto: texto(200, 1), id: uuid, tipo: escolha('checkin', 'lancamento'), comandaId: opcional(uuid), criadaEm: inteiro(1), ordem: inteiro(1), estado: escolha('pendente', 'conflito'), motivo: opcional(texto(2000)) })
export function validarOperacao (v: unknown): Operacao {
  const op = conferir<Operacao>(v, regra)
  if (op.chave !== `${op.contexto}:${op.id}` || op.criadaEm > Date.now() + 300_000) throw new Error('Pendência inválida')
  if (op.tipo === 'checkin') { conferir(op.dados, novoCliente); if (!registro(op.dados) || op.dados.id !== op.id) throw new Error('Identificador divergente') }
  else { conferir(op.dados, objeto({ id: uuid, itens: itensEnvio })); if (!uuid(op.comandaId) || !registro(op.dados) || op.dados.id !== op.id) throw new Error('Pedido inválido') }
  return structuredClone(op)
}

/** Transações IndexedDB serializam inclusão e sequência, inclusive entre abas. Falha de quota impede promessa de preservação. */
const BANCO = 'mesamar-equipe-offline'
export const LIMITE_POR_CONTEXTO = 200
async function abrir (): Promise<IDBDatabase> {
  return await new Promise((resolve, reject) => {
    const req = indexedDB.open(BANCO, 1)
    req.onupgradeneeded = () => { req.result.createObjectStore('operacoes', { keyPath: 'chave' }); req.result.createObjectStore('cache', { keyPath: 'chave' }) }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(new Error('Não foi possível guardar a ação neste aparelho. Libere espaço e tente de novo.'))
  })
}
async function transacao<T> (loja: string, modo: IDBTransactionMode, executar: (store: IDBObjectStore, concluir: (v: T) => void) => void): Promise<T> {
  const db = await abrir()
  try {
    return await new Promise<T>((resolve, reject) => {
      const tx = db.transaction(loja, modo)
      let resultado: T
      executar(tx.objectStore(loja), v => { resultado = v })
      tx.oncomplete = () => resolve(resultado)
      tx.onerror = tx.onabort = () => reject(new Error('A ação não pôde ser preservada. Confira o espaço do aparelho.'))
    })
  } finally { db.close() }
}
export const mudancasOffline = new EventTarget()
const avisar = (): void => { mudancasOffline.dispatchEvent(new Event('mudou')) }
export async function listarOperacoes (contexto: string): Promise<Operacao[]> {
  const bruto = await transacao<unknown[]>('operacoes', 'readonly', (s, concluir) => { s.getAll().onsuccess = e => concluir((e.target as IDBRequest<unknown[]>).result) })
  const resultado: Operacao[] = []
  for (const v of bruto) {
    try {
      const op = validarOperacao(v)
      if (Date.now() - op.criadaEm > 3 * 86_400_000) { await removerOperacao(op.chave); continue }
      if (op.contexto === contexto) resultado.push(op)
    } catch {
      if (registro(v) && typeof v.chave === 'string') await removerOperacao(v.chave)
    }
  }
  return resultado.sort((a, b) => a.ordem - b.ordem || a.chave.localeCompare(b.chave))
}
export async function preservar (contexto: string, tipo: Operacao['tipo'], id: string, dados: unknown, comandaId?: string): Promise<Operacao> {
  const op = validarOperacao({ versao: 1, chave: `${contexto}:${id}`, contexto, tipo, id, dados, comandaId, criadaEm: Date.now(), ordem: 1, estado: 'pendente' })
  const salvo = await transacao<Operacao>('operacoes', 'readwrite', (store, concluir) => {
    store.getAll().onsuccess = e => {
      // Um registro corrompido é descartado aqui mesmo: não pode impedir que a nova ação seja preservada.
      const todas: Operacao[] = []
      for (const v of (e.target as IDBRequest<unknown[]>).result) {
        try { todas.push(validarOperacao(v)) } catch { if (registro(v) && typeof v.chave === 'string') store.delete(v.chave) }
      }
      const existente = todas.find(o => o.chave === op.chave)
      if (existente !== undefined) { concluir(existente); return }
      // O limite vale por barraca/autor: pendências de outra sessão no mesmo aparelho não bloqueiam esta.
      if (todas.filter(o => o.contexto === contexto).length >= LIMITE_POR_CONTEXTO) { store.transaction.abort(); return }
      op.ordem = Math.max(0, ...todas.map(o => o.ordem)) + 1
      store.add(op); concluir(op)
    }
  })
  avisar(); return salvo
}
export async function removerOperacao (chave: string): Promise<void> { await transacao<void>('operacoes', 'readwrite', (s, concluir) => { s.delete(chave); concluir(undefined) }); avisar() }
export async function conflito (op: Operacao, motivo: string): Promise<void> { await transacao<void>('operacoes', 'readwrite', (s, concluir) => { s.put({ ...op, estado: 'conflito', motivo: motivo.slice(0, 2000) }); concluir(undefined) }); avisar() }
export async function guardarCache (contexto: string, caminho: string, dados: unknown): Promise<void> {
  await transacao<void>('cache', 'readwrite', (s, concluir) => {
    s.getAll().onsuccess = e => {
      for (const v of (e.target as IDBRequest<unknown[]>).result) if (registro(v) && typeof v.em === 'number' && Date.now() - v.em > 3 * 86_400_000 && typeof v.chave === 'string') s.delete(v.chave)
      s.put({ chave: `${contexto}:${caminho}`, dados, em: Date.now() }); concluir(undefined)
    }
  })
}
export async function lerCache (contexto: string, caminho: string): Promise<unknown> {
  const v = await transacao<unknown>('cache', 'readonly', (s, concluir) => { s.get(`${contexto}:${caminho}`).onsuccess = e => concluir((e.target as IDBRequest<unknown>).result) })
  return registro(v) && inteiro(1)(v.em) && Number(v.em) <= Date.now() + 300_000 && Date.now() - Number(v.em) < 3 * 86_400_000 ? v.dados : undefined
}
export interface FalhaFila { status: number, temporario: boolean, descartada?: boolean, message: string }
/** Não passa por cima de conflito, sessão expirada ou resultado desconhecido. Ordem global inclui check-in antes do seu pedido. */
export async function drenar (contexto: string, enviar: (op: Operacao) => Promise<void>, vigente: () => boolean): Promise<void> {
  for (const op of await listarOperacoes(contexto)) {
    if (!vigente() || op.estado === 'conflito') return
    try { await enviar(op); if (!vigente()) return; await removerOperacao(op.chave) } catch (e) {
      if (!vigente()) return
      const erro = e as Partial<FalhaFila>
      if (erro.descartada === true || erro.status === 401 || erro.status === 403 || erro.temporario === true || erro.status === undefined) return
      await conflito(op, erro.message ?? 'Operação recusada. Confira com a equipe.'); return
    }
  }
}
/**
 * Espera até a próxima tentativa de sincronia: 15 s sem falhas, dobrando a cada falha seguida até 2 min,
 * com variação de ±20% para que vários aparelhos não voltem todos no mesmo instante.
 */
export function esperaSincronia (falhas: number, aleatorio: () => number = Math.random): number {
  const base = Math.min(120_000, 15_000 * 2 ** Math.max(0, Math.min(falhas, 10)))
  return Math.round(base * (0.8 + 0.4 * aleatorio()))
}
export function tokenLocal (): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
