import { ApiError, cardapio, definirEsgotado, type Produto, type Secao } from '../api'
import { h, mount } from '../dom'
import { avisar } from '../toast'

/**
 * Aba Cardápio: o que interessa no meio do dia é "acabou / voltou". Um toque troca, na hora,
 * e se o servidor recusar o botão volta ao estado anterior com um aviso.
 */
export function abaCardapio (root: HTMLElement): void {
  mount(root, h('p', { class: 'loading' }, 'Carregando o cardápio…'))
  cardapio()
    .then((secoes) => desenhar(root, secoes))
    .catch((e: unknown) => {
      mount(root, h('div', { class: 'empty' },
        h('p', {}, e instanceof ApiError ? e.message : 'Não deu para carregar o cardápio.'),
        h('button', { type: 'button', class: 'btn btn-line', onclick: () => abaCardapio(root) }, 'Tentar de novo')
      ))
    })
}

function desenhar (root: HTMLElement, secoes: Secao[]): void {
  const total = secoes.reduce((n, s) => n + s.produtos.length, 0)
  if (total === 0) {
    mount(root, h('div', { class: 'empty' },
      h('h2', {}, 'Cardápio vazio'),
      h('p', {}, 'O dono cadastra categorias, produtos e preços no painel, pelo navegador.')
    ))
    return
  }
  const esgotados = secoes.flatMap((s) => s.produtos).filter((p) => p.esgotado).length
  mount(root,
    h('header', { class: 'tab-head' },
      h('h1', {}, 'Cardápio'),
      h('p', { class: 'hint' }, esgotados === 0 ? 'Tudo disponível. Acabou algo? Toque em "Esgotou".' : `${esgotados} ${esgotados === 1 ? 'item esgotado' : 'itens esgotados'}.`)
    ),
    ...secoes.filter((s) => s.produtos.length > 0).map((s) =>
      h('section', { class: 'menu-sec', 'aria-label': s.nome },
        h('h2', {}, s.nome),
        h('ul', { class: 'menu-list' }, ...s.produtos.map((p) => linha(root, secoes, p)))
      )
    )
  )
}

function linha (root: HTMLElement, secoes: Secao[], p: Produto): HTMLLIElement {
  const botao = h('button', {
    type: 'button',
    class: p.esgotado ? 'sold on' : 'sold',
    'aria-pressed': String(p.esgotado),
    'aria-label': p.esgotado ? `Voltar a vender ${p.nome}` : `Marcar ${p.nome} como esgotado`,
    onclick: () => {
      const novo = !p.esgotado
      p.esgotado = novo
      desenhar(root, secoes) // otimista: o toque responde na hora, mesmo com sinal fraco
      definirEsgotado(p.id, novo)
        .then((salvo) => { p.esgotado = salvo.esgotado; avisar(salvo.esgotado ? `${p.nome}: esgotado.` : `${p.nome} voltou.`) })
        .catch((e: unknown) => {
          p.esgotado = !novo
          desenhar(root, secoes)
          avisar(e instanceof ApiError ? e.message : 'Não deu para salvar. Tente de novo.', true)
        })
    }
  }, p.esgotado ? 'Voltou' : 'Esgotou')

  return h('li', { class: p.esgotado ? 'is-sold' : '' },
    h('div', { class: 'menu-main' },
      h('strong', {}, p.nome),
      p.esgotado ? h('span', { class: 'tag' }, 'Esgotado') : null,
      h('span', { class: 'price' }, p.preco)
    ),
    botao
  )
}
