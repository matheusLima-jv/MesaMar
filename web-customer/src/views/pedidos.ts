import { reais, type ItemPedido, type Pedido } from '../api'
import { h } from '../dom'
import { avisoBloqueio, casca, type Contexto } from './casca'

const ROTULO: Record<string, string> = {
  AGUARDANDO_PAGAMENTO: 'Aguardando pagamento',
  RECEBIDO: 'Recebido',
  EM_PREPARO: 'Em preparo',
  ENTREGUE: 'Entregue',
  CANCELADO: 'Cancelado'
}

/**
 * "Meus pedidos": do mais novo para o mais antigo, com o status de cada um. A página se atualiza
 * sozinha a cada 15 s enquanto esta tela está aberta (ver main.ts). `pedidos` é `null` até a primeira resposta.
 */
export function telaPedidos (ctx: Contexto, pedidos: Pedido[] | null, semSinal: boolean): Node[] {
  let corpo: Node | string
  if (pedidos === null) {
    corpo = semSinal ? '' : h('p', { class: 'hint' }, 'Carregando os seus pedidos…')
  } else if (pedidos.length === 0) {
    corpo = h('div', { class: 'empty' },
      h('h2', {}, 'Você ainda não pediu nada'),
      h('a', { href: '#', class: 'btn btn-sun btn-big' }, 'Ver cardápio')
    )
  } else {
    corpo = h('ul', { class: 'orders' }, ...pedidos.map(cartao))
  }
  const aviso = pedidos === null
    ? 'Sem sinal. Não deu para carregar os pedidos.'
    : 'Sem sinal. Mostrando a última lista que carregou.'
  return casca(
    ctx,
    'pedidos',
    h('h2', { class: 'screen-title' }, 'Meus pedidos'),
    avisoBloqueio(ctx.dados) ?? '',
    semSinal ? h('p', { class: 'alert', role: 'status' }, aviso) : '',
    corpo,
    h('p', { class: 'total' }, 'Total da conta ', h('strong', { class: 'total-valor' }, reais(ctx.dados.saldoCentavos))),
    h('p', { class: 'hint' }, 'Esta lista se atualiza sozinha a cada 15 segundos.')
  )
}

function cartao (p: Pedido): HTMLElement {
  return h('li', { class: `order st-${p.status.toLowerCase()}` },
    h('div', { class: 'order-head' },
      h('strong', {}, `Pedido nº ${p.numero}`, h('span', { class: 'order-hora' }, ` · ${p.hora}`)),
      h('span', { class: 'badge' }, ROTULO[p.status] ?? p.status)
    ),
    h('ul', { class: 'order-items' }, ...p.itens.map(linha)),
    p.status === 'AGUARDANDO_PAGAMENTO'
      ? h('p', { class: 'hint' }, `Pague ${reais(p.totalCentavos)} a um atendente para o pedido entrar na fila.`)
      : null,
    h('p', { class: 'order-total' }, 'Total ', h('strong', {}, reais(p.totalCentavos)))
  )
}

function linha (i: ItemPedido): HTMLElement {
  return h('li', { class: i.cancelado ? 'is-sold' : '' },
    h('strong', {}, `${i.quantidade}× ${i.nome}`),
    i.cancelado ? h('span', { class: 'tag' }, 'Cancelado') : null,
    i.observacao !== null ? h('span', { class: 'desc' }, i.observacao) : null
  )
}
