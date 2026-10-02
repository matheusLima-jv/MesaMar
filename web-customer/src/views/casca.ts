import { reais, type Comanda, type Produto } from '../api'
import { type Carrinho } from '../carrinho'
import { h } from '../dom'

/** O que toda tela precisa para se desenhar. */
export interface Contexto {
  dados: Comanda
  carrinho: Carrinho
  /** O cardápio por id, para achar nome e preço de uma linha do carrinho. */
  produtos: Map<string, Produto>
  /** Avisos do quiosque que o cliente ainda não leu (ex.: "O quiosque cancelou 1× Pastel do pedido nº 3: Esgotou."). */
  avisos: string[]
  dispensarAvisos: () => void
}

export type Aba = 'cardapio' | 'pedidos' | 'conta' | null

/**
 * Cabeçalho ("Olá, Ana · Guarda-sol 12" e a conta de agora), as abas Cardápio, Meus pedidos e Conta, o
 * conteúdo da tela e o rodapé com a política de privacidade.
 */
export function casca (ctx: Contexto, aba: Aba, ...conteudo: Array<Node | string>): Node[] {
  const { dados } = ctx
  const link = (id: Exclude<Aba, null>, href: string, rotulo: string): HTMLElement =>
    h('a', { href, class: aba === id ? 'on' : '', 'aria-current': aba === id ? 'page' : undefined }, rotulo)
  return [
    h('header', { class: 'top' },
      h('p', { class: 'shop' }, dados.barraca),
      h('h1', {}, `Olá, ${dados.nome}`, dados.guardaSol !== null ? ` · Guarda-sol ${dados.guardaSol}` : ''),
      h('p', { class: 'conta' }, 'Sua conta: ', h('strong', {}, reais(dados.saldoCentavos)))
    ),
    h('nav', { class: 'abas', 'aria-label': 'Seções da sua comanda' },
      link('cardapio', '#', 'Cardápio'),
      link('pedidos', '#pedidos', 'Meus pedidos'),
      link('conta', '#conta', 'Conta')
    ),
    h('main', { id: 'conteudo', class: 'page' },
      ctx.avisos.length > 0
        ? h('section', { class: 'avisos', role: 'status', 'aria-label': 'Avisos do quiosque' },
          ...ctx.avisos.map((t) => h('p', {}, t)),
          h('button', { type: 'button', class: 'btn btn-line', onclick: ctx.dispensarAvisos }, 'Entendi'))
        : '',
      ...conteudo),
    h('footer', { class: 'foot' },
      h('a', { href: '/privacidade', target: '_blank', rel: 'noopener' }, 'Política de privacidade')
    )
  ]
}

/** Aviso que não some: pedidos pausados, conta fechando, limite de saldo… */
export function avisoBloqueio (dados: Comanda): Node | null {
  if (dados.bloqueio === null) return null
  return h('p', { class: 'alert', role: 'status' }, dados.bloqueio.mensagem)
}
