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

export class ApiError extends Error {
  constructor (
    readonly status: number,
    readonly codigo: string,
    mensagem: string,
    readonly tentativasRestantes?: number
  ) {
    super(mensagem)
  }
}

let csrf = ''

/** Avisado quando a sessão acaba (ex.: o dono desativou o atendente): o app volta ao login. */
export const eventos = new EventTarget()

async function lerErro (res: Response): Promise<ApiError> {
  try {
    const body = await res.json() as { erro?: string, mensagem?: string, tentativasRestantes?: number }
    return new ApiError(res.status, body.erro ?? 'erro', body.mensagem ?? 'Algo deu errado. Tente de novo.', body.tentativasRestantes)
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
