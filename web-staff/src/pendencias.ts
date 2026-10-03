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
    lembrar.set(k, JSON.stringify({ id, dados }))
  },
  apagar (tipo: TipoPendencia, escopo: string): void {
    const k = chave(tipo, escopo)
    memoria.set(k, null)
    lembrar.set(k, 'null')
  }
}
