import { ApiError, descartada, bloquearComanda, desbloquearComanda, continuarComanda, encerrarComanda, reais, type ContaAPagar, type Sessao } from '../api'
import { adiarSeEmUso, mountPreservando } from '../a11y'
import { h } from '../dom'
import { estado as estadoDaFila } from '../fila-store'
import { agoraDoServidor, estado, observar, recarregar, mudarPagina } from '../pagamentos-store'
import { avisar } from '../toast'
import { dialogoFecharSemReceber, dialogoReceber } from './receber'

const ocupados = new Set<string>()
let sairDaTela: (() => void) | null = null

/**
 * Aba Pagamentos: quem tocou em "fechar a conta" (por ordem de aviso, o mais antigo em cima) e, embaixo, as contas
 * abertas com saldo, as maiores primeiro, para o dono enxergar quem está devendo. Se atualiza sozinha quando o
 * cliente avisa que vai pagar.
 */
export function abaPagamentos (root: HTMLElement, sessao: Sessao): void {
  sairDaTela?.()
  const desenhar = (): void => {
    // Trocou de aba: para de ouvir
    if (location.hash !== '#/pagamentos') { sairDaTela?.(); return }
    // Escolhendo num select ou digitando: não redesenha por baixo da pessoa (espera ela sair do campo)
    if (adiarSeEmUso(root, desenhar)) return
    render(root, sessao)
  }
  const parar = observar(desenhar)
  // "Avisou há N min" cresce com o tempo, mesmo sem novidade no servidor
  const relogio = window.setInterval(desenhar, 20_000)
  sairDaTela = () => { parar(); window.clearInterval(relogio); sairDaTela = null }
  render(root, sessao)
  void recarregar()
}

function textoDeEspera (desde: string): string {
  const min = Math.max(0, Math.floor((agoraDoServidor() - Date.parse(desde)) / 60_000))
  if (min < 1) return 'agora'
  if (min < 60) return `há ${min} min`
  return `há ${Math.floor(min / 60)} h ${String(min % 60).padStart(2, '0')} min`
}

const local = (c: ContaAPagar): string => (c.guardaSol === null ? 'Balcão' : `Guarda-sol ${c.guardaSol}`)

function render (root: HTMLElement, sessao: Sessao): void {
  const { dados, falhou } = estado()
  const { conectado } = estadoDaFila()
  if (dados === null) {
    mountPreservando(root,
      h('header', { class: 'tab-head' }, h('h1', {}, 'Pagamentos')),
      falhou
        ? h('div', { class: 'empty' },
          h('p', {}, 'Não deu para carregar os pagamentos. Confira o sinal.'),
          h('button', { type: 'button', class: 'btn btn-line', onclick: () => { void recarregar() } }, 'Tentar de novo'))
        : h('p', { class: 'loading' }, 'Carregando os pagamentos…')
    )
    return
  }
  const dono = sessao.usuario?.papel === 'DONO'
  const { aguardando, abertas } = dados
  const devendo = abertas.reduce((soma, c) => soma + c.saldoCentavos, 0)
  mountPreservando(root,
    h('header', { class: 'tab-head' },
      h('h1', {}, 'Pagamentos'),
      h('p', { class: 'hint' }, `Total em aberto: ${reais(dados.totalSaldoAbertoCentavos)}`),
      h('nav', { 'aria-label': 'Páginas de pagamentos', class: 'acoes' },
        h('button', { type: 'button', class: 'btn btn-line', disabled: dados.pagina === 0, onclick: () => { mudarPagina(dados.pagina - 1) } }, 'Anterior'),
        h('span', {}, `Página ${dados.pagina + 1}`),
        h('button', { type: 'button', class: 'btn btn-line', disabled: !dados.temMais, onclick: () => { mudarPagina(dados.pagina + 1) } }, 'Próxima')),
      h('p', { class: 'hint' }, aguardando.length === 0
        ? 'Ninguém avisou que vai pagar.'
        : `${aguardando.length} ${aguardando.length === 1 ? 'conta aguarda' : 'contas aguardam'} o recebimento, a mais antiga em cima.`),
      h('div', { class: 'fila-barra' },
        h('span', { class: conectado ? 'live on' : 'live off', role: 'status' }, conectado ? 'Ao vivo' : 'Reconectando…')
      )
    ),
    aguardando.length === 0
      ? h('div', { class: 'empty' }, h('h2', {}, 'Nada para receber'), h('p', {}, 'Quando um cliente tocar em "Fechar conta", ele aparece aqui sozinho, com o valor e a forma de pagamento.'))
      : h('ul', { class: 'fila', 'aria-label': 'Contas aguardando pagamento' }, ...aguardando.map((c) => cartao(root, sessao, c, dono))),
    h('section', { class: 'devendo', 'aria-labelledby': 'devendo-titulo' },
      h('h2', { id: 'devendo-titulo' }, `Contas abertas com saldo (${abertas.length})`),
      abertas.length === 0
        ? h('p', { class: 'hint' }, 'Nenhuma conta aberta com saldo.')
        : h('p', { class: 'hint' }, `${reais(devendo)} em aberto nesta página. As maiores primeiro.`),
      abertas.length > 0 ? h('ul', { class: 'fila', 'aria-label': 'Contas abertas com saldo' }, ...abertas.map((c) => cartao(root, sessao, c, dono))) : ''
    )
  )
}

/** O que o dono precisa saber de relance: quanto, como o cliente vai pagar e há quanto tempo avisou. */
function resumo (c: ContaAPagar): Array<Node | string> {
  if (c.status === 'AGUARDANDO_PAGAMENTO') {
    if (c.metodo === 'PIX') {
      return c.avisoEm !== null
        ? [h('strong', {}, 'Pix'), ` · avisou que pagou ${textoDeEspera(c.avisoEm)}`, h('span', { class: 'aviso-pix' }, 'Confira no app do banco antes de confirmar.')]
        : [h('strong', {}, 'Pix'), ' · ainda não avisou que pagou']
    }
    const forma = c.metodo === 'DINHEIRO' ? 'Dinheiro' : 'Cartão'
    const partes: Array<Node | string> = [h('strong', {}, forma), c.avisoEm !== null ? ` · avisou ${textoDeEspera(c.avisoEm)}` : '']
    if (c.metodo === 'DINHEIRO' && c.trocoParaCentavos !== null) {
      partes.push(h('span', { class: 'troco-previsto' }, `Troco para ${reais(c.trocoParaCentavos)}: devolver ${reais(Math.max(0, c.trocoParaCentavos - c.saldoCentavos))}`))
    }
    return partes
  }
  return [`Aberta ${textoDeEspera(c.abertaEm)}`]
}

function cartao (root: HTMLElement, sessao: Sessao, c: ContaAPagar, dono: boolean): HTMLLIElement {
  const ocupado = ocupados.has(c.comandaId)
  const bloqueada = c.status === 'BLOQUEADA'

  const agir = (chamada: () => Promise<unknown>, sucesso: string): void => {
    ocupados.add(c.comandaId)
    render(root, sessao)
    chamada()
      .then(() => { avisar(sucesso) })
      .catch((e: unknown) => { if (!descartada(e)) avisar(e instanceof ApiError ? e.message : 'Não deu para salvar. Tente de novo.', true) })
      .finally(() => {
        ocupados.delete(c.comandaId)
        void recarregar()
      })
  }

  const acoes: HTMLElement[] = []
  if (c.status === 'AGUARDANDO_PAGAMENTO' && c.pagoCentavos > 0 && c.saldoCentavos > 0) {
    acoes.push(h('button', {
      type: 'button', class: 'btn btn-line', disabled: ocupado, 'aria-label': `Continuar conta de ${c.cliente}`,
      onclick: () => { agir(async () => await continuarComanda(c.comandaId), `Conta de ${c.cliente} aberta novamente. Pagamentos preservados; pausa e limite continuam valendo para o cliente.`) }
    }, 'Continuar conta'))
  }
  if (c.saldoCentavos > 0) {
    acoes.push(h('button', {
      type: 'button', class: 'btn btn-sun', disabled: ocupado, 'aria-label': `Receber de ${c.cliente}`, onclick: () => { dialogoReceber(c, () => { void recarregar() }) }
    }, 'Receber'))
  } else {
    acoes.push(h('button', {
      type: 'button', class: 'btn btn-sun', disabled: ocupado, 'aria-label': `Encerrar a conta de ${c.cliente}`,
      onclick: () => { agir(async () => await encerrarComanda(c.comandaId), `Conta de ${c.cliente} encerrada.`) }
    }, 'Encerrar conta'))
  }
  acoes.push(bloqueada
    ? h('button', {
      type: 'button', class: 'btn btn-line', disabled: ocupado, 'aria-label': `Desbloquear ${c.cliente}`,
      onclick: () => { agir(async () => await desbloquearComanda(c.comandaId), `${c.cliente} pode pedir de novo.`) }
    }, 'Desbloquear')
    : h('button', {
      type: 'button', class: 'btn btn-line', disabled: ocupado, 'aria-label': `Bloquear ${c.cliente}`,
      onclick: () => { agir(async () => await bloquearComanda(c.comandaId), `${c.cliente} não consegue mais pedir.`) }
    }, 'Bloquear'))
  if (dono && c.saldoCentavos > 0) {
    acoes.push(h('button', {
      type: 'button', class: 'link', disabled: ocupado, 'aria-label': `Fechar sem receber: ${c.cliente}`,
      onclick: () => { dialogoFecharSemReceber(c, () => { void recarregar() }) }
    }, 'Fechar sem receber…'))
  }

  return h('li', { class: `pedido pag${c.status === 'AGUARDANDO_PAGAMENTO' ? ' pag-aguarda' : ''}${bloqueada ? ' pag-bloqueada' : ''}`, 'data-comanda': c.cliente },
    h('div', { class: 'pedido-topo' },
      h('span', { class: 'gs' }, local(c)),
      h('span', { class: 'saldo', 'aria-label': `Falta pagar ${reais(c.saldoCentavos)}` }, reais(c.saldoCentavos))
    ),
    h('p', { class: 'quem' },
      h('strong', {}, c.cliente),
      bloqueada ? h('span', { class: 'tag' }, 'Bloqueada') : null,
      c.pedidosAguardando > 0 ? h('span', { class: 'tag tag-preparo' }, `${c.pedidosAguardando} ${c.pedidosAguardando === 1 ? 'pedido espera' : 'pedidos esperam'} o pagamento`) : null
    ),
    h('p', { class: 'resumo-pag' }, ...resumo(c)),
    c.pagoCentavos > 0 ? h('p', { class: 'hint' }, `Já pagou ${reais(c.pagoCentavos)} de ${reais(c.pedidoCentavos)}.`) : null,
    c.status === 'AGUARDANDO_PAGAMENTO' && c.pagoCentavos > 0
      ? h('p', { class: 'hint' }, 'Se o cliente quiser consumir mais, toque em Continuar conta. O saldo e os pagamentos são preservados; pausa e limite continuam valendo para os pedidos do cliente.') : null,
    h('div', { class: 'acoes' }, ...acoes)
  )
}
