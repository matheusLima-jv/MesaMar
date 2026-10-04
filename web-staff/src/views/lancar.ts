import { ApiError, cardapio, descartada, lancarPedido, novoId, reais, type Comanda, type ItemLancado, type Secao } from '../api'
import { abrirTela, h, mount } from '../dom'
import { cardapioMudou } from '../fila-store'
import { pendencias } from '../pendencias'
import { avisar } from '../toast'

/** Ao recarregar o cardápio (o preço mudou, algo esgotou): o que já estava escolhido e o aviso para conferir. */
interface Retomada { quantidades: Map<string, number>, aviso: string }

/** O lançamento que saiu e ficou sem resposta, com o que mostrar enquanto ele não se resolve. */
interface Lancamento { itens: ItemLancado[], linhas: string[], totalCentavos: number }

const AVISO_EXCECAO = 'Lançamento pela equipe: você autoriza o pedido mesmo com pedidos pausados ou limite de saldo atingido. Não há teto de saldo para a equipe. O servidor registra o autor, horário e quais condições foram ignoradas ao receber o pedido, inclusive após sincronizar sem sinal.'

/**
 * "Lançar pedido para cliente": a equipe escolhe os itens pela comanda (cliente sem celular, sem bateria ou sem sinal).
 * O pedido entra na mesma fila, marcado como lançado pela equipe.
 *
 * Um envio sem resposta (sinal caiu, prazo estourou) fica guardado no aparelho com o seu id: voltar a esta comanda,
 * trocar de aba ou recarregar o app mostra o mesmo lançamento para reenviar, sem deixar mudar os itens (o servidor
 * reconhece o id; um id repetido com itens diferentes devolveria o pedido antigo).
 */
export function lancar (root: HTMLElement, comanda: Comanda, voltar: () => void, retomada?: Retomada): void {
  const aberta = abrirTela(root)
  const pendente = pendencias.ler<Lancamento>('lancamento', comanda.id)
  if (pendente !== null) {
    telaPendente(root, comanda, voltar, pendente.id, pendente.dados)
    return
  }
  mount(root, h('p', { class: 'loading' }, 'Carregando o cardápio…'))
  cardapio()
    .then((secoes) => { if (aberta()) desenhar(root, aberta, comanda, secoes, voltar, retomada) })
    .catch((e: unknown) => {
      if (!aberta() || descartada(e)) return
      mount(root, h('div', { class: 'empty' },
        h('p', {}, e instanceof ApiError ? e.message : 'Não deu para carregar o cardápio.'),
        h('button', { type: 'button', class: 'btn btn-line', onclick: () => lancar(root, comanda, voltar, retomada) }, 'Tentar de novo'),
        h('button', { type: 'button', class: 'link center-block', onclick: voltar }, 'Voltar')
      ))
    })
}

/**
 * Envia e resolve o que fazer com a resposta. Sucesso ou recusa do servidor: a pendência sai. Sem resposta: fica, e
 * a tela passa a mostrar o lançamento pendente.
 */
function enviar (
  root: HTMLElement,
  aberta: () => boolean,
  comanda: Comanda,
  voltar: () => void,
  id: string,
  lancamento: Lancamento,
  aoRecusar: (e: ApiError) => void
): void {
  // Guardado ANTES de sair: se o app fechar no meio, o próximo envio usa o mesmo id
  try { pendencias.guardar('lancamento', comanda.id, id, lancamento) } catch {
    aoRecusar(new ApiError(400, 'validacao', 'Não deu para preparar o pedido. Use até 30 produtos diferentes e 60 unidades; divida em dois pedidos se necessário.'))
    return
  }
  lancarPedido(comanda.id, { id, itens: lancamento.itens })
    .then((p) => {
      pendencias.apagar('lancamento', comanda.id)
      const excecao = (p.excecoes?.length ?? 0) > 0 ? ` Exceção registrada: ${p.excecoes?.join(' e ')} ignorado(s).` : ''
      avisar(`Pedido ${p.numero} lançado para ${comanda.nome}.${excecao}`)
      if (aberta()) voltar()
    })
    .catch((e: unknown) => {
      if (descartada(e)) return
      if (e instanceof ApiError && e.status >= 400 && e.status < 500 && !e.temporario) {
        // O servidor recusou: nada foi gravado, dá para corrigir e enviar outro
        pendencias.apagar('lancamento', comanda.id)
        if (aberta()) aoRecusar(e)
        else avisar(e.message, true)
        return
      }
      // Sem resposta: o pedido pode ter chegado. Fica pendente, com os itens travados
      if (aberta()) telaPendente(root, comanda, voltar, id, lancamento)
      else avisar(`O pedido de ${comanda.nome} ficou sem resposta. Abra Pedir de novo para conferir.`, true)
    })
}

function telaPendente (root: HTMLElement, comanda: Comanda, voltar: () => void, id: string, lancamento: Lancamento): void {
  const aberta = abrirTela(root)
  const erro = h('p', { class: 'alert', role: 'alert', hidden: true })
  const botao = h('button', { type: 'button', class: 'btn btn-ink btn-block btn-big' }, 'Reenviar o pedido')
  botao.addEventListener('click', () => {
    botao.disabled = true
    botao.textContent = 'Reenviando…'
    erro.hidden = true
    enviar(root, aberta, comanda, voltar, id, lancamento, (e) => {
      // Recusado agora (ex.: a comanda foi encerrada): mostra o motivo e volta a deixar montar outro pedido
      lancar(root, comanda, voltar, { quantidades: new Map(lancamento.itens.map((i) => [i.produtoId, i.quantidade])), aviso: e.message })
    })
  })
  mount(root,
    h('header', { class: 'tab-head' },
      h('h1', {}, 'Lançar pedido'),
      h('p', { class: 'hint' }, `Para ${comanda.nome} · ${comanda.guardaSol === null ? 'sem guarda-sol' : `guarda-sol ${comanda.guardaSol}`}`)
    ),
    h('p', { class: 'alert', role: 'status' },
      'Pedido preservado neste aparelho. Será reenviado em ordem quando o sinal voltar. Se já tiver chegado, não será duplicado.'),
    h('p', { class: 'alert', role: 'note' }, AVISO_EXCECAO),
    h('ul', { class: 'linhas' }, ...lancamento.linhas.map((l) => h('li', {}, l))),
    h('p', { class: 'lancar-total' }, reais(lancamento.totalCentavos)),
    erro,
    botao,
    h('button', { type: 'button', class: 'link center-block', onclick: voltar }, 'Voltar')
  )
}

function desenhar (root: HTMLElement, aberta: () => boolean, comanda: Comanda, secoes: Secao[], voltar: () => void, retomada?: Retomada): void {
  let id = novoId()
  let enviando = false
  const produtos = secoes.flatMap((s) => s.produtos)
  // Retomando: só o que ainda dá para pedir
  const quantidades = new Map<string, number>(
    Array.from(retomada?.quantidades ?? []).filter(([pid]) => produtos.some((p) => p.id === pid && !p.esgotado))
  )

  const resumo = h('p', { class: 'lancar-total', role: 'status' })
  const erro = h('p', { class: 'alert', role: 'alert', hidden: retomada === undefined }, retomada?.aviso ?? '')
  const botao = h('button', { type: 'button', class: 'btn btn-ink btn-block btn-big' }, 'Lançar pedido')
  const controles: HTMLButtonElement[] = []

  const total = (): number => produtos.reduce((soma, p) => soma + (quantidades.get(p.id) ?? 0) * p.precoCentavos, 0)
  const unidades = (): number => Array.from(quantidades.values()).reduce((a, b) => a + b, 0)
  const atualizar = (): void => {
    resumo.textContent = unidades() === 0 ? 'Nenhum item escolhido.' : `${unidades()} ${unidades() === 1 ? 'item' : 'itens'} · ${reais(total())}`
    botao.disabled = enviando || unidades() === 0
    // Enquanto o pedido viaja, nada muda: o mesmo id nunca vai com itens diferentes
    controles.forEach((b) => { b.disabled = enviando })
  }

  const linhas = secoes.filter((s) => s.produtos.length > 0).map((s) =>
    h('section', { class: 'menu-sec', 'aria-label': s.nome },
      h('h2', {}, s.nome),
      h('ul', { class: 'menu-list' }, ...s.produtos.map((p) => {
        const valor = h('output', { class: 'qtd-valor' }, String(quantidades.get(p.id) ?? 0))
        const mexer = (delta: number): void => {
          if (enviando) return
          if (delta > 0 && ((quantidades.get(p.id) ?? 0) === 0 && quantidades.size >= 30)) {
            erro.textContent = 'O pedido pode ter até 30 produtos diferentes. Envie este pedido e monte outro para os demais.'
            erro.hidden = false
            return
          }
          if (delta > 0 && unidades() >= 60) {
            erro.textContent = 'O pedido pode ter até 60 unidades. Envie este pedido e monte outro para os demais.'
            erro.hidden = false
            return
          }
          const nova = Math.min(20, Math.max(0, (quantidades.get(p.id) ?? 0) + delta))
          if (nova === 0) quantidades.delete(p.id)
          else quantidades.set(p.id, nova)
          valor.textContent = String(nova)
          atualizar()
        }
        const mais = h('button', { type: 'button', class: 'qtd-btn', 'aria-label': `Mais um ${p.nome}`, onclick: () => mexer(1) }, '+')
        const menos = h('button', { type: 'button', class: 'qtd-btn', 'aria-label': `Menos um ${p.nome}`, onclick: () => mexer(-1) }, '−')
        controles.push(mais, menos)
        return h('li', { class: p.esgotado ? 'is-sold' : '' },
          h('div', { class: 'menu-main' },
            h('strong', {}, p.nome),
            p.esgotado ? h('span', { class: 'tag' }, 'Esgotado') : null,
            h('span', { class: 'price' }, p.preco)
          ),
          p.esgotado ? null : h('div', { class: 'qtd' }, menos, valor, mais)
        )
      }))
    )
  )

  // O cardápio mudou em outro aparelho (preço, esgotado): recarrega mantendo o que já foi escolhido
  const aoMudarCardapio = (): void => {
    if (!aberta()) { cardapioMudou.removeEventListener('mudou', aoMudarCardapio); return }
    if (enviando) return
    cardapioMudou.removeEventListener('mudou', aoMudarCardapio)
    lancar(root, comanda, voltar, { quantidades, aviso: 'O cardápio mudou agora há pouco. Confira os itens e os preços.' })
  }
  cardapioMudou.addEventListener('mudou', aoMudarCardapio)

  botao.addEventListener('click', () => {
    if (enviando || unidades() === 0) return
    erro.hidden = true
    enviando = true
    botao.textContent = 'Lançando…'
    atualizar()
    const escolhidos = produtos.filter((p) => (quantidades.get(p.id) ?? 0) > 0)
    const lancamento: Lancamento = {
      // Vai o preço que está na tela: se o dono mudou o preço nesse meio-tempo, o servidor pede para conferir
      itens: escolhidos.map((p) => ({ produtoId: p.id, quantidade: quantidades.get(p.id) ?? 0, precoCentavos: p.precoCentavos })),
      linhas: escolhidos.map((p) => `${quantidades.get(p.id) ?? 0}× ${p.nome}`),
      totalCentavos: total()
    }
    enviar(root, aberta, comanda, voltar, id, lancamento, (e) => {
      if (e.codigo === 'preco-mudou' || e.codigo === 'indisponivel') {
        // Cardápio novo (preço de agora, esgotados fora), mantendo o que já estava escolhido
        lancar(root, comanda, voltar, { quantidades, aviso: e.message })
        return
      }
      // Recusa definitiva (comanda bloqueada, encerrada…): nada foi gravado. O próximo envio é outro pedido
      id = novoId()
      enviando = false
      botao.textContent = 'Lançar pedido'
      atualizar()
      erro.textContent = e.message
      erro.hidden = false
    })
  })

  mount(root,
    h('header', { class: 'tab-head' },
      h('h1', {}, 'Lançar pedido'),
      h('p', { class: 'hint' }, `Para ${comanda.nome} · ${comanda.guardaSol === null ? 'sem guarda-sol' : `guarda-sol ${comanda.guardaSol}`}`)
    ),
    h('p', { class: 'alert', role: 'note' }, AVISO_EXCECAO),
    ...linhas,
    h('div', { class: 'lancar-rodape' }, resumo, erro, botao,
      h('button', { type: 'button', class: 'link center-block', onclick: voltar }, 'Cancelar')
    )
  )
  atualizar()
}
