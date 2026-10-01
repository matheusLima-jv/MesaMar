import { reais, type Pedido, type PedidoEnvio } from '../api'
import { h } from '../dom'
import { casca, type Contexto } from './casca'

/**
 * Pedido saindo do celular. Sem sinal, a página continua tentando sozinha (o servidor não duplica),
 * e mostra o pedido para o cliente poder passar para um atendente se preferir.
 */
export function telaEnvio (ctx: Contexto, pedido: PedidoEnvio, semSinal: boolean): Node[] {
  const linhas = pedido.itens.map((i) => {
    const p = ctx.produtos.get(i.produtoId)
    return h('li', {},
      h('strong', {}, `${i.quantidade}× ${p?.nome ?? 'Item'}`),
      i.observacao !== '' ? h('span', { class: 'desc' }, i.observacao) : null
    )
  })
  return casca(
    ctx.dados,
    h('div', { class: 'sending', role: 'status' },
      h('h2', {}, semSinal ? 'O pedido ainda não saiu' : 'Enviando o seu pedido…'),
      h('p', {}, semSinal
        ? 'Sem sinal. Tentaremos de novo. Se preferir, chame um atendente.'
        : 'Só um instante.')
    ),
    h('p', { class: 'hint' }, 'O seu pedido:'),
    h('ul', { class: 'cart-list plain' }, ...linhas),
    h('p', { class: 'hint' }, 'Se você fechar esta página, abra o link do QR de novo: o pedido continua daqui e é enviado só uma vez.')
  )
}

/** Confirmação clara: o número do pedido e o que foi pedido, como o quiosque recebeu. */
export function telaConfirmacao (ctx: Contexto, pedido: Pedido, aoVoltar: () => void): Node[] {
  const voltar = h('button', { type: 'button', class: 'btn btn-sun btn-big btn-block' }, 'Voltar ao cardápio')
  voltar.addEventListener('click', aoVoltar)
  return casca(
    ctx.dados,
    h('div', { class: 'done', role: 'status' },
      h('p', { class: 'done-mark', 'aria-hidden': 'true' }, '✓'),
      h('h2', {}, `Pedido nº ${pedido.numero} enviado`),
      h('p', {}, 'O quiosque já recebeu o seu pedido.')
    ),
    h('ul', { class: 'cart-list plain' }, ...pedido.itens.map((i) =>
      h('li', {},
        h('strong', {}, `${i.quantidade}× ${i.nome}`),
        i.observacao !== null ? h('span', { class: 'desc' }, i.observacao) : null,
        h('span', { class: 'price' }, reais(i.subtotalCentavos))
      )
    )),
    h('p', { class: 'total' }, 'Total do pedido ', h('strong', { class: 'total-valor' }, reais(pedido.totalCentavos))),
    voltar
  )
}
