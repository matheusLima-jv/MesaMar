import { definirContextoPendencias } from './pendencias'
import { drenar, esperaSincronia, guardarCache, lerCache, listarOperacoes, mudancasOffline, preservar, removerOperacao, tokenLocal, type Operacao } from './fila-offline'
import { comandaRegra, sessaoRegra } from './contratos'
import { conferir, lerJson, novoCliente as novoClienteRegra } from '../../web-shared/src/validacao'
import { contrato } from './contratos'
import { registro, texto, inteiro } from '../../web-shared/src/validacao'
export { novoId, reais, lerReais } from '../../web-shared/src/valores'
// Cliente da API do quiosque (/api/staff). Sessão por cookie; POSTs levam o token CSRF.

export type Papel = 'DONO' | 'ATENDENTE'

export interface Sessao {
  logado: boolean
  csrf: string
  usuario?: { id: string, nome: string, papel: Papel }
  barraca?: { nome: string, codigo: string, praia: string, demo?: boolean }
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
  token?: string
  nome: string
  guardaSolId: string | null
  whatsapp: string
  consentimento: boolean
  /** A versão do texto de consentimento que estava na tela (docs/consentimento.md). */
  consentimentoVersao?: string
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

export type Metodo = 'PIX' | 'DINHEIRO' | 'CARTAO'

/** Uma comanda vista pela aba Pagamentos: quanto deve, como o cliente disse que vai pagar e desde quando. */
export interface ContaAPagar {
  comandaId: string
  cliente: string
  guardaSol: number | null
  status: 'ABERTA' | 'AGUARDANDO_PAGAMENTO' | 'BLOQUEADA' | 'FECHADA'
  pedidoCentavos: number
  pagoCentavos: number
  saldoCentavos: number
  metodo: Metodo | null
  /** Dinheiro: "vou pagar com R$ …", para separar o troco. */
  trocoParaCentavos: number | null
  /** Horário do SERVIDOR em que o cliente avisou. `null` se ainda não avisou (no Pix, é o "já paguei"). */
  avisoEm: string | null
  abertaEm: string
  /** Pedidos que só entram na fila depois de pagos (modo "pagar a cada pedido"). */
  pedidosAguardando: number
  saldoPerdoadoCentavos: number | null
}

export interface Pagamentos {
  pagina: number
  temMais: boolean
  totalAguardando: number
  totalSaldoAbertoCentavos: number
  /** Horário do servidor no momento da resposta. */
  agora: string
  /** Quem tocou em "fechar a conta", por ordem de aviso. */
  aguardando: ContaAPagar[]
  /** Contas abertas ou bloqueadas com saldo, as maiores primeiro. */
  abertas: ContaAPagar[]
}

export interface Confirmacao {
  conta: ContaAPagar
  pagamento: { id: string, metodo: Metodo, valorCentavos: number, valorRecebidoCentavos: number | null, trocoCentavos: number }
  /** A conta chegou a zero e se encerrou. */
  fechada: boolean
}

export class ApiError extends Error {
  constructor (
    readonly status: number,
    readonly codigo: string,
    mensagem: string,
    readonly tentativasRestantes?: number,
    /** Erros por campo (400 de validação): { nome: 'Digite o nome…' } */
    readonly campos: Record<string, string> = {},
    /** 429: quanto o servidor pediu para esperar (cabeçalho Retry-After), em ms. */
    readonly esperaMs?: number
  ) {
    super(mensagem)
  }

  get temporario (): boolean { return !this.descartada && (this.status === 0 || this.status >= 500 || this.status === 408 || this.status === 429) }

  /**
   * A chamada foi descartada porque a sessão local acabou (saiu, trocou de pessoa) enquanto ela estava em andamento.
   * Ninguém mostra este erro: a tela que pediu já não existe.
   */
  get descartada (): boolean {
    return this.codigo === 'descartada'
  }
}

/** Erro que não deve aparecer na tela (chamada de uma sessão que já acabou). */
export function descartada (e: unknown): boolean {
  return e instanceof ApiError && e.descartada
}

let csrf = ''

/** Avisado quando a sessão acaba (ex.: o dono desativou o atendente): o app volta ao login. */
export const eventos = new EventTarget()

/**
 * Prazo de cada chamada. Leitura que passa do prazo vira "sem conexão" e a tela oferece tentar de novo. Ação (POST)
 * que passa do prazo tem resultado desconhecido: vira o mesmo erro de rede (status 0), e quem chama reenvia com o
 * MESMO id, então o servidor não registra duas vezes.
 */
const PRAZO_LEITURA_MS = 15_000
const PRAZO_ACAO_MS = 20_000

/** Tudo o que está em andamento pertence a esta sessão local. Sair aborta e descarta (nada repopula telas nem stores). */
let sessaoLocal = new AbortController()

export function encerrarSessaoLocal (): void {
  sessaoLocal.abort()
  sessaoLocal = new AbortController()
}

async function lerErro (res: Response): Promise<ApiError> {
  const retry = Number(res.headers.get('Retry-After'))
  const esperaMs = res.status === 429 && Number.isFinite(retry) && retry > 0 ? retry * 1000 : undefined
  try {
    const bruto: unknown = await res.json()
    const body = registro(bruto) ? bruto : {}
    const campos = registro(body.campos) && Object.keys(body.campos).length <= 30 && Object.values(body.campos).every(texto(2000)) ? body.campos as Record<string, string> : {}
    return new ApiError(res.status, texto(100, 1)(body.erro) ? String(body.erro) : 'erro', texto(2000, 1)(body.mensagem) ? String(body.mensagem) : 'Algo deu errado. Tente de novo.', inteiro(0, 100)(body.tentativasRestantes) ? Number(body.tentativasRestantes) : undefined, campos, esperaMs)
  } catch {
    return new ApiError(res.status, 'erro', 'Algo deu errado. Tente de novo.', undefined, {}, esperaMs)
  }
}

async function chamarRede<T> (method: 'GET' | 'POST', path: string, body?: unknown, tentarDeNovo = true): Promise<T> {
  const daSessao = sessaoLocal.signal
  const descartar = (): ApiError => new ApiError(0, 'descartada', 'Ação descartada.')
  if (daSessao.aborted) throw descartar()
  const controle = new AbortController()
  let estourou = false
  const prazo = window.setTimeout(() => { estourou = true; controle.abort() }, method === 'GET' ? PRAZO_LEITURA_MS : PRAZO_ACAO_MS)
  const aoEncerrar = (): void => { controle.abort() }
  daSessao.addEventListener('abort', aoEncerrar)
  // Falha de rede, prazo estourado ou corpo cortado: o que aconteceu no servidor é desconhecido
  const semResposta = (): ApiError => {
    if (daSessao.aborted) return descartar()
    if (estourou) return new ApiError(0, 'tempo', 'O servidor demorou demais para responder. Confira o sinal e tente de novo (nada será registrado duas vezes).')
    return new ApiError(0, 'rede', 'Sem conexão com o servidor. Confira a internet e tente de novo.')
  }
  try {
    let res: Response
    try {
      res = await fetch(`/api/staff${path}`, {
        method,
        credentials: 'same-origin',
        cache: 'no-store',
        signal: controle.signal,
        headers: {
          Accept: 'application/json',
          ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
          ...(method === 'POST' ? { 'X-CSRF-TOKEN': csrf } : {})
        },
        body: body !== undefined ? JSON.stringify(body) : undefined
      })
    } catch {
      throw semResposta()
    }

    if (res.status === 204) {
      if (daSessao.aborted) throw descartar()
      return undefined as T
    }
    if (res.ok) {
      let dados: T & { csrf?: string }
      try {
        const bruto: unknown = await res.json()
        try { dados = contrato<T & { csrf?: string }>(path, method, bruto) } catch { throw new ApiError(502, 'contrato', 'Resposta incompatível. Confira a operação antes de repetir.') }
      } catch (e) {
        if (e instanceof ApiError) throw e
        // 2xx com o corpo cortado (o sinal caiu no meio): a ação pode ter sido gravada. Quem chama repete com o mesmo id.
        throw semResposta()
      }
      if (daSessao.aborted) throw descartar()
      if (typeof dados === 'object' && dados !== null && typeof dados.csrf === 'string') csrf = dados.csrf
      return dados
    }

    const erro = await lerErro(res)
    if (daSessao.aborted) throw descartar()
    // Token CSRF vencido (sessão expirou no servidor): pega um novo e repete uma vez.
    if (erro.status === 403 && erro.codigo === 'csrf' && tentarDeNovo) {
      await chamarRede<Sessao>('GET', '/sessao')
      if (daSessao.aborted) throw descartar()
      return await chamarRede<T>(method, path, body, false)
    }
    if (erro.status === 401 && erro.codigo === 'sessao') eventos.dispatchEvent(new Event('sessao-expirada'))
    throw erro
  } finally {
    window.clearTimeout(prazo)
    daSessao.removeEventListener('abort', aoEncerrar)
  }
}

let contexto = ''
let sincronizando = false
let pararSincronia: (() => void) | null = null
const SAIDA = 'mesamar.equipe.saida-pendente'
function lerSaida (): boolean { try { return localStorage.getItem(SAIDA) === '1' } catch { return false } }
async function confirmarSaida (): Promise<void> {
  if (!lerSaida()) return
  try { await chamarRede<void>('POST', '/sair') } catch (e) { if (!(e instanceof ApiError && e.status === 401)) throw e }
  try { localStorage.removeItem(SAIDA) } catch { /* sem armazenamento */ }
}
async function chamar<T> (method: 'GET' | 'POST', path: string, body?: unknown): Promise<T> {
  if (path.startsWith('/entrar/')) await confirmarSaida()
  const destaSessao = sessaoLocal.signal
  const desteContexto = contexto
  try {
    const dados = await chamarRede<T>(method, path, body)
    if (destaSessao.aborted || desteContexto !== contexto) throw new ApiError(0, 'descartada', 'Ação descartada.')
    if (method === 'GET' && contexto !== '' && ['/cardapio', '/guarda-sois'].includes(path)) {
      try { await guardarCache(contexto, path, dados) } catch { /* leitura permanece utilizável sem cache */ }
    }
    return dados
  } catch (e) {
    if (method === 'GET' && contexto !== '' && e instanceof ApiError && e.temporario && !e.descartada && ['/cardapio', '/guarda-sois', '/clientes?pagina=0'].includes(path)) {
      try {
        const salvo = await lerCache(contexto, path)
        if (destaSessao.aborted || desteContexto !== contexto) throw new ApiError(0, 'descartada', 'Ação descartada.')
        if (salvo !== undefined) return contrato<T>(path, method, salvo)
      } catch { /* sem cópia válida */ }
    }
    throw e
  }
}
export function prepararOffline (s: Sessao): void {
  pararSincronia?.()
  contexto = s.logado && s.usuario !== undefined && s.barraca !== undefined ? `${s.barraca.codigo}:${s.usuario.id}` : ''
  definirContextoPendencias(contexto)
  if (contexto === '') { try { localStorage.removeItem('mesamar.equipe.sessao') } catch { /* sem armazenamento */ }; return }
  try { if (s.csrf !== '' && navigator.onLine !== false) localStorage.setItem('mesamar.equipe.sessao', JSON.stringify({ versao: 1, em: Date.now(), dados: { ...s, csrf: '' } })) } catch { /* sem persistência: não permite reabertura offline */ }
  // Reenvio com espera crescente enquanto restarem pendências sem confirmação; o sinal voltando recomeça do zero.
  let falhas = 0
  let timer = 0
  let parado = false
  const agendar = (): void => { if (!parado) { window.clearTimeout(timer); timer = window.setTimeout(tentar, esperaSincronia(falhas)) } }
  const tentar = (): void => {
    window.clearTimeout(timer)
    void sincronizar().then(restam => { falhas = restam ? falhas + 1 : 0 }, () => { falhas += 1 }).finally(agendar)
  }
  const voltou = (): void => { falhas = 0; tentar() }
  window.addEventListener('online', voltou)
  pararSincronia = () => { parado = true; window.clearTimeout(timer); window.removeEventListener('online', voltou) }
  tentar()
}
/** Suspender a página não revoga a sessão nem apaga a última confirmação online. */
export function suspenderOffline (): void {
  pararSincronia?.(); pararSincronia = null
  contexto = ''
  definirContextoPendencias('')
}
export function restaurarSessaoOffline (): Sessao | null {
  if (lerSaida()) return null
  try {
    const v = lerJson(localStorage.getItem('mesamar.equipe.sessao'))
    if (!registro(v) || v.versao !== 1 || !inteiro(1)(v.em) || Number(v.em) > Date.now() || Date.now() - Number(v.em) > 3 * 86_400_000) return null
    const s = conferir<Sessao>(v.dados, sessaoRegra)
    return s.logado && s.usuario !== undefined && s.barraca !== undefined ? s : null
  } catch { return null }
}
async function exclusaoMutua<T> (executar: () => Promise<T>): Promise<T> {
  if ('locks' in navigator) return await navigator.locks.request(`mesamar-sync:${contexto}`, executar)
  // Identificadores idempotentes protegem navegadores antigos sem Web Locks.
  return await executar()
}
async function enviarOperacao (op: Operacao): Promise<unknown> {
  const path = op.tipo === 'checkin' ? '/clientes' : `/comandas/${op.comandaId ?? ''}/pedidos`
  const resultado = await chamarRede<unknown>('POST', path, op.dados)
  // Guarda confirmação no contexto do autor, nunca no contexto que eventualmente abriu depois.
  await guardarCache(op.contexto, `confirmacao:${op.id}`, resultado)
  if (op.tipo === 'checkin') {
    const atuais = await lerCache(op.contexto, '/clientes?pagina=0')
    const lista = Array.isArray(atuais) ? atuais.filter(v => !registro(v) || v.id !== op.id) : []
    await guardarCache(op.contexto, '/clientes?pagina=0', [resultado, ...lista].slice(0, 200))
  }
  mudancasOffline.dispatchEvent(new CustomEvent('confirmada', { detail: { contexto: op.contexto, id: op.id, tipo: op.tipo, comandaId: op.comandaId } }))
  return resultado
}
/** Envia as pendências em ordem. Devolve `true` se alguma continua aguardando envio (para espaçar a próxima tentativa). */
export async function sincronizar (): Promise<boolean> {
  if (contexto === '') return false
  if (sincronizando || navigator.onLine === false) return true
  sincronizando = true
  const ctx = contexto
  const sinal = sessaoLocal.signal
  try {
    await exclusaoMutua(async () => {
      const s = await chamarRede<Sessao>('GET', '/sessao')
      const atual = s.logado && s.usuario !== undefined && s.barraca !== undefined ? `${s.barraca.codigo}:${s.usuario.id}` : ''
      if (sinal.aborted || ctx !== contexto) return
      if (atual !== ctx) { eventos.dispatchEvent(new Event('sessao-expirada')); return }
      await drenar(ctx, async op => { await enviarOperacao(op) }, () => !sinal.aborted && ctx === contexto)
    })
    return (await listarOperacoes(ctx)).some(o => o.estado === 'pendente')
  } finally { sincronizando = false; mudancasOffline.dispatchEvent(new Event('mudou')) }
}
export async function estadoSincronia (): Promise<{ sincronizando: boolean, operacoes: Operacao[] }> { return { sincronizando, operacoes: contexto === '' ? [] : await listarOperacoes(contexto) } }
export async function descartarConflito (id: string): Promise<void> {
  const op = (await listarOperacoes(contexto)).find(o => o.id === id)
  if (op?.estado !== 'conflito') return
  await removerOperacao(op.chave)
  await sincronizar()
}
async function enviarPreservado<T> (tipo: Operacao['tipo'], id: string, dados: unknown, comandaId?: string): Promise<T> {
  if (contexto === '') throw new ApiError(401, 'sessao', 'Entre novamente para preservar esta ação.')
  const ctx = contexto
  const sinal = sessaoLocal.signal
  let op: Operacao
  try { op = await preservar(ctx, tipo, id, dados, comandaId) } catch { throw new ApiError(507, 'armazenamento', 'Não foi possível guardar a ação. Libere espaço no aparelho e tente de novo.') }
  let resultado: unknown
  let falha: unknown
  await exclusaoMutua(async () => {
    if (sinal.aborted || ctx !== contexto) return
    const s = await chamarRede<Sessao>('GET', '/sessao').catch(e => { falha = e; return null })
    if (s === null) return
    if (!s.logado || s.usuario === undefined || s.barraca === undefined || `${s.barraca.codigo}:${s.usuario.id}` !== ctx) { falha = new ApiError(401, 'sessao', 'A sessão mudou. Entre novamente com o autor desta ação.'); eventos.dispatchEvent(new Event('sessao-expirada')); return }
    await drenar(ctx, async pendente => {
      try { const r = await enviarOperacao(pendente); if (pendente.id === id) resultado = r } catch (e) { falha = e; throw e }
    }, () => !sinal.aborted && ctx === contexto)
  })
  if (sinal.aborted || ctx !== contexto) throw new ApiError(0, 'descartada', 'Ação descartada.')
  if (resultado !== undefined) return resultado as T
  // Outra aba pode ter confirmado durante a espera pela trava.
  const confirmado = await lerCache(ctx, `confirmacao:${id}`)
  if (confirmado !== undefined) return contrato<T>(tipo === 'checkin' ? '/clientes' : `/comandas/${comandaId ?? ''}/pedidos`, 'POST', confirmado)
  if (falha instanceof ApiError && !falha.temporario) throw falha
  if (op.estado === 'conflito') throw new ApiError(409, 'conflito-offline', op.motivo ?? 'Confira a ação recusada no indicador de sincronização.')
  throw new ApiError(0, 'enfileirada', 'Ação preservada neste aparelho. Será reenviada em ordem quando o sinal voltar.')
}

export async function sessao (): Promise<Sessao> {
  if (lerSaida()) {
    if (navigator.onLine !== false) { try { await confirmarSaida() } catch (e) { if (!(e instanceof ApiError && e.temporario)) throw e } }
    return { logado: false, csrf: '' }
  }
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
  try { localStorage.setItem(SAIDA, '1'); localStorage.removeItem('mesamar.equipe.sessao') } catch { /* encerramento em memória */ }
  pararSincronia?.(); contexto = ''
  encerrarSessaoLocal()
  try { await confirmarSaida() } catch { /* a próxima conexão confirma a saída antes de qualquer login */ }
}

export async function cardapio (): Promise<Secao[]> {
  return await chamar<Secao[]>('GET', '/cardapio')
}

export async function definirEsgotado (produtoId: string, esgotado: boolean): Promise<Produto> {
  return await chamar<Produto>('POST', `/produtos/${produtoId}/esgotado`, { esgotado })
}

export async function clientes (pagina = 0): Promise<Comanda[]> {
  const ctx = contexto
  const sinal = sessaoLocal.signal
  const dados = await chamar<Comanda[]>('GET', `/clientes?pagina=${pagina}`)
  if (pagina !== 0 || ctx === '') return dados
  const pendentes = (await listarOperacoes(ctx)).filter(o => o.tipo === 'checkin')
  const cache = await lerCache(ctx, '/clientes?pagina=0')
  if (sinal.aborted || ctx !== contexto) throw new ApiError(0, 'descartada', 'Ação descartada.')
  const locais = Array.isArray(cache) ? cache.filter(v => registro(v) && pendentes.some(o => o.id === v.id) && !dados.some(c => c.id === v.id)).map(v => conferir<Comanda>(v, comandaRegra)) : []
  const mesclados = [...locais, ...dados].slice(0, 200)
  try { await guardarCache(ctx, '/clientes?pagina=0', mesclados) } catch { /* rede utilizável sem cache */ }
  return mesclados
}

/** "QR novo": troca o link da comanda (o cliente compartilhou ou perdeu o QR). Os pedidos e o saldo ficam. */
export async function novoQr (comandaId: string): Promise<Comanda> {
  return await chamar<Comanda>('POST', `/comandas/${comandaId}/novo-qr`)
}

export async function guardaSois (): Promise<GuardaSolOpcao[]> {
  return await chamar<GuardaSolOpcao[]>('GET', '/guarda-sois')
}

/**
 * Check-in. O `id` nasce no celular: se a rede cair no meio, reenviar o mesmo id devolve a mesma
 * comanda em vez de criar outra. A fila preserva a ação antes de enviar e reenvia em ordem quando houver conexão.
 */
export async function novoCliente (c: NovoCliente): Promise<Comanda> {
  const existente = (await listarOperacoes(contexto)).find(o => o.tipo === 'checkin' && o.id === c.id)
  const dados: NovoCliente & { token: string } = existente !== undefined && registro(existente.dados) && typeof existente.dados.token === 'string' ? conferir<NovoCliente & { token: string }>(existente.dados, novoClienteRegra) : { ...c, token: c.token ?? tokenLocal() }
  try { return await enviarPreservado<Comanda>('checkin', dados.id, dados) } catch (e) {
    if (!(e instanceof ApiError) || e.codigo !== 'enfileirada') throw e
    const ctx = contexto
    const gs = await lerCache(ctx, '/guarda-sois')
    const guardaSol = Array.isArray(gs) ? gs.find(v => registro(v) && v.id === c.guardaSolId) : undefined
    const local: Comanda = { id: c.id, numeroChegada: 0, nome: c.nome.trim(), guardaSol: registro(guardaSol) ? Number(guardaSol.numero) : null, whatsapp: null, whatsappExibicao: null, link: `${location.origin}/c/${dados.token}`, hora: new Date().toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }), status: 'ABERTA' }
    conferir(local, comandaRegra)
    const cache = await lerCache(ctx, '/clientes?pagina=0')
    await guardarCache(ctx, '/clientes?pagina=0', [local, ...(Array.isArray(cache) ? cache.filter(v => !registro(v) || v.id !== local.id) : [])].slice(0, 200))
    return local
  }
}

export interface ItemLancado { produtoId: string, quantidade: number, observacao?: string, precoCentavos?: number }

/** O pedido como o servidor devolve depois de lançado. */
export interface PedidoLancado {
  id: string
  numero: number
  status: StatusPedido
  hora: string
  totalCentavos: number
  excecoes?: string[]
}

/**
 * "Lançar pedido para cliente". O `id` nasce no celular: reenviar o mesmo pedido (sem sinal, toque duplo)
 * devolve o que já existe em vez de criar outro. Tenta de novo sozinho se a rede cair.
 */
export async function lancarPedido (comandaId: string, pedido: { id: string, itens: ItemLancado[] }): Promise<PedidoLancado> {
  return await enviarPreservado<PedidoLancado>('lancamento', pedido.id, pedido, comandaId)
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

/** O que está para pagar: quem avisou que vai pagar e as contas abertas com saldo. */
export async function pagamentos (pagina = 0): Promise<Pagamentos> {
  return await chamar<Pagamentos>('GET', `/pagamentos?pagina=${pagina}`)
}

/**
 * Confirma o que o cliente pagou. O `id` nasce no celular: reenviar a mesma confirmação (sem sinal, toque duplo)
 * devolve o pagamento que já existe em vez de cobrar duas vezes.
 */
export async function receberPagamento (
  comandaId: string,
  pagamento: { id: string, metodo: Metodo, valorCentavos: number, valorRecebidoCentavos: number | null }
): Promise<Confirmacao> {
  return await chamar<Confirmacao>('POST', `/comandas/${comandaId}/pagamentos`, pagamento)
}

export async function continuarComanda (comandaId: string): Promise<ContaAPagar> {
  return await chamar<ContaAPagar>('POST', `/comandas/${comandaId}/continuar`)
}

export async function bloquearComanda (comandaId: string): Promise<ContaAPagar> {
  return await chamar<ContaAPagar>('POST', `/comandas/${comandaId}/bloquear`)
}

export async function desbloquearComanda (comandaId: string): Promise<ContaAPagar> {
  return await chamar<ContaAPagar>('POST', `/comandas/${comandaId}/desbloquear`)
}

/** Encerra uma conta que não deve nada. */
export async function encerrarComanda (comandaId: string): Promise<ContaAPagar> {
  return await chamar<ContaAPagar>('POST', `/comandas/${comandaId}/encerrar`)
}

/** Fechamento forçado com motivo (só o dono): o cliente foi embora sem pagar. */
export async function fecharSemReceber (comandaId: string, motivo: string): Promise<ContaAPagar> {
  return await chamar<ContaAPagar>('POST', `/comandas/${comandaId}/fechamento-forcado`, { motivo })
}





/** "Reportar problema": o texto e o contexto que o app conhece (tela, conexão). */
export async function reportarProblema (mensagem: string, tela: string, conexao: string): Promise<void> {
  await chamar<unknown>('POST', '/problemas', { mensagem, tela, conexao })
}
