// A fila do quiosque, guardada fora das telas: a conexão em tempo real (SSE) fica aberta enquanto o app está
// logado, então o número de pedidos na aba e o aviso de pedido novo funcionam em qualquer aba.
//
// O servidor só avisa "um pedido mudou"; o app busca a fila de novo (/api/staff/fila). Assim a tela sempre mostra
// o que o servidor tem, e se um aviso se perder a próxima busca corrige. Sem conexão, busca a cada 15 s.
import { ApiError, fila, type Fila, type PedidoFila } from './api'
import { avisarAtraso, avisarPedidoNovo } from './som'
import { agendarBusca as agendarPagamentos } from './pagamentos-store'
import { avisar } from './toast'

type Ouvinte = () => void

const REDESENHO_MS = 250
const RESERVA_MS = 15_000
const DESTAQUE_MS = 20_000

let dados: Fila | null = null
let diferencaMs = 0
let conectado = false
let falhou = false
let origem: EventSource | null = null
let timerBusca: number | undefined
let timerReserva: number | undefined
let primeiraBusca = true
const vistos = new Set<string>()
const chegadas = new Map<string, number>()
const ouvintes = new Set<Ouvinte>()

export function estado (): { dados: Fila | null, conectado: boolean, falhou: boolean } {
  return { dados, conectado, falhou }
}

/** O horário do servidor agora (o relógio do celular pode estar errado; a diferença vem da última resposta). */
export function agoraDoServidor (): number {
  return Date.now() + diferencaMs
}

/** Pedido que acabou de chegar: ganha destaque por alguns segundos. */
export function ehNovo (id: string): boolean {
  const quando = chegadas.get(id)
  return quando !== undefined && Date.now() - quando < DESTAQUE_MS
}

export function observar (ouvinte: Ouvinte): () => void {
  ouvintes.add(ouvinte)
  return () => { ouvintes.delete(ouvinte) }
}

function avisarOuvintes (): void {
  ouvintes.forEach((o) => { o() })
}

export async function recarregar (): Promise<void> {
  try {
    const nova = await fila()
    diferencaMs = Date.parse(nova.agora) - Date.now()
    const recebidos = nova.pedidos.filter((p: PedidoFila) => p.status === 'RECEBIDO' && !vistos.has(p.id))
    nova.pedidos.forEach((p) => vistos.add(p.id))
    // A primeira busca só mostra o que já estava lá: não toca som por pedido antigo
    if (!primeiraBusca && recebidos.length > 0) {
      recebidos.forEach((p) => chegadas.set(p.id, Date.now()))
      avisarPedidoNovo()
    }
    primeiraBusca = false
    dados = nova
    falhou = false
  } catch (e) {
    // 401: o app já volta para o login (evento da api). Sem sinal: segue com a última fila e tenta de novo.
    falhou = !(e instanceof ApiError && e.status === 401)
  }
  avisarOuvintes()
}

/** Várias mudanças seguidas viram uma busca só. */
function agendarBusca (): void {
  window.clearTimeout(timerBusca)
  timerBusca = window.setTimeout(() => { void recarregar() }, REDESENHO_MS)
}

function conectar (): void {
  if (origem !== null) return
  const es = new EventSource('/api/staff/eventos')
  origem = es
  es.onopen = () => {
    conectado = true
    // Pode ter perdido avisos enquanto estava fora do ar
    void recarregar()
    agendarPagamentos()
  }
  es.addEventListener('fila', agendarBusca)
  es.addEventListener('ressincronizar', () => { agendarBusca(); agendarPagamentos() })
  // Uma conta mudou (o cliente fechou ou avisou que vai pagar, alguém recebeu): a aba Pagamentos busca de novo
  es.addEventListener('conta', agendarPagamentos)
  // Um pedido passou do tempo de espera da barraca: alerta no app aberto (o push cobre o app fechado)
  es.addEventListener('atraso', (ev) => {
    try {
      const { numero } = JSON.parse((ev as MessageEvent<string>).data) as { numero: number }
      avisar(`O pedido nº ${numero} está parado há muito tempo.`, true)
    } catch { /* aviso sem número: só toca o alerta */ }
    avisarAtraso()
    agendarBusca()
  })
  es.onerror = () => {
    conectado = false
    avisarOuvintes()
    // O navegador reconecta sozinho. Se a conexão foi recusada de vez (ex.: sessão acabou), ele fecha: a busca
    // seguinte devolve 401 e o app volta ao login.
    if (es.readyState === EventSource.CLOSED) {
      origem = null
      window.setTimeout(conectar, RESERVA_MS)
    }
  }
}

export function iniciarFila (): void {
  conectar()
  void recarregar()
  window.clearInterval(timerReserva)
  // Rede de segurança: sem a conexão em tempo real, a fila continua se atualizando
  timerReserva = window.setInterval(() => { if (!conectado) void recarregar() }, RESERVA_MS)
}

export function pararFila (): void {
  origem?.close()
  origem = null
  conectado = false
  window.clearTimeout(timerBusca)
  window.clearInterval(timerReserva)
  dados = null
  primeiraBusca = true
  vistos.clear()
  chegadas.clear()
}

/** Pedidos que o quiosque ainda precisa atender (o número que aparece na aba). */
export function pendentes (): number {
  return dados?.pedidos.length ?? 0
}
