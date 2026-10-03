/** Contratos na fronteira: JSON só vira um tipo depois de conferir sua estrutura. */
export type Regra = (valor: unknown) => boolean
export const texto = (max = 200, min = 0): Regra => v => typeof v === 'string' && v.length >= min && v.length <= max
export const inteiro = (min = 0, max = Number.MAX_SAFE_INTEGER): Regra => v => typeof v === 'number' && Number.isSafeInteger(v) && v >= min && v <= max
export const booleano: Regra = v => typeof v === 'boolean'
export const uuid: Regra = v => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v)
export const data: Regra = v => typeof v === 'string' && v.length <= 40 && Number.isFinite(Date.parse(v))
export const hora: Regra = v => typeof v === 'string' && /^\d{2}:\d{2}(:\d{2})?$/.test(v)
export const nulo = (r: Regra): Regra => v => v === null || r(v)
export const opcional = (r: Regra): Regra => v => v === undefined || r(v)
export const escolha = (...valores: unknown[]): Regra => v => valores.includes(v)
export const objeto = (campos: Record<string, Regra>): Regra => v => registro(v) && Object.entries(campos).every(([k, r]) => r(v[k]))
export const registro = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
export const lista = (r: Regra, max = 500, min = 0, chave?: string): Regra => v => Array.isArray(v) && v.length >= min && v.length <= max && v.every(r) && (chave === undefined || new Set(v.map(x => registro(x) ? x[chave] : undefined)).size === v.length)
export function conferir<T> (v: unknown, regra: Regra): T {
  if (!regra(v)) throw new Error('Dados incompatíveis. Atualize a página e confira a operação antes de repetir.')
  return v as T
}
export const centavos = inteiro()
export const statusConta = escolha('ABERTA', 'AGUARDANDO_PAGAMENTO', 'FECHADA', 'CANCELADA', 'BLOQUEADA')
export const statusPedido = escolha('AGUARDANDO_PAGAMENTO', 'RECEBIDO', 'EM_PREPARO', 'ENTREGUE', 'CANCELADO')
export const metodo = escolha('PIX', 'DINHEIRO', 'CARTAO')
export const produto = objeto({ id: uuid, nome: texto(120, 1), descricao: nulo(texto(2000)), precoCentavos: centavos, preco: texto(40, 1), esgotado: booleano })
export const secoes = lista(objeto({ id: uuid, nome: texto(120, 1), produtos: lista(produto, 1000, 0, 'id') }), 100, 0, 'id')
export const itemEnvio = objeto({ produtoId: uuid, quantidade: inteiro(1, 20), observacao: opcional(texto(80)), precoCentavos: opcional(centavos) })
export const itensEnvio = lista(itemEnvio, 30, 1, 'produtoId')
export const pedidoEnvio = objeto({ id: uuid, itens: itensEnvio, reenvio: opcional(booleano) })
export const novoCliente = objeto({ id: uuid, token: opcional(v => typeof v === 'string' && /^[A-Za-z0-9_-]{22,64}$/.test(v)), nome: texto(40, 1), guardaSolId: nulo(uuid), whatsapp: texto(25), consentimento: escolha(true), consentimentoVersao: opcional(texto(40, 1)) })
/** Versões desconhecidas nunca são reinterpretadas como dados da versão atual. */
export function desembrulhar (v: unknown): unknown {
  if (registro(v) && 'versao' in v) return v.versao === 1 ? v.dados : undefined
  return v // migração explícita do formato legado, submetido às mesmas regras
}
export function lerJson (bruto: string | null, max = 100_000): unknown {
  if (bruto === null || bruto.length > max) return undefined
  try { return JSON.parse(bruto) as unknown } catch { return undefined }
}
