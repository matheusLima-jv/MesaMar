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
    ...(dados.demo ? [h('p', { class: 'demo-faixa', role: 'note' }, 'Barraca de treino: nada aqui é de verdade. Não pague.')] : []),
    h('header', { class: 'top' },
      h('p', { class: 'shop' }, dados.barraca),
      h('h1', {}, `Olá, ${dados.nome}`, dados.guardaSol !== null ? ` · Guarda-sol ${dados.guardaSol}` : ''),
      h('p', { class: 'conta' }, 'Sua conta: ', h('strong', {}, reais(dados.saldoCentavos)))
    ),
    // Sem sinal, o cardápio veio do cache do aparelho: diz de quando ele é, para ninguém achar que está confirmado agora
    ...(dados.salvoEm !== undefined
      ? [h('p', { class: 'alert', role: 'status' },
          `Sem sinal. Mostrando o cardápio salvo às ${new Date(dados.salvoEm).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })}: preços e itens podem ter mudado.`)]
      : []),
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
      h('a', { href: '/privacidade', target: '_blank', rel: 'noopener' }, 'Política de privacidade'),
      h('a', { href: '#problema', class: 'foot-link' }, 'Reportar um problema')
    )
  ]
}

/** Aviso que não some: pedidos pausados, conta fechando, limite de saldo… */
export function avisoBloqueio (dados: Comanda): Node | null {
  if (dados.bloqueio === null) return null
  return h('p', { class: 'alert', role: 'status' }, dados.bloqueio.mensagem)
}
