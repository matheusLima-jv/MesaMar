import { reais, type Produto } from '../api'
import { MAX_QUANTIDADE } from '../carrinho'
import { h } from '../dom'
import { avisoBloqueio, casca, type Contexto } from './casca'

/** Cardápio por categoria. Esgotado aparece, mas sem botão. Sem pedido possível (conta fechando…), só leitura. */
export function telaCardapio (ctx: Contexto): Node[] {
  const { dados, carrinho } = ctx
  const aberto = dados.bloqueio === null

  const barra = h('a', { href: '#carrinho', class: 'cart-bar' })
  const atualizarBarra = (): void => {
    const unidades = carrinho.unidades()
    barra.hidden = unidades === 0 || !aberto
    barra.textContent = `Ver pedido · ${unidades} ${unidades === 1 ? 'item' : 'itens'} · ${reais(carrinho.total(ctx.produtos))}`
  }

  const secoes = dados.cardapio.map((s) =>
    h('section', { class: 'menu-sec', 'aria-labelledby': `sec-${s.id}` },
      h('h2', { id: `sec-${s.id}` }, s.nome),
      h('ul', { class: 'menu-list' }, ...s.produtos.map((p) => linhaProduto(ctx, p, aberto, atualizarBarra)))
    )
  )

  atualizarBarra()
  return [
    ...casca(
      dados,
      avisoBloqueio(dados) ?? '',
      ...(secoes.length > 0
        ? secoes
        : [h('div', { class: 'empty' }, h('h2', {}, 'O cardápio está sendo montado'), h('p', {}, 'Chame um atendente para pedir.'))])
    ),
    barra
  ]
}

function linhaProduto (ctx: Contexto, p: Produto, aberto: boolean, aoMudar: () => void): HTMLElement {
  const { carrinho } = ctx
  const podePedir = aberto && !p.esgotado
  const n = h('span', { class: 'qty-n', 'aria-live': 'polite' })
  const adicionar = h('button', { type: 'button', class: 'btn btn-sun add', 'aria-label': `Adicionar ${p.nome}` }, 'Adicionar')
  const menos = h('button', { type: 'button', class: 'step', 'aria-label': `Menos um ${p.nome}` }, '−')
  const mais = h('button', { type: 'button', class: 'step', 'aria-label': `Mais um ${p.nome}` }, '+')
  const stepper = h('div', { class: 'stepper', role: 'group', 'aria-label': p.nome }, menos, n, mais)

  const sincronizar = (): void => {
    const q = carrinho.quantidade(p.id)
    n.textContent = String(q)
    adicionar.hidden = q > 0
    stepper.hidden = q === 0
    mais.disabled = q >= MAX_QUANTIDADE
  }
  const mudar = (q: number): void => {
    carrinho.definir(p.id, q)
    sincronizar()
    aoMudar()
    // O botão que o cliente tocou pode ter saído da tela (de "Adicionar" para o seletor, ou o contrário)
    if (q === 1 && document.activeElement === adicionar) mais.focus()
    if (q === 0) adicionar.focus()
  }
  adicionar.addEventListener('click', () => { mudar(1) })
  mais.addEventListener('click', () => { mudar(carrinho.quantidade(p.id) + 1) })
  menos.addEventListener('click', () => { mudar(carrinho.quantidade(p.id) - 1) })
  sincronizar()

  return h('li', { class: p.esgotado ? 'is-sold' : '' },
    h('div', { class: 'menu-main' },
      h('strong', {}, p.nome),
      p.esgotado ? h('span', { class: 'tag' }, 'Esgotado') : null,
      p.descricao !== null ? h('span', { class: 'desc' }, p.descricao) : null,
      h('span', { class: 'price' }, p.preco)
    ),
    podePedir ? h('div', { class: 'qty' }, adicionar, stepper) : null
  )
}
