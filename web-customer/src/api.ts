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
  cardapio: Secao[]
}

export interface ItemEnvio {
  produtoId: string
  quantidade: number
  observacao: string
}

export interface PedidoEnvio {
  id: string
  itens: ItemEnvio[]
}

export interface ItemPedido {
  produtoId: string
  nome: string
  precoCentavos: number
  quantidade: number
  observacao: string | null
  subtotalCentavos: number
}

export interface Pedido {
  id: string
  numero: number
  status: string
  hora: string
  itens: ItemPedido[]
  totalCentavos: number
}

export class ApiError extends Error {
  constructor (
    readonly status: number,
    readonly codigo: string,
    mensagem: string,
    /** Produtos que não dá para pedir (erro "indisponivel"). */
    readonly produtos: string[] = []
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

async function chamar<T> (method: 'GET' | 'POST', token: string, caminho: string, body?: unknown): Promise<T> {
  let res: Response
  try {
    res = await fetch(`/api/c/${encodeURIComponent(token)}${caminho}`, {
      method,
      credentials: 'omit',
      cache: 'no-store',
      headers: {
        Accept: 'application/json',
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {})
      },
      body: body !== undefined ? JSON.stringify(body) : undefined
    })
  } catch {
    throw new ApiError(0, 'rede', 'Sem sinal. Confira a internet e tente de novo.')
  }
  if (res.ok) return await res.json() as T
  try {
    const erro = await res.json() as { erro?: string, mensagem?: string, produtos?: string[] }
    throw new ApiError(res.status, erro.erro ?? 'erro', erro.mensagem ?? 'Algo deu errado. Tente de novo.', erro.produtos)
  } catch (e) {
    if (e instanceof ApiError) throw e
    throw new ApiError(res.status, 'erro', 'Algo deu errado. Tente de novo.')
  }
}

export async function comanda (token: string): Promise<Comanda> {
  return await chamar<Comanda>('GET', token, '')
}

/** Uma tentativa de envio. Quem chama repete com o MESMO `id` até dar certo: o servidor não duplica. */
export async function enviarPedido (token: string, pedido: PedidoEnvio): Promise<Pedido> {
  return await chamar<Pedido>('POST', token, '/pedidos', pedido)
}

/** UUID v4 com crypto.getRandomValues (o crypto.randomUUID não existe fora de HTTPS, ex.: http://IP-DO-PC). */
export function novoId (): string {
  const b = crypto.getRandomValues(new Uint8Array(16))
  b[6] = (b[6] & 0x0f) | 0x40
  b[8] = (b[8] & 0x3f) | 0x80
  const hex = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** 12300 → "R$ 123,00" (o mesmo formato do servidor: Money.format). */
export function reais (centavos: number): string {
  const inteiro = String(Math.floor(centavos / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, '.')
  return `R$ ${inteiro},${String(centavos % 100).padStart(2, '0')}`
}
