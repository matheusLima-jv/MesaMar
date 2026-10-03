import { ApiError, cardapio, definirEsgotado, descartada, type Produto, type Secao } from '../api'
import { abrirTela, h, mount } from '../dom'
import { cardapioMudou } from '../fila-store'
import { avisar } from '../toast'

/** Produtos com um "Esgotou/Voltou" viajando: um envio por produto de cada vez. */
const emEnvio = new Set<string>()

/**
 * Aba Cardápio: o que interessa no meio do dia é "acabou / voltou". Um toque troca na hora (otimista); o envio é um
 * por produto de cada vez, e toques rápidos no meio só mudam o que se quer no fim: quando o envio volta, se o desejado
 * mudou, manda de novo. Assim uma resposta velha nunca desfaz o último toque. Se o servidor recusar, o botão volta ao
 * último estado que ele confirmou.
 */
export function abaCardapio (root: HTMLElement): void {
  const aberta = abrirTela(root)
  mount(root, h('p', { class: 'loading' }, 'Carregando o cardápio…'))
  cardapio()
    .then((secoes) => { if (aberta()) desenhar(root, aberta, secoes) })
    .catch((e: unknown) => {
      if (!aberta() || descartada(e)) return
      mount(root, h('div', { class: 'empty' },
        h('p', {}, e instanceof ApiError ? e.message : 'Não deu para carregar o cardápio.'),
        h('button', { type: 'button', class: 'btn btn-line', onclick: () => abaCardapio(root) }, 'Tentar de novo')
      ))
    })
  // Outro aparelho (ou o painel) mudou o cardápio: busca de novo, se nada estiver viajando daqui
  const aoMudar = (): void => {
    if (!aberta()) { cardapioMudou.removeEventListener('mudou', aoMudar); return }
    if (emEnvio.size > 0) return
    cardapioMudou.removeEventListener('mudou', aoMudar)
    abaCardapio(root)
  }
  cardapioMudou.addEventListener('mudou', aoMudar)
}

function desenhar (root: HTMLElement, aberta: () => boolean, secoes: Secao[]): void {
  // O último estado que o servidor confirmou de cada produto: é para ele que o botão volta numa recusa
  const confirmado = new Map(secoes.flatMap((s) => s.produtos).map((p) => [p.id, p.esgotado]))
  const redesenhar = (): void => { if (aberta()) render(root, secoes, alternar) }

  const sincronizar = async (p: Produto): Promise<void> => {
    if (emEnvio.has(p.id)) return // o envio em andamento confere o desejado quando voltar
    emEnvio.add(p.id)
    try {
      while (p.esgotado !== confirmado.get(p.id)) {
        const desejado = p.esgotado
        const salvo = await definirEsgotado(p.id, desejado)
        confirmado.set(p.id, salvo.esgotado)
        // Ninguém tocou de novo enquanto viajava: confirma
        if (p.esgotado === desejado) {
          p.esgotado = salvo.esgotado
          avisar(salvo.esgotado ? `${p.nome}: esgotado.` : `${p.nome} voltou.`)
        }
      }
    } catch (e) {
      if (descartada(e)) return
      p.esgotado = confirmado.get(p.id) ?? p.esgotado
      avisar(e instanceof ApiError ? e.message : 'Não deu para salvar. Tente de novo.', true)
    } finally {
      emEnvio.delete(p.id)
      redesenhar()
    }
  }

  const alternar = (p: Produto): void => {
    p.esgotado = !p.esgotado
    redesenhar() // otimista: o toque responde na hora, mesmo com sinal fraco
    void sincronizar(p)
  }
  redesenhar()
}

function render (root: HTMLElement, secoes: Secao[], alternar: (p: Produto) => void): void {
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
        h('ul', { class: 'menu-list' }, ...s.produtos.map((p) => linha(p, alternar)))
      )
    )
  )
}

function linha (p: Produto, alternar: (p: Produto) => void): HTMLLIElement {
  const botao = h('button', {
    type: 'button',
    class: p.esgotado ? 'sold on' : 'sold',
    'aria-pressed': String(p.esgotado),
    'aria-label': p.esgotado ? `Voltar a vender ${p.nome}` : `Marcar ${p.nome} como esgotado`,
    onclick: () => { alternar(p) }
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
