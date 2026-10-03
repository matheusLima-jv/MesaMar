import { conferir, inteiro, itensEnvio, lerJson, lista, novoCliente, objeto, registro, texto, uuid } from '../../web-shared/src/validacao'
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
let contexto = ''
export function definirContextoPendencias (novo: string): void { if (novo !== contexto) memoria.clear(); contexto = novo }
const chave = (tipo: TipoPendencia, escopo: string): string => `pendente.${tipo}.${contexto}.${escopo}`

export const pendencias = {
  ler<T> (tipo: TipoPendencia, escopo: string): Guardada<T> | null {
    const k = chave(tipo, escopo)
    try {
      const salvo: unknown = memoria.has(k) ? memoria.get(k) : lerJson(lembrar.get(k))
      if (salvo === null || salvo === undefined) return null
      const regraDados = tipo === 'checkin' ? novoCliente : objeto({ itens: itensEnvio, linhas: lista(texto(300), 30, 1), totalCentavos: inteiro(0) })
      const p = conferir<Guardada<T> & { em: number, versao: number }>(salvo, objeto({ versao: v => v === 1, id: uuid, dados: regraDados, em: inteiro(1) }))
      if (p.em > Date.now() + 300_000 || Date.now() - p.em > VALIDADE_DIAS * 86_400_000) return null
      return structuredClone(p)
    } catch { return null }
  },
  guardar<T> (tipo: TipoPendencia, escopo: string, id: string, dados: T): void {
    const k = chave(tipo, escopo)
    const em = Date.now()
    const salvo = { versao: 1, id, dados: structuredClone(dados), em }
    conferir(salvo, objeto({ versao: v => v === 1, id: uuid, dados: tipo === 'checkin' ? novoCliente : objeto({ itens: itensEnvio, linhas: lista(texto(300), 30, 1), totalCentavos: inteiro(0) }), em: inteiro(1) }))
    memoria.set(k, salvo)
    // `em` serve só para expirar: uma pendência esquecida não fica no aparelho para sempre
    lembrar.set(k, JSON.stringify(salvo))
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
      const objeto = lerJson(bruto)
      if (!registro(objeto)) { localStorage.removeItem(k); continue }
      if (!inteiro(1)(objeto.em) || Number(objeto.em) > Date.now() + 300_000 || Number(objeto.em) < limite) localStorage.removeItem(k)
    }
  } catch { /* armazenamento indisponível: nada a limpar */ }
}
