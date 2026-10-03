// Ações da equipe que saíram do aparelho e ficaram sem resposta (sinal caiu, prazo estourou, app fechou no meio).
// Ficam na memória e no armazenamento do aparelho: mudar de aba ou recarregar o app não perde o id, e o próximo envio
// repete a MESMA ação (o servidor reconhece o id e não registra duas vezes). Saem daqui quando o servidor responde,
// com sucesso ou com uma recusa (4xx), porque aí se sabe o que aconteceu.
//
// Base para a fila offline do checkpoint 6.2.
import { lembrar } from './dom'

export type TipoPendencia = 'lancamento' | 'checkin'

interface Guardada<T> { id: string, dados: T }

const memoria = new Map<string, Guardada<unknown> | null>()
const chave = (tipo: TipoPendencia, escopo: string): string => `pendente.${tipo}.${escopo}`

export const pendencias = {
  ler<T> (tipo: TipoPendencia, escopo: string): Guardada<T> | null {
    const k = chave(tipo, escopo)
    if (memoria.has(k)) return (memoria.get(k) ?? null) as Guardada<T> | null
    try {
      const salvo = JSON.parse(lembrar.get(k) ?? 'null') as Guardada<T> | null
      return salvo !== null && typeof salvo.id === 'string' ? salvo : null
    } catch { return null }
  },
  guardar<T> (tipo: TipoPendencia, escopo: string, id: string, dados: T): void {
    const k = chave(tipo, escopo)
    memoria.set(k, { id, dados })
    // `em` serve só para expirar: uma pendência esquecida não fica no aparelho para sempre
    lembrar.set(k, JSON.stringify({ id, dados, em: Date.now() }))
  },
  apagar (tipo: TipoPendencia, escopo: string): void {
    const k = chave(tipo, escopo)
    memoria.set(k, null)
    lembrar.set(k, 'null')
  }
}

/** Depois de quantos dias uma ação sem resposta é esquecida: o cliente de lá já foi embora, e o dado pessoal não fica no celular. */
const VALIDADE_DIAS = 3
const PREFIXOS = ['mesamar.pendente.', 'mesamar.pagamento-incerto.']

/**
 * Apaga do aparelho o que ficou guardado há mais de [VALIDADE_DIAS] dias (check-in com nome do cliente, lançamento e
 * pagamento sem resposta). O que ainda não tem data (de uma versão anterior do app) ganha a de hoje e vence depois.
 */
export function limparDadosAntigos (): void {
  try {
    const limite = Date.now() - VALIDADE_DIAS * 86_400_000
    for (const k of Object.keys(localStorage)) {
      if (!PREFIXOS.some((p) => k.startsWith(p))) continue
      const bruto = localStorage.getItem(k)
      let objeto: { em?: number } | null = null
      try { objeto = JSON.parse(bruto ?? 'null') as { em?: number } | null } catch { objeto = null }
      if (objeto === null) { localStorage.removeItem(k); continue }
      if (typeof objeto.em !== 'number') localStorage.setItem(k, JSON.stringify({ ...objeto, em: Date.now() }))
      else if (objeto.em < limite) localStorage.removeItem(k)
    }
  } catch { /* armazenamento indisponível: nada a limpar */ }
}
