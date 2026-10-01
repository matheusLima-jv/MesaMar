import { reais } from '../api'
import { MAX_OBSERVACAO, MAX_QUANTIDADE, type Linha } from '../carrinho'
import { h } from '../dom'
import { avisoBloqueio, casca, type Contexto } from './casca'

/**
 * Carrinho: quantidade e observação por item ("sem gelo"), o total e o botão de enviar.
 * `erro` é o motivo da última tentativa que o servidor recusou (ex.: "Acabou: Água de coco").
 */
export function telaCarrinho (ctx: Contexto, erro: string | null, aoEnviar: () => void, aoAtualizar: () => void): Node[] {
  const { dados, carrinho, produtos } = ctx
  const voltar = h('a', { href: '#', class: 'back' }, '← Cardápio')

  if (carrinho.linhas.length === 0) {
    return casca(
      ctx,
      null,
      voltar,
      h('div', { class: 'empty' },
        h('h2', {}, 'Seu pedido está vazio'),
        h('p', {}, 'Escolha o que quiser no cardápio.'),
        h('a', { href: '#', class: 'btn btn-sun btn-big' }, 'Ver cardápio')
      )
    )
  }

  const total = h('strong', { class: 'total-valor' })
  const aviso = h('p', { class: 'hint' })
  const enviar = h('button', { type: 'button', class: 'btn btn-sun btn-big btn-block' }, 'Enviar pedido')
  const aberto = dados.bloqueio === null

  const sincronizar = (): void => {
    total.textContent = reais(carrinho.total(produtos))
    const acabou = carrinho.linhas.filter((l) => produtos.get(l.produtoId)?.esgotado !== false)
    enviar.disabled = !aberto || acabou.length > 0
    aviso.textContent = acabou.length > 0
      ? 'Tire os itens que acabaram para poder enviar o pedido.'
      : dados.modoPagamento === 'POR_PEDIDO'
        ? 'Neste quiosque você paga a cada pedido: ele entra na fila depois que o quiosque confirmar o pagamento.'
        : 'O pedido vai direto para o quiosque.'
  }
  enviar.addEventListener('click', aoEnviar)

  const lista = h('ul', { class: 'cart-list' }, ...carrinho.linhas.map((l) => linha(ctx, l, sincronizar, aoAtualizar)))
  sincronizar()

  return casca(
    ctx,
    null,
    voltar,
    h('h2', { class: 'screen-title' }, 'Seu pedido'),
    avisoBloqueio(dados) ?? '',
    erro !== null ? h('p', { class: 'alert', role: 'alert' }, erro) : '',
    lista,
    h('p', { class: 'total' }, 'Total ', total),
    enviar,
    aviso
  )
}

function linha (ctx: Contexto, l: Linha, aoMudar: () => void, aoRemover: () => void): HTMLElement {
  const { carrinho, produtos } = ctx
  const p = produtos.get(l.produtoId)
  // Produto que acabou (ou saiu do cardápio) desde que entrou no carrinho
  const acabou = p === undefined || p.esgotado
  const nome = p?.nome ?? 'Item que saiu do cardápio'
  const n = h('span', { class: 'qty-n', 'aria-live': 'polite' }, String(l.quantidade))
  const subtotal = h('span', { class: 'price' })
  const menos = h('button', { type: 'button', class: 'step', 'aria-label': `Menos um ${nome}` }, '−')
  const mais = h('button', { type: 'button', class: 'step', 'aria-label': `Mais um ${nome}` }, '+')
  const idObs = `obs-${l.produtoId}`

  const sincronizar = (): void => {
    const q = carrinho.quantidade(l.produtoId)
    n.textContent = String(q)
    subtotal.textContent = `${q} × ${p?.preco ?? ''} = ${reais((p?.precoCentavos ?? 0) * q)}`
    mais.disabled = q >= MAX_QUANTIDADE || acabou
  }
  const mudar = (q: number): void => {
    carrinho.definir(l.produtoId, q)
    if (q === 0) { aoRemover(); return }
    sincronizar()
    aoMudar()
  }
  menos.addEventListener('click', () => { mudar(carrinho.quantidade(l.produtoId) - 1) })
  mais.addEventListener('click', () => { mudar(carrinho.quantidade(l.produtoId) + 1) })
  sincronizar()

  const obs = h('input', {
    id: idObs, type: 'text', maxlength: MAX_OBSERVACAO, value: l.observacao, placeholder: 'ex.: sem gelo', autocomplete: 'off'
  })
  obs.addEventListener('input', () => { carrinho.definirObservacao(l.produtoId, obs.value) })

  return h('li', { class: acabou ? 'cart-line is-sold' : 'cart-line' },
    h('div', { class: 'cart-head' },
      h('div', { class: 'menu-main' },
        h('strong', {}, nome),
        acabou ? h('span', { class: 'tag' }, 'Acabou') : null,
        subtotal
      ),
      h('div', { class: 'stepper', role: 'group', 'aria-label': nome }, menos, n, mais)
    ),
    h('div', { class: 'field' },
      h('label', { for: idObs }, `Observação de ${nome} `, h('span', { class: 'opt' }, '(opcional)')),
      obs
    )
  )
}
