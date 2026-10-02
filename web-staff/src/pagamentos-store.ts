// O que está para pagar, guardado fora das telas (como a fila): o número na aba Pagamentos e o aviso "indo pagar"
// funcionam em qualquer aba. O servidor só avisa "uma conta mudou" (evento `conta`, pela mesma conexão em tempo
// real da fila); o app busca a lista de novo (/api/staff/pagamentos), então a tela sempre mostra o que o servidor tem.
import { ApiError, pagamentos, reais, type ContaAPagar, type Pagamentos } from './api'
import { avisarPagamento } from './som'
import { avisar } from './toast'

type Ouvinte = () => void

const REDESENHO_MS = 250

let dados: Pagamentos | null = null
let diferencaMs = 0
let falhou = false
let timerBusca: number | undefined
let primeiraBusca = true
/** Avisos que o app já mostrou: comanda + hora do aviso. Avisar de novo muda a hora, e então toca de novo. */
const vistos = new Set<string>()
const ouvintes = new Set<Ouvinte>()

export function estado (): { dados: Pagamentos | null, falhou: boolean } {
  return { dados, falhou }
}

/** O horário do servidor agora (o relógio do celular pode estar errado). */
export function agoraDoServidor (): number {
  return Date.now() + diferencaMs
}

export function observar (ouvinte: Ouvinte): () => void {
  ouvintes.add(ouvinte)
  return () => { ouvintes.delete(ouvinte) }
}

function avisarOuvintes (): void {
  ouvintes.forEach((o) => { o() })
}

/** Quantas contas esperam o quiosque receber (o número que aparece na aba). */
export function aguardando (): number {
  return dados?.aguardando.length ?? 0
}

/** "Guarda-sol 12 · Ana está indo pagar · R$ 87,50 · Dinheiro (troco para R$ 100,00)". */
export function textoDoAviso (c: ContaAPagar): string {
  const local = c.guardaSol === null ? 'Balcão' : `Guarda-sol ${c.guardaSol}`
  if (c.metodo === 'PIX') return `${local} · ${c.cliente} avisou que pagou · ${reais(c.saldoCentavos)} · Pix: conferir no app do banco`
  const forma = c.metodo === 'DINHEIRO' ? 'Dinheiro' : 'Cartão'
  const troco = c.metodo === 'DINHEIRO' && c.trocoParaCentavos !== null ? ` (troco para ${reais(c.trocoParaCentavos)})` : ''
  return `${local} · ${c.cliente} está indo pagar · ${reais(c.saldoCentavos)} · ${forma}${troco}`
}

export async function recarregar (): Promise<void> {
  try {
    const nova = await pagamentos()
    diferencaMs = Date.parse(nova.agora) - Date.now()
    const novos = nova.aguardando.filter((c) => c.avisoEm !== null && !vistos.has(`${c.comandaId}@${c.avisoEm}`))
    nova.aguardando.forEach((c) => { if (c.avisoEm !== null) vistos.add(`${c.comandaId}@${c.avisoEm}`) })
    // A primeira busca só mostra o que já estava lá: não toca som por aviso antigo
    if (!primeiraBusca && novos.length > 0) {
      avisarPagamento()
      avisar(textoDoAviso(novos[0]) + (novos.length > 1 ? ` (+${novos.length - 1})` : ''))
    }
    primeiraBusca = false
    dados = nova
    falhou = false
  } catch (e) {
    // 401: o app já volta para o login (evento da api). Sem sinal: segue com a última lista e tenta de novo.
    falhou = !(e instanceof ApiError && e.status === 401)
  }
  avisarOuvintes()
}

/** Várias mudanças seguidas viram uma busca só. */
export function agendarBusca (): void {
  window.clearTimeout(timerBusca)
  timerBusca = window.setTimeout(() => { void recarregar() }, REDESENHO_MS)
}

export function iniciarPagamentos (): void {
  void recarregar()
}

export function pararPagamentos (): void {
  window.clearTimeout(timerBusca)
  dados = null
  falhou = false
  primeiraBusca = true
  vistos.clear()
}
