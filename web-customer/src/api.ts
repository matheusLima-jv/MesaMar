import { contrato } from './contratos'
import { registro, texto, lista, uuid } from '../../web-shared/src/validacao'
export { novoId, reais, lerReais } from '../../web-shared/src/valores'
// Cliente da API da página do cliente (/api/c/{token}). Sem cookies e sem sessão: o token que está
// no endereço da página (/c/{token}) é a credencial de tudo.

export interface Produto {
  id: string
  nome: string
  descricao: string | null
  precoCentavos: number
  preco: string
  esgotado: boolean
}

export interface Secao {
  id: string
  nome: string
  produtos: Produto[]
}

/** Por que a comanda não aceita pedido agora (pausado, conta fechando…), com a mensagem pronta. */
export interface Bloqueio {
  codigo: string
  mensagem: string
}

export interface Comanda {
  barraca: string
  nome: string
  guardaSol: number | null
  bloqueio: Bloqueio | null
  /** Quanto a conta já soma (itens não cancelados), em centavos. */
  saldoCentavos: number
  /** FINAL: paga ao fechar a conta. POR_PEDIDO: o pedido só entra na fila depois de pago. */
  modoPagamento: 'FINAL' | 'POR_PEDIDO'
  /** Barraca de treino: a página mostra um aviso de que nada ali é de verdade. */
  demo: boolean
  cardapio: Secao[]
  /**
   * Preenchido só quando a resposta veio do cache do aparelho (sem sinal): quando ela foi salva (ms desde 1970).
   * A página avisa que o cardápio pode estar desatualizado, em vez de parecer confirmado agora pelo servidor.
   */
  salvoEm?: number
}

export interface ItemEnvio {
  produtoId: string
  quantidade: number
  observacao: string
  /** O preço que o cliente viu no cardápio: se mudou, o servidor recusa ("preco-mudou") para ele conferir. */
  precoCentavos?: number
}

export interface PedidoEnvio {
  id: string
  itens: ItemEnvio[]
  /**
   * Já houve uma tentativa sem resposta. No reenvio, se a equipe já lançou um pedido igual para esta comanda (o cliente
   * chamou um atendente enquanto a página tentava), o servidor devolve o pedido da equipe em vez de criar outro.
   */
  reenvio?: boolean
}

export interface ItemPedido {
  id: string
  produtoId: string
  nome: string
  precoCentavos: number
  quantidade: number
  observacao: string | null
  subtotalCentavos: number
  /** Item que o quiosque cancelou: continua na lista, mas não entra no total. */
  cancelado: boolean
  /** Por que o quiosque cancelou (ex.: "Esgotou"). */
  motivoCancelamento: string | null
}

export interface Pedido {
  id: string
  numero: number
  status: string
  hora: string
  itens: ItemPedido[]
  totalCentavos: number
  /** Quando o pedido todo foi cancelado, por quê. */
  motivoCancelamento: string | null
}

export interface MeusPedidos {
  saldoCentavos: number
  pedidos: Pedido[]
}

export type Metodo = 'PIX' | 'DINHEIRO' | 'CARTAO'

export interface Pix {
  /** O "copia e cola" do Pix; o mesmo texto vira o QR. */
  codigo: string
  chave: string
  nomeRecebedor: string
  cidade: string
  valorCentavos: number
}

export interface PagamentoFeito {
  metodo: Metodo
  rotulo: string
  valorCentavos: number
  /** Dinheiro: quanto o cliente entregou, para mostrar o troco. */
  valorRecebidoCentavos: number | null
  hora: string
}

/** O extrato da conta e em que ponto do fechamento ela está. */
export interface Conta {
  status: 'ABERTA' | 'AGUARDANDO_PAGAMENTO' | 'FECHADA' | 'CANCELADA' | 'BLOQUEADA'
  pedidoCentavos: number
  pagoCentavos: number
  saldoCentavos: number
  metodo: Metodo | null
  trocoParaCentavos: number | null
  /** Hora em que o cliente avisou o quiosque (dinheiro e cartão: ao fechar; Pix: no "já paguei"). */
  avisadoEm: string | null
  pix: Pix | null
  aceitaPix: boolean
  pedidos: Pedido[]
  pagamentos: PagamentoFeito[]
  /** O quiosque encerrou a conta sem receber tudo: não é "Conta paga". */
  encerradaComSaldo?: boolean
}

export class ApiError extends Error {
  constructor (
    readonly status: number,
    readonly codigo: string,
    mensagem: string,
    /** Produtos que não dá para pedir (erro "indisponivel"). */
    readonly produtos: string[] = [],
    /** 429: quanto o servidor pediu para esperar (cabeçalho Retry-After), em ms. */
    readonly esperaMs?: number
  ) {
    super(mensagem)
  }

  /** Sem rede, servidor engasgado ou limite de uso: vale tentar de novo (o envio é idempotente). */
  get temporario (): boolean {
    return this.status === 0 || this.status >= 500 || this.status === 408 || this.status === 429
  }
}

/** O token é a parte do endereço depois de /c/ (o servidor entrega esta mesma página para qualquer token). */
export function tokenDaUrl (): string | null {
  const m = /^\/c\/([A-Za-z0-9_-]+)\/?$/.exec(location.pathname)
  return m === null ? null : m[1]
}

/**
 * Prazo de cada chamada: em sinal ruim, uma requisição pode ficar pendurada por minutos. Passou do prazo, vira "sem
 * sinal" (status 0): leitura tenta de novo depois, e o pedido é reenviado com o MESMO id (o servidor não duplica).
 */
const PRAZO_LEITURA_MS = 15_000
const PRAZO_ACAO_MS = 20_000

async function chamar<T> (method: 'GET' | 'POST', token: string, caminho: string, body?: unknown, aoResponder?: (res: Response) => void): Promise<T> {
  const controle = new AbortController()
  const prazo = window.setTimeout(() => { controle.abort() }, method === 'GET' ? PRAZO_LEITURA_MS : PRAZO_ACAO_MS)
  const semSinal = (): ApiError => new ApiError(0, 'rede', 'Sem sinal. Confira a internet e tente de novo.')
  try {
    let res: Response
    try {
      res = await fetch(`/api/c/${encodeURIComponent(token)}${caminho}`, {
        method,
        credentials: 'omit',
        cache: 'no-store',
        signal: controle.signal,
        headers: {
          Accept: 'application/json',
          ...(body !== undefined ? { 'Content-Type': 'application/json' } : {})
        },
        body: body !== undefined ? JSON.stringify(body) : undefined
      })
    } catch {
      throw semSinal()
    }
    if (res.ok) {
      try {
        const bruto: unknown = await res.json()
        let dados: T
        try { dados = contrato<T>(caminho, method, bruto) } catch { throw new ApiError(502, 'contrato', 'A resposta veio incompleta ou incompatível. Confira a operação antes de repetir.') }
        aoResponder?.(res)
        return dados
      } catch (e) {
        if (e instanceof ApiError) throw e
        // O servidor respondeu 2xx, mas o corpo não chegou inteiro (sinal caiu no meio). O pedido pode ter sido gravado:
        // trata como falha de rede, para quem chama tentar de novo com o MESMO id (o servidor devolve o que já existe).
        throw semSinal()
      }
    }
    const retry = Number(res.headers.get('Retry-After'))
    const esperaMs = res.status === 429 && Number.isFinite(retry) && retry > 0 ? retry * 1000 : undefined
    try {
      const bruto: unknown = await res.json()
      const erro = registro(bruto) ? bruto : {}
      throw new ApiError(res.status, texto(100, 1)(erro.erro) ? String(erro.erro) : 'erro', texto(2000, 1)(erro.mensagem) ? String(erro.mensagem) : 'Algo deu errado. Tente de novo.', lista(uuid, 30)(erro.produtos) ? erro.produtos as string[] : [], esperaMs)
    } catch (e) {
      if (e instanceof ApiError) throw e
      throw new ApiError(res.status, 'erro', 'Algo deu errado. Tente de novo.', [], esperaMs)
    }
  } finally {
    window.clearTimeout(prazo)
  }
}

export async function comanda (token: string): Promise<Comanda> {
  let salvoEm: number | undefined
  const dados = await chamar<Comanda>('GET', token, '', undefined, (res) => {
    // O service worker marca as cópias que guarda: se veio esta marca, a resposta é do cache, não do servidor agora
    const marca = Number(res.headers.get('X-MesaMar-Salvo-Em'))
    salvoEm = Number.isFinite(marca) && marca > 0 ? marca : undefined
  })
  return { ...dados, salvoEm }
}

/** Os pedidos da comanda (do mais novo para o mais antigo) e o saldo. */
export async function meusPedidos (token: string): Promise<MeusPedidos> {
  return await chamar<MeusPedidos>('GET', token, '/pedidos')
}

/** "Reportar problema": o texto e o contexto que a página conhece (tela, conexão). */
export async function reportarProblema (token: string, mensagem: string, tela: string, conexao: string): Promise<void> {
  await chamar<unknown>('POST', token, '/problemas', { mensagem, tela, conexao })
}

/** Uma tentativa de envio. Quem chama repete com o MESMO `id` até dar certo: o servidor não duplica. */
export async function enviarPedido (token: string, pedido: PedidoEnvio): Promise<Pedido> {
  return await chamar<Pedido>('POST', token, '/pedidos', pedido)
}

/** O extrato e o ponto do fechamento da conta. */
export async function conta (token: string): Promise<Conta> {
  return await chamar<Conta>('GET', token, '/conta')
}

/** "Fechar conta": a comanda passa a aguardar o pagamento. Repetir troca a forma de pagamento. */
export async function fecharConta (token: string, metodo: Metodo, trocoParaCentavos: number | null): Promise<Conta> {
  return await chamar<Conta>('POST', token, '/conta/fechar', { metodo, trocoParaCentavos })
}

/** Pix: "já paguei". */
export async function pixPago (token: string): Promise<Conta> {
  return await chamar<Conta>('POST', token, '/conta/pix-pago', {})
}

/** "Voltar e pedir mais": só enquanto nenhum pagamento foi confirmado. */
export async function reabrirConta (token: string): Promise<Conta> {
  return await chamar<Conta>('POST', token, '/conta/reabrir', {})
}

/**
 * Tempo real: o servidor avisa quando um pedido ou a conta da comanda muda (status, item cancelado, pagamento) e a página busca de novo.
 * `aoMudar` roda a cada aviso; `aoEstado(true)` quando conectou e `aoEstado(false)` quando caiu (o navegador reconecta sozinho).
 * Devolve a função que encerra a conexão.
 */
export function ouvirComanda (token: string, aoMudar: () => void, aoEstado: (conectado: boolean) => void): () => void {
  let es: EventSource | null = null
  let timer: number | undefined
  let fechado = false
  const abrir = (): void => {
    if (fechado) return
    es = new EventSource(`/api/c/${encodeURIComponent(token)}/eventos`)
    es.onopen = () => { aoEstado(true) }
    es.addEventListener('pedido', aoMudar)
    // A conta mudou (o quiosque recebeu um pagamento, bloqueou a comanda…)
    es.addEventListener('conta', aoMudar)
    // O quiosque mudou o cardápio (preço, esgotado, produto novo)
    es.addEventListener('cardapio', aoMudar)
    es.addEventListener('ressincronizar', aoMudar)
    es.onerror = () => {
      aoEstado(false)
      // Recusada de vez (muito uso, link inválido): o navegador não tenta mais. Tenta de novo daqui a pouco.
      if (es?.readyState === EventSource.CLOSED) {
        es = null
        timer = window.setTimeout(abrir, 30_000)
      }
    }
  }
  abrir()
  return () => {
    fechado = true
    window.clearTimeout(timer)
    es?.close()
  }
}
