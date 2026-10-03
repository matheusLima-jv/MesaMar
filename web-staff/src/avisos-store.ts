// Avisos operacionais que NÃO somem sozinhos: pedido parado há muito tempo e cliente indo pagar. O toast some em poucos
// segundos (e quem estava olhando para outra coisa perde); aqui o aviso fica na tela, em qualquer aba, até o problema
// acabar (o pedido sai da fila, a conta é recebida) ou até a pessoa tocar em "Entendi".
//
// São derivados do que o servidor mostra (a fila e os pagamentos): quem abre o app no meio de um atraso também vê.
import { anunciar } from './a11y'

export type TipoAviso = 'atraso' | 'pagamento'

export interface AvisoFixo {
  /** Identifica o aviso: o mesmo `chave` é o mesmo aviso (o texto pode mudar, como os minutos de espera). */
  chave: string
  tipo: TipoAviso
  texto: string
  /** A aba onde se resolve. */
  aba: 'fila' | 'pagamentos'
}

type Ouvinte = () => void

const avisos = new Map<string, AvisoFixo>()
/** O que a pessoa dispensou com "Entendi": não volta enquanto a chave for a mesma. */
const dispensados = new Set<string>()
const ouvintes = new Set<Ouvinte>()

export function observarAvisos (ouvinte: Ouvinte): () => void {
  ouvintes.add(ouvinte)
  return () => { ouvintes.delete(ouvinte) }
}

export function avisosFixos (): AvisoFixo[] {
  return Array.from(avisos.values())
}

/** Troca TODOS os avisos de um tipo pelo que o servidor mostra agora. Aviso novo é anunciado ao leitor de tela. */
export function sincronizarAvisos (tipo: TipoAviso, agora: AvisoFixo[]): void {
  const antes = new Map(Array.from(avisos).filter(([, a]) => a.tipo === tipo))
  let mudou = false
  for (const [chave] of antes) {
    if (!agora.some((a) => a.chave === chave)) { avisos.delete(chave); mudou = true }
  }
  for (const a of agora) {
    if (dispensados.has(a.chave)) continue
    const existente = antes.get(a.chave)
    if (existente === undefined) {
      anunciar(a.texto)
      mudou = true
    } else if (existente.texto !== a.texto) {
      mudou = true
    }
    avisos.set(a.chave, a)
  }
  // O que já foi resolvido não precisa mais ficar na lista de dispensados
  for (const chave of Array.from(dispensados)) {
    if (chave.startsWith(`${tipo}:`) && !agora.some((a) => a.chave === chave)) dispensados.delete(chave)
  }
  if (mudou) ouvintes.forEach((o) => { o() })
}

export function dispensarAviso (chave: string): void {
  dispensados.add(chave)
  if (avisos.delete(chave)) ouvintes.forEach((o) => { o() })
}

/** Ao sair: nada de uma sessão passa para a próxima. */
export function limparAvisos (): void {
  avisos.clear()
  dispensados.clear()
  ouvintes.forEach((o) => { o() })
}
