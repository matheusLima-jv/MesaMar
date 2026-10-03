import { type Pedido } from './api'
import { type Contexto } from './views/casca'

/** Estado de uma instância do cliente; não é compartilhado entre reinícios/aplicações. */
export class EstadoCliente {
  ativo = true
  geracao = 0
  token = ''
  ctx!: Contexto
  confirmado: Pedido | null = null
  erroCarrinho: string | null = null
  semSinal = false
  enviando = false
  atualizadoEm = 0
  meus: Pedido[] | null = null
  meusSemSinal = false
  tempoReal = false
  avisados = new Set<string>()
  idsDosAvisos: string[] = []
}
