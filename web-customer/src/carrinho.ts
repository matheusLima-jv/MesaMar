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
      const salvo = JSON.parse(lembrar.get(`carrinho.${token}`) ?? '[]') as unknown
      if (Array.isArray(salvo)) {
        this.linhas = salvo.filter((l): l is Linha =>
          typeof l === 'object' && l !== null &&
          typeof (l as Linha).produtoId === 'string' &&
          Number.isInteger((l as Linha).quantidade) && (l as Linha).quantidade > 0 &&
          typeof (l as Linha).observacao === 'string')
      }
    } catch { this.linhas = [] }
  }

  quantidade (produtoId: string): number {
    return this.linhas.find((l) => l.produtoId === produtoId)?.quantidade ?? 0
  }

  observacao (produtoId: string): string {
    return this.linhas.find((l) => l.produtoId === produtoId)?.observacao ?? ''
  }

  definir (produtoId: string, quantidade: number): void {
    const q = Math.min(Math.max(quantidade, 0), MAX_QUANTIDADE)
    const linha = this.linhas.find((l) => l.produtoId === produtoId)
    if (q === 0) this.linhas = this.linhas.filter((l) => l.produtoId !== produtoId)
    else if (linha !== undefined) linha.quantidade = q
    else this.linhas.push({ produtoId, quantidade: q, observacao: '' })
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

  itens (): ItemEnvio[] {
    return this.linhas.map((l) => ({ produtoId: l.produtoId, quantidade: l.quantidade, observacao: l.observacao.trim() }))
  }

  private salvar (): void {
    lembrar.set(`carrinho.${this.token}`, JSON.stringify(this.linhas))
  }
}

/**
 * O pedido que já saiu do carrinho e ainda não foi confirmado pelo servidor. O `id` nasce uma vez só e
 * acompanha todas as tentativas de envio: é isso que impede o pedido duplicado.
 */
export const pendente = {
  ler (token: string): PedidoEnvio | null {
    try {
      const p = JSON.parse(lembrar.get(`pendente.${token}`) ?? 'null') as PedidoEnvio | null
      return p !== null && typeof p.id === 'string' && Array.isArray(p.itens) ? p : null
    } catch { return null }
  },
  guardar (token: string, pedido: PedidoEnvio): void {
    lembrar.set(`pendente.${token}`, JSON.stringify(pedido))
  },
  apagar (token: string): void {
    lembrar.set(`pendente.${token}`, 'null')
  }
}
