import { ApiError, descartada, mudarStatus, pausar, type Fila, type PedidoFila } from '../api'
import { h, mount } from '../dom'
import { agoraDoServidor, ehNovo, estado, observar, recarregar } from '../fila-store'
import { definirSom, somLigado } from '../som'
import { avisar } from '../toast'
import { dialogoCancelar } from './cancelar'
import { dialogoPausar } from './pausar'

type Modo = 'todos' | 'pendentes'

// Filtros e pedidos em andamento: ficam fora da tela para sobreviver a cada redesenho
let modo: Modo = 'todos'
let guardaSolEscolhido = ''
const ocupados = new Set<string>()
let sairDaTela: (() => void) | null = null

/**
 * Aba Fila: o que o quiosque ainda precisa atender, **o mais antigo em cima** (ordem de chegada pelo relógio do
 * servidor). A tela se atualiza sozinha quando chega ou muda um pedido.
 */
export function abaFila (root: HTMLElement): void {
  sairDaTela?.()
  const desenhar = (): void => {
    // Trocou de aba: para de ouvir e de contar o tempo
    if (location.hash !== '#/fila') { sairDaTela?.(); return }
    render(root)
  }
  const parar = observar(desenhar)
  // A espera de cada pedido cresce com o tempo, mesmo sem novidade no servidor
  const relogio = window.setInterval(desenhar, 20_000)
  sairDaTela = () => { parar(); window.clearInterval(relogio); sairDaTela = null }
  render(root)
  void recarregar()
}

/** Minutos de espera pelo horário do servidor. */
export function minutosDeEspera (criadoEm: string): number {
  return Math.max(0, Math.floor((agoraDoServidor() - Date.parse(criadoEm)) / 60_000))
}

function textoDeEspera (min: number): string {
  if (min < 1) return 'agora'
  if (min < 60) return `há ${min} min`
  return `há ${Math.floor(min / 60)} h ${String(min % 60).padStart(2, '0')} min`
}

/** Verde até metade do tempo configurado, amarelo até o tempo, vermelho depois (e escrito, para quem não distingue cor). */
export function nivelDeEspera (min: number, alertaMin: number): 'ok' | 'atencao' | 'atraso' {
  if (min >= alertaMin) return 'atraso'
  return min >= alertaMin / 2 ? 'atencao' : 'ok'
}

function render (root: HTMLElement): void {
  const { dados, conectado, falhou } = estado()
  if (dados === null) {
    mount(root,
      h('header', { class: 'tab-head' }, h('h1', {}, 'Fila')),
      falhou
        ? h('div', { class: 'empty' },
          h('p', {}, 'Não deu para carregar a fila. Confira o sinal.'),
          h('button', { type: 'button', class: 'btn btn-line', onclick: () => { void recarregar() } }, 'Tentar de novo'))
        : h('p', { class: 'loading' }, 'Carregando a fila…')
    )
    return
  }

  const guardaSois = [...new Set(dados.pedidos.map((p) => p.guardaSol))]
    .sort((a, b) => (a ?? Infinity) - (b ?? Infinity))
  if (guardaSolEscolhido !== '' && !guardaSois.some((g) => chave(g) === guardaSolEscolhido)) guardaSolEscolhido = ''
  const lista = dados.pedidos
    .filter((p) => modo === 'todos' || p.status === 'RECEBIDO')
    .filter((p) => guardaSolEscolhido === '' || chave(p.guardaSol) === guardaSolEscolhido)
  const pendentes = dados.pedidos.filter((p) => p.status === 'RECEBIDO').length

  const filtro = (id: Modo, rotulo: string): HTMLButtonElement =>
    h('button', {
      type: 'button',
      class: modo === id ? 'on' : '',
      'aria-pressed': String(modo === id),
      onclick: () => { modo = id; render(root) }
    }, rotulo)
  const seletor = h('select', {
    id: 'filtro-gs',
    onchange: (ev: Event) => { guardaSolEscolhido = (ev.target as HTMLSelectElement).value; render(root) }
  },
  h('option', { value: '' }, 'Todos'),
  ...guardaSois.map((g) => h('option', { value: chave(g), selected: chave(g) === guardaSolEscolhido }, g === null ? 'Balcão' : `Guarda-sol ${g}`))
  )

  const total = dados.pedidos.length
  mount(root,
    h('header', { class: 'tab-head' },
      h('h1', {}, 'Fila'),
      h('p', { class: 'hint' }, total === 0
        ? 'Nenhum pedido esperando.'
        : `${total} ${total === 1 ? 'pedido' : 'pedidos'}, o mais antigo em cima.`),
      h('div', { class: 'fila-barra' },
        h('span', { class: conectado ? 'live on' : 'live off', role: 'status' }, conectado ? 'Ao vivo' : 'Reconectando…'),
        botaoSom(root)
      )
    ),
    blocoPausa(dados),
    total === 0
      ? h('div', { class: 'empty' }, h('h2', {}, 'Tudo em dia'), h('p', {}, 'Quando um cliente pedir pelo celular, o pedido aparece aqui sozinho.'))
      : h('div', { class: 'fila-filtros' },
        h('div', { class: 'segmented', role: 'group', 'aria-label': 'Quais pedidos mostrar' },
          filtro('todos', `Todos (${total})`),
          filtro('pendentes', `Pendentes (${pendentes})`)
        ),
        h('div', { class: 'field filtro-gs' }, h('label', { for: 'filtro-gs' }, 'Guarda-sol'), seletor)
      ),
    total > 0 && lista.length === 0
      ? h('p', { class: 'hint center' }, 'Nenhum pedido com esse filtro.')
      : h('ul', { class: 'fila' }, ...lista.map((p) => cartao(root, p, dados.tempoAlertaMin)))
  )
}

/** "Pausar pedidos" (ou, se já está pausado, o aviso e o botão para voltar a receber). */
function blocoPausa (dados: Fila): HTMLElement {
  if (!dados.pedidosPausados) {
    return h('button', {
      type: 'button',
      class: 'btn btn-line btn-block btn-pausa',
      onclick: () => { dialogoPausar(() => { void recarregar() }) }
    }, 'Pausar pedidos')
  }
  const voltar = h('button', { type: 'button', class: 'btn btn-sun btn-block' }, 'Voltar a receber pedidos')
  voltar.addEventListener('click', () => {
    voltar.disabled = true
    pausar(false)
      .then(() => { avisar('Voltou a receber pedidos.') })
      .catch((e: unknown) => { if (!descartada(e)) avisar(e instanceof ApiError ? e.message : 'Não deu para salvar. Tente de novo.', true) })
      .finally(() => { void recarregar() })
  })
  return h('div', { class: 'pausa', role: 'status' },
    h('p', {}, h('strong', {}, 'Pedidos pausados.'), ' ', dados.mensagemPausa ?? 'Os clientes veem que o quiosque não está recebendo pedidos agora.'),
    voltar
  )
}

const chave = (g: number | null): string => (g === null ? 'balcao' : String(g))

function botaoSom (root: HTMLElement): HTMLButtonElement {
  const ligado = somLigado()
  return h('button', {
    type: 'button',
    class: 'sold som',
    'aria-pressed': String(ligado),
    onclick: () => { definirSom(!ligado); render(root) }
  }, ligado ? 'Som: ligado' : 'Som: desligado')
}

function cartao (root: HTMLElement, p: PedidoFila, alertaMin: number): HTMLLIElement {
  const min = minutosDeEspera(p.criadoEm)
  const nivel = nivelDeEspera(min, alertaMin)
  const ocupado = ocupados.has(p.id)

  const agir = (novo: 'EM_PREPARO' | 'ENTREGUE'): void => {
    ocupados.add(p.id)
    render(root)
    mudarStatus(p.id, novo)
      .then(() => { avisar(novo === 'ENTREGUE' ? `Pedido nº ${p.numero} entregue.` : `Pedido nº ${p.numero} em preparo.`) })
      .catch((e: unknown) => { if (!descartada(e)) avisar(e instanceof ApiError ? e.message : 'Não deu para salvar. Tente de novo.', true) })
      .finally(() => {
        ocupados.delete(p.id)
        void recarregar()
      })
  }

  return h('li', { class: `pedido espera-${nivel}${ehNovo(p.id) ? ' novo' : ''}`, 'data-pedido': String(p.numero) },
    h('div', { class: 'pedido-topo' },
      h('span', { class: 'gs' }, p.guardaSol === null ? 'Balcão' : `Guarda-sol ${p.guardaSol}`),
      h('span', { class: 'num', 'aria-label': `Pedido número ${p.numero}` }, `#${p.numero}`)
    ),
    h('p', { class: 'quem' },
      h('strong', {}, p.cliente),
      ' · ',
      h('span', { class: 'espera' }, textoDeEspera(min)),
      nivel === 'atraso' ? h('span', { class: 'tag' }, 'Atrasado') : null,
      ehNovo(p.id) ? h('span', { class: 'tag tag-novo' }, 'Novo') : null,
      p.status === 'EM_PREPARO' ? h('span', { class: 'tag tag-preparo' }, 'Em preparo') : null
    ),
    h('ul', { class: 'linhas' }, ...p.itens.map((i) =>
      h('li', { class: i.cancelado ? 'is-sold' : '' },
        h('strong', {}, `${i.quantidade}× ${i.nome}`),
        i.cancelado ? h('span', { class: 'tag' }, `Cancelado: ${i.motivoCancelamento ?? ''}`.trim()) : null,
        i.observacao !== null ? h('span', { class: 'obs' }, i.observacao) : null
      )
    )),
    h('div', { class: 'acoes' },
      p.status === 'RECEBIDO'
        ? h('button', {
          type: 'button', class: 'btn btn-line', disabled: ocupado, 'aria-label': `Em preparo: pedido ${p.numero}`, onclick: () => { agir('EM_PREPARO') }
        }, 'Em preparo')
        : null,
      h('button', {
        type: 'button', class: 'btn btn-sun', disabled: ocupado, 'aria-label': `Entregue: pedido ${p.numero}`, onclick: () => { agir('ENTREGUE') }
      }, 'Entregue'),
      h('button', {
        type: 'button', class: 'link', disabled: ocupado, 'aria-label': `Cancelar: pedido ${p.numero}`, onclick: () => { dialogoCancelar(p, () => { void recarregar() }) }
      }, 'Cancelar…')
    )
  )
}
