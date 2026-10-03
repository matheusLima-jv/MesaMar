// O que está para pagar, guardado fora das telas (como a fila): o número na aba Pagamentos e o aviso "indo pagar"
// funcionam em qualquer aba. O servidor só avisa "uma conta mudou" (evento `conta`, pela mesma conexão em tempo
// real da fila); o app busca a lista de novo (/api/staff/pagamentos), então a tela sempre mostra o que o servidor tem.
import { ApiError, descartada, pagamentos, reais, type ContaAPagar, type Pagamentos } from './api'
import { umaPorVez } from './busca'
import { avisarPagamento } from './som'
import { avisar } from './toast'
import { sincronizarAvisos } from './avisos-store'

type Ouvinte = () => void

const REDESENHO_MS = 250

let pagina = 0
let dados: Pagamentos | null = null
let diferencaMs = 0
let falhou = false
let timerBusca: number | undefined
let primeiraBusca = true
let ativa = false
let geracao = 0
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
  return dados?.totalAguardando ?? 0
}

/** "Guarda-sol 12 · Ana está indo pagar · R$ 87,50 · Dinheiro (troco para R$ 100,00)". */
export function textoDoAviso (c: ContaAPagar): string {
  const local = c.guardaSol === null ? 'Balcão' : `Guarda-sol ${c.guardaSol}`
  if (c.metodo === 'PIX') return `${local} · ${c.cliente} avisou que pagou · ${reais(c.saldoCentavos)} · Pix: conferir no app do banco`
  const forma = c.metodo === 'DINHEIRO' ? 'Dinheiro' : 'Cartão'
  const troco = c.metodo === 'DINHEIRO' && c.trocoParaCentavos !== null ? ` (troco para ${reais(c.trocoParaCentavos)})` : ''
  return `${local} · ${c.cliente} está indo pagar · ${reais(c.saldoCentavos)} · ${forma}${troco}`
}

/** Uma busca por vez: respostas fora de ordem nunca voltam a lista para um estado antigo. */
export const recarregar = umaPorVez(buscarPagamentos)

async function buscarPagamentos (): Promise<void> {
  if (!ativa) return
  const destaBusca = geracao
  try {
    const nova = await pagamentos(pagina)
    if (!ativa || destaBusca !== geracao) return
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
    // O toast some em segundos: o "indo pagar" também fica na tela até a conta ser recebida (ou o aviso mudar)
    sincronizarAvisos('pagamento', nova.aguardando
      .filter((c) => c.avisoEm !== null)
      .map((c) => ({ chave: `pagamento:${c.comandaId}@${c.avisoEm}`, tipo: 'pagamento' as const, texto: textoDoAviso(c), aba: 'pagamentos' as const })))
  } catch (e) {
    if (descartada(e) || !ativa || destaBusca !== geracao) return
    // 401: o app já volta para o login (evento da api). Sem sinal: segue com a última lista e tenta de novo.
    falhou = !(e instanceof ApiError && e.status === 401)
  }
  avisarOuvintes()
}

export function mudarPagina (nova: number): void {
  geracao++
  pagina = Math.max(0, nova)
  void recarregar()
}

/** Várias mudanças seguidas viram uma busca só. */
export function agendarBusca (): void {
  if (!ativa) return
  window.clearTimeout(timerBusca)
  timerBusca = window.setTimeout(() => { void recarregar() }, REDESENHO_MS)
}

export function iniciarPagamentos (): void {
  ativa = true
  void recarregar()
}

export function pararPagamentos (): void {
  pagina = 0
  ativa = false
  geracao++
  window.clearTimeout(timerBusca)
  dados = null
  falhou = false
  primeiraBusca = true
  vistos.clear()
}
