// Cliente da API do quiosque (/api/staff). Sessão por cookie; POSTs levam o token CSRF.

export type Papel = 'DONO' | 'ATENDENTE'

export interface Sessao {
  logado: boolean
  csrf: string
  usuario?: { nome: string, papel: Papel }
  barraca?: { nome: string, codigo: string, praia: string }
}

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

export interface AtendenteOpcao { id: string, nome: string }

/** Comanda de um cliente que fez check-in. `link` é o que vai no QR. */
export interface Comanda {
  id: string
  numeroChegada: number
  nome: string
  guardaSol: number | null
  whatsapp: string | null
  whatsappExibicao: string | null
  link: string
  hora: string
  status: string
}

export interface GuardaSolOpcao { id: string, numero: number, clientes: number }

export interface NovoCliente {
  id: string
  nome: string
  guardaSolId: string | null
  whatsapp: string
  consentimento: boolean
}

export type StatusPedido = 'AGUARDANDO_PAGAMENTO' | 'RECEBIDO' | 'EM_PREPARO' | 'ENTREGUE' | 'CANCELADO'
export type Motivo = 'ESGOTOU' | 'CLIENTE_DESISTIU' | 'OUTRO'

export interface ItemFila {
  id: string
  nome: string
  quantidade: number
  observacao: string | null
  cancelado: boolean
  motivoCancelamento: string | null
}

/** Um pedido na fila. `criadoEm` é o horário do SERVIDOR: a ordem e a espera nunca dependem do relógio do celular. */
export interface PedidoFila {
  id: string
  numero: number
  status: StatusPedido
  criadoEm: string
  guardaSol: number | null
  cliente: string
  itens: ItemFila[]
}

export interface Fila {
  /** Horário do servidor no momento da resposta. */
  agora: string
  tempoAlertaMin: number
  /** O quiosque está sem receber pedidos pelo celular dos clientes. */
  pedidosPausados: boolean
  mensagemPausa: string | null
  pedidos: PedidoFila[]
}

export interface Pausa {
  pedidosPausados: boolean
  mensagemPausa: string | null
}

export interface PushConfig {
  /** Falso enquanto o servidor não tem as chaves VAPID. */
  habilitado: boolean
  chavePublica: string | null
}

export class ApiError extends Error {
  constructor (
    readonly status: number,
    readonly codigo: string,
    mensagem: string,
    readonly tentativasRestantes?: number,
    /** Erros por campo (400 de validação): { nome: 'Digite o nome…' } */
    readonly campos: Record<string, string> = {}
  ) {
    super(mensagem)
  }
}

let csrf = ''

/** Avisado quando a sessão acaba (ex.: o dono desativou o atendente): o app volta ao login. */
export const eventos = new EventTarget()

async function lerErro (res: Response): Promise<ApiError> {
  try {
    const body = await res.json() as { erro?: string, mensagem?: string, tentativasRestantes?: number, campos?: Record<string, string> }
    return new ApiError(res.status, body.erro ?? 'erro', body.mensagem ?? 'Algo deu errado. Tente de novo.', body.tentativasRestantes, body.campos)
  } catch {
    return new ApiError(res.status, 'erro', 'Algo deu errado. Tente de novo.')
  }
}

async function chamar<T> (method: 'GET' | 'POST', path: string, body?: unknown, tentarDeNovo = true): Promise<T> {
  let res: Response
  try {
    res = await fetch(`/api/staff${path}`, {
      method,
      credentials: 'same-origin',
      cache: 'no-store',
      headers: {
        Accept: 'application/json',
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(method === 'POST' ? { 'X-CSRF-TOKEN': csrf } : {})
      },
      body: body !== undefined ? JSON.stringify(body) : undefined
    })
  } catch {
    throw new ApiError(0, 'rede', 'Sem conexão com o servidor. Confira a internet e tente de novo.')
  }

  if (res.status === 204) return undefined as T
  if (res.ok) {
    const dados = await res.json() as T & { csrf?: string }
    if (typeof dados === 'object' && dados !== null && typeof dados.csrf === 'string') csrf = dados.csrf
    return dados
  }

  const erro = await lerErro(res)
  // Token CSRF vencido (sessão expirou no servidor): pega um novo e repete uma vez.
  if (erro.status === 403 && erro.codigo === 'csrf' && tentarDeNovo) {
    await sessao()
    return await chamar<T>(method, path, body, false)
  }
  if (erro.status === 401 && erro.codigo === 'sessao') eventos.dispatchEvent(new Event('sessao-expirada'))
  throw erro
}

export async function sessao (): Promise<Sessao> {
  return await chamar<Sessao>('GET', '/sessao')
}

export async function entrarDono (email: string, senha: string): Promise<Sessao> {
  return await chamar<Sessao>('POST', '/entrar/dono', { email, senha })
}

export async function atendentes (codigo: string): Promise<AtendenteOpcao[]> {
  return await chamar<AtendenteOpcao[]>('GET', `/barracas/${encodeURIComponent(codigo.trim().toLowerCase())}/atendentes`)
}

export async function entrarAtendente (codigo: string, atendenteId: string, pin: string): Promise<Sessao> {
  return await chamar<Sessao>('POST', '/entrar/atendente', { codigo: codigo.trim().toLowerCase(), atendenteId, pin })
}

export async function sair (): Promise<void> {
  await chamar<void>('POST', '/sair')
}

export async function cardapio (): Promise<Secao[]> {
  return await chamar<Secao[]>('GET', '/cardapio')
}

export async function definirEsgotado (produtoId: string, esgotado: boolean): Promise<Produto> {
  return await chamar<Produto>('POST', `/produtos/${produtoId}/esgotado`, { esgotado })
}

export async function clientes (): Promise<Comanda[]> {
  return await chamar<Comanda[]>('GET', '/clientes')
}

export async function guardaSois (): Promise<GuardaSolOpcao[]> {
  return await chamar<GuardaSolOpcao[]>('GET', '/guarda-sois')
}

/**
 * Check-in. O `id` nasce no celular: se a rede cair no meio, reenviar o mesmo id devolve a mesma
 * comanda em vez de criar outra. Sem rede, tenta de novo sozinho mais 2 vezes antes de desistir.
 */
export async function novoCliente (c: NovoCliente): Promise<Comanda> {
  for (const espera of [1000, 3000]) {
    try {
      return await chamar<Comanda>('POST', '/clientes', c)
    } catch (e) {
      if (!(e instanceof ApiError) || e.status !== 0) throw e
      await new Promise((resolve) => setTimeout(resolve, espera))
    }
  }
  return await chamar<Comanda>('POST', '/clientes', c)
}

/** A fila: pedidos pendentes, o mais antigo primeiro. */
export async function fila (): Promise<Fila> {
  return await chamar<Fila>('GET', '/fila')
}

/** "Em preparo" ou "Entregue". Repetir a mesma ação não faz mal. */
export async function mudarStatus (pedidoId: string, status: 'EM_PREPARO' | 'ENTREGUE'): Promise<PedidoFila> {
  return await chamar<PedidoFila>('POST', `/pedidos/${pedidoId}/status`, { status })
}

export async function cancelarPedido (pedidoId: string, motivo: Motivo, detalhe: string): Promise<PedidoFila> {
  return await chamar<PedidoFila>('POST', `/pedidos/${pedidoId}/cancelar`, { motivo, detalhe })
}

export async function cancelarItem (pedidoId: string, itemId: string, motivo: Motivo, detalhe: string): Promise<PedidoFila> {
  return await chamar<PedidoFila>('POST', `/pedidos/${pedidoId}/itens/${itemId}/cancelar`, { motivo, detalhe })
}

/** "Pausar pedidos" (com um recado opcional para os clientes) e "Voltar a receber". */
export async function pausar (pausado: boolean, mensagem = ''): Promise<Pausa> {
  return await chamar<Pausa>('POST', '/pausa', { pausado, mensagem })
}

export async function pushConfig (): Promise<PushConfig> {
  return await chamar<PushConfig>('GET', '/push')
}

export async function inscreverPush (inscricao: unknown): Promise<void> {
  await chamar<void>('POST', '/push/inscrever', inscricao)
}

export async function cancelarPush (endpoint: string): Promise<void> {
  await chamar<void>('POST', '/push/cancelar', { endpoint })
}

export async function testarPush (endpoint: string): Promise<void> {
  await chamar<void>('POST', '/push/testar', { endpoint })
}

/** UUID v4 com crypto.getRandomValues (o crypto.randomUUID não existe fora de HTTPS, ex.: http://IP-DO-PC). */
export function novoId (): string {
  const b = crypto.getRandomValues(new Uint8Array(16))
  b[6] = (b[6] & 0x0f) | 0x40
  b[8] = (b[8] & 0x3f) | 0x80
  const hex = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
