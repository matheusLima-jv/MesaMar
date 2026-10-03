import { ApiError, cardapio, lancarPedido, novoId, reais, type Comanda, type Secao } from '../api'
import { h, mount } from '../dom'
import { avisar } from '../toast'

/**
 * "Lançar pedido para cliente": a equipe escolhe os itens pela comanda (cliente sem celular, sem bateria ou sem sinal).
 * O pedido entra na mesma fila, marcado como lançado pela equipe. O id nasce ao abrir a tela e vale para todas as
 * tentativas: reenviar não duplica.
 */
/** Ao recarregar o cardápio (o preço mudou): o que já estava escolhido e o aviso para conferir. */
interface Retomada { quantidades: Map<string, number>, aviso: string }

export function lancar (root: HTMLElement, comanda: Comanda, voltar: () => void, retomada?: Retomada): void {
  mount(root, h('p', { class: 'loading' }, 'Carregando o cardápio…'))
  cardapio()
    .then((secoes) => desenhar(root, comanda, secoes, voltar, retomada))
    .catch((e: unknown) => {
      mount(root, h('div', { class: 'empty' },
        h('p', {}, e instanceof ApiError ? e.message : 'Não deu para carregar o cardápio.'),
        h('button', { type: 'button', class: 'btn btn-line', onclick: () => lancar(root, comanda, voltar) }, 'Tentar de novo'),
        h('button', { type: 'button', class: 'link center-block', onclick: voltar }, 'Voltar')
      ))
    })
}

function desenhar (root: HTMLElement, comanda: Comanda, secoes: Secao[], voltar: () => void, retomada?: Retomada): void {
  const id = novoId()
  const produtos = secoes.flatMap((s) => s.produtos)
  // Retomando: só o que ainda dá para pedir
  const quantidades = new Map<string, number>(
    Array.from(retomada?.quantidades ?? []).filter(([pid]) => produtos.some((p) => p.id === pid && !p.esgotado))
  )

  const resumo = h('p', { class: 'lancar-total', role: 'status' })
  const erro = h('p', { class: 'alert', role: 'alert', hidden: retomada === undefined }, retomada?.aviso ?? '')
  const botao = h('button', { type: 'button', class: 'btn btn-ink btn-block btn-big' }, 'Lançar pedido')

  const total = (): number => produtos.reduce((soma, p) => soma + (quantidades.get(p.id) ?? 0) * p.precoCentavos, 0)
  const unidades = (): number => Array.from(quantidades.values()).reduce((a, b) => a + b, 0)
  const atualizar = (): void => {
    resumo.textContent = unidades() === 0 ? 'Nenhum item escolhido.' : `${unidades()} ${unidades() === 1 ? 'item' : 'itens'} · ${reais(total())}`
    botao.disabled = unidades() === 0
  }

  const contador = (nome: string): { valor: HTMLElement, mais: HTMLButtonElement, menos: HTMLButtonElement } => ({
    valor: h('output', { class: 'qtd-valor' }, '0'),
    mais: h('button', { type: 'button', class: 'qtd-btn', 'aria-label': `Mais um ${nome}` }, '+'),
    menos: h('button', { type: 'button', class: 'qtd-btn', 'aria-label': `Menos um ${nome}` }, '−')
  })

  const linhas = secoes.filter((s) => s.produtos.length > 0).map((s) =>
    h('section', { class: 'menu-sec', 'aria-label': s.nome },
      h('h2', {}, s.nome),
      h('ul', { class: 'menu-list' }, ...s.produtos.map((p) => {
        const c = contador(p.nome)
        c.valor.textContent = String(quantidades.get(p.id) ?? 0)
        const mexer = (delta: number): void => {
          const nova = Math.min(20, Math.max(0, (quantidades.get(p.id) ?? 0) + delta))
          if (nova === 0) quantidades.delete(p.id)
          else quantidades.set(p.id, nova)
          c.valor.textContent = String(nova)
          atualizar()
        }
        c.mais.addEventListener('click', () => mexer(1))
        c.menos.addEventListener('click', () => mexer(-1))
        return h('li', { class: p.esgotado ? 'is-sold' : '' },
          h('div', { class: 'menu-main' },
            h('strong', {}, p.nome),
            p.esgotado ? h('span', { class: 'tag' }, 'Esgotado') : null,
            h('span', { class: 'price' }, p.preco)
          ),
          p.esgotado ? null : h('div', { class: 'qtd' }, c.menos, c.valor, c.mais)
        )
      }))
    )
  )

  botao.addEventListener('click', () => {
    erro.hidden = true
    botao.disabled = true
    botao.textContent = 'Lançando…'
    // Vai o preço que está na tela: se o dono mudou o preço nesse meio-tempo, o servidor pede para conferir
    const itens = Array.from(quantidades, ([produtoId, quantidade]) =>
      ({ produtoId, quantidade, precoCentavos: produtos.find((p) => p.id === produtoId)?.precoCentavos }))
    lancarPedido(comanda.id, { id, itens })
      .then((p) => {
        avisar(`Pedido ${p.numero} lançado para ${comanda.nome}.`)
        voltar()
      })
      .catch((e: unknown) => {
        if (e instanceof ApiError && (e.codigo === 'preco-mudou' || e.codigo === 'indisponivel')) {
          // Cardápio novo (preço de agora, esgotados fora), mantendo o que já estava escolhido
          lancar(root, comanda, voltar, { quantidades, aviso: e.message })
          return
        }
        botao.textContent = 'Lançar pedido'
        atualizar()
        erro.textContent = e instanceof ApiError ? e.message : 'Não deu para lançar. Tente de novo.'
        erro.hidden = false
      })
  })

  mount(root,
    h('header', { class: 'tab-head' },
      h('h1', {}, 'Lançar pedido'),
      h('p', { class: 'hint' }, `Para ${comanda.nome} · ${comanda.guardaSol === null ? 'sem guarda-sol' : `guarda-sol ${comanda.guardaSol}`}`)
    ),
    ...linhas,
    h('div', { class: 'lancar-rodape' }, resumo, erro, botao,
      h('button', { type: 'button', class: 'link center-block', onclick: voltar }, 'Cancelar')
    )
  )
  atualizar()
}
