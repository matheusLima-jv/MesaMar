import { type Comanda, type Produto } from '../api'
import { type Carrinho } from '../carrinho'
import { h } from '../dom'

/** O que toda tela precisa para se desenhar. */
export interface Contexto {
  dados: Comanda
  carrinho: Carrinho
  /** O cardápio por id, para achar nome e preço de uma linha do carrinho. */
  produtos: Map<string, Produto>
}

/** Cabeçalho ("Olá, Ana · Guarda-sol 12"), o conteúdo da tela e o rodapé com a política de privacidade. */
export function casca (dados: Comanda, ...conteudo: Array<Node | string>): Node[] {
  return [
    h('header', { class: 'top' },
      h('p', { class: 'shop' }, dados.barraca),
      h('h1', {}, `Olá, ${dados.nome}`, dados.guardaSol !== null ? ` · Guarda-sol ${dados.guardaSol}` : '')
    ),
    h('main', { id: 'conteudo', class: 'page' }, ...conteudo),
    h('footer', { class: 'foot' },
      h('a', { href: '/privacidade', target: '_blank', rel: 'noopener' }, 'Política de privacidade')
    )
  ]
}

/** Aviso que não some: pedidos pausados, conta fechando… */
export function avisoBloqueio (dados: Comanda): Node | null {
  if (dados.bloqueio === null) return null
  return h('p', { class: 'alert', role: 'status' }, dados.bloqueio.mensagem)
}
