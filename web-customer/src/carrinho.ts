import { conferir, desembrulhar, inteiro, lerJson, lista, objeto, pedidoEnvio, texto, uuid } from '../../web-shared/src/validacao'
// Carrinho e pedido em envio. Os dois ficam guardados no navegador (por comanda), então fechar a aba
// ou recarregar a página com sinal ruim não perde o que o cliente montou nem o pedido que ainda não
// chegou ao quiosque.

import { type ItemEnvio, type PedidoEnvio, type Produto } from './api'
import { lembrar } from './dom'

export interface Linha {
  produtoId: string
  quantidade: number
  observacao: string
}

/** Mesmos limites do servidor (PedidoService): o servidor confere de novo. */
export const MAX_QUANTIDADE = 20
export const MAX_OBSERVACAO = 80

export class Carrinho {
  linhas: Linha[] = []

  constructor (private readonly token: string) {
    try {
      const salvo = desembrulhar(lerJson(lembrar.get(`carrinho.${token}`)))
      if (salvo !== undefined) this.linhas = conferir<Linha[]>(salvo, lista(objeto({ produtoId: uuid, quantidade: inteiro(1, MAX_QUANTIDADE), observacao: texto(MAX_OBSERVACAO) }), 30, 0, 'produtoId'))
    } catch { this.linhas = [] }
  }

  quantidade (produtoId: string): number {
    return this.linhas.find((l) => l.produtoId === produtoId)?.quantidade ?? 0
  }

  observacao (produtoId: string): string {
    return this.linhas.find((l) => l.produtoId === produtoId)?.observacao ?? ''
  }

  definir (produtoId: string, quantidade: number): void {
    if (!uuid(produtoId) || !Number.isSafeInteger(quantidade)) return
    const q = Math.min(Math.max(quantidade, 0), MAX_QUANTIDADE)
    const linha = this.linhas.find((l) => l.produtoId === produtoId)
    if (q === 0) this.linhas = this.linhas.filter((l) => l.produtoId !== produtoId)
    else if (linha !== undefined) linha.quantidade = q
    else if (this.linhas.length < 30) this.linhas.push({ produtoId, quantidade: q, observacao: '' })
    this.salvar()
  }

  definirObservacao (produtoId: string, texto: string): void {
    const linha = this.linhas.find((l) => l.produtoId === produtoId)
    if (linha === undefined) return
    linha.observacao = texto.slice(0, MAX_OBSERVACAO)
    this.salvar()
  }

  remover (produtoIds: string[]): void {
    this.linhas = this.linhas.filter((l) => !produtoIds.includes(l.produtoId))
    this.salvar()
  }

  limpar (): void {
    this.linhas = []
    this.salvar()
  }

  unidades (): number {
    return this.linhas.reduce((soma, l) => soma + l.quantidade, 0)
  }

  /** Total em centavos pelos preços do cardápio que a página tem agora (o servidor confirma na hora de gravar). */
  total (produtos: Map<string, Produto>): number {
    return this.linhas.reduce((soma, l) => soma + (produtos.get(l.produtoId)?.precoCentavos ?? 0) * l.quantidade, 0)
  }

  /** As linhas para enviar, com o preço que o cliente está vendo (o servidor confere se ainda é o mesmo). */
  itens (produtos: Map<string, Produto>): ItemEnvio[] {
    return this.linhas.map((l) => ({
      produtoId: l.produtoId,
      quantidade: l.quantidade,
      observacao: l.observacao.trim(),
      precoCentavos: produtos.get(l.produtoId)?.precoCentavos
    }))
  }

  private salvar (): void {
    lembrar.set(`carrinho.${this.token}`, JSON.stringify({ versao: 1, dados: this.linhas }))
  }
}

/**
 * O pedido que já saiu do carrinho e ainda não foi confirmado pelo servidor. O `id` nasce uma vez só e
 * acompanha todas as tentativas de envio: é isso que impede o pedido duplicado.
 */
/** Cópia em memória do pedido pendente: se o armazenamento do navegador falhar, o envio desta visita continua valendo. */
const pendenteEmMemoria = new Map<string, PedidoEnvio | null>()

export const pendente = {
  ler (token: string): PedidoEnvio | null {
    if (pendenteEmMemoria.has(token)) return structuredClone(pendenteEmMemoria.get(token) ?? null)
    try {
      const p = desembrulhar(lerJson(lembrar.get(`pendente.${token}`)))
      return p === undefined || p === null ? null : conferir<PedidoEnvio>(p, pedidoEnvio)
    } catch { return null }
  },
  guardar (token: string, pedido: PedidoEnvio): void {
    const copia = conferir<PedidoEnvio>(structuredClone(pedido), pedidoEnvio)
    pendenteEmMemoria.set(token, copia)
    lembrar.set(`pendente.${token}`, JSON.stringify({ versao: 1, dados: copia }))
  },
  apagar (token: string): void {
    pendenteEmMemoria.set(token, null)
    lembrar.set(`pendente.${token}`, 'null')
  }
}
