import './style.css'
import './install'
import { ApiError, encerrarSessaoLocal, eventos, sessao, type Sessao } from './api'
import { anunciar } from './a11y'
import { avisosFixos, dispensarAviso, limparAvisos, observarAvisos } from './avisos-store'
import { fecharSobreposicoes, h, mount } from './dom'
import { abaCardapio } from './views/cardapio'
import { abaClientes } from './views/clientes'
import { iniciarFila, observar, pararFila, pendentes } from './fila-store'
import { limparDadosAntigos } from './pendencias'
import { religarPush } from './push'
import { abaFila } from './views/fila'
import { abaPagamentos } from './views/pagamentos'
import { aguardando, iniciarPagamentos, observar as observarPagamentos, pararPagamentos } from './pagamentos-store'
import { telaLogin } from './views/login'
import { lembrarTela } from './views/problema'
import { abaMais } from './views/mais'
import { limparAviso } from './toast'

type Aba = 'clientes' | 'fila' | 'pagamentos' | 'cardapio' | 'mais'

const ABAS: Array<{ id: Aba, rotulo: string }> = [
  { id: 'clientes', rotulo: 'Clientes' },
  { id: 'fila', rotulo: 'Fila' },
  { id: 'pagamentos', rotulo: 'Pagamentos' },
  { id: 'cardapio', rotulo: 'Cardápio' },
  { id: 'mais', rotulo: 'Mais' }
]

const root = document.getElementById('app') as HTMLElement
let atual: Sessao | null = null
let pararDeObservar: (() => void) | null = null
let pararDeObservarPagamentos: (() => void) | null = null
let pararDeObservarAvisos: (() => void) | null = null

function abaDaUrl (): Aba {
  const id = location.hash.replace('#/', '') as Aba
  // Clientes é a aba padrão: o check-in é a ação mais comum do dia.
  return ABAS.some((a) => a.id === id) ? id : 'clientes'
}

/** Campos de texto abertos = teclado na tela: a barra de abas sai do caminho (ver `body.teclado` no CSS). */
const CAMPOS_COM_TECLADO = ['INPUT', 'TEXTAREA']
function ehCampoDeTexto (el: EventTarget | null): boolean {
  if (!(el instanceof HTMLElement) || !CAMPOS_COM_TECLADO.includes(el.tagName)) return false
  return !(el instanceof HTMLInputElement) || !['checkbox', 'radio', 'button', 'submit', 'range'].includes(el.type)
}
document.addEventListener('focusin', (ev) => { if (ehCampoDeTexto(ev.target)) document.body.classList.add('teclado') })
document.addEventListener('focusout', () => {
  window.setTimeout(() => { if (!ehCampoDeTexto(document.activeElement)) document.body.classList.remove('teclado') }, 100)
})

/** A faixa de avisos que não somem (pedido parado, cliente indo pagar). Redesenhada quando a lista muda. */
function desenharAvisosFixos (faixa: HTMLElement): void {
  const lista = avisosFixos()
  faixa.hidden = lista.length === 0
  const visiveis = lista.slice(0, 3)
  faixa.replaceChildren(
    ...visiveis.map((a) =>
      h('div', { class: `aviso-fixo ${a.tipo}`, role: 'group', 'aria-label': a.tipo === 'atraso' ? 'Pedido parado' : 'Cliente indo pagar' },
        h('p', {}, a.texto),
        h('a', { href: `#/${a.aba}`, class: 'link' }, a.aba === 'fila' ? 'Ver a fila' : 'Ver pagamentos'),
        h('button', {
          type: 'button',
          class: 'link',
          'aria-label': `Dispensar o aviso: ${a.texto}`,
          onclick: () => { dispensarAviso(a.chave) }
        }, 'Entendi')
      )
    ),
    ...(lista.length > visiveis.length ? [h('p', { class: 'hint' }, `E mais ${lista.length - visiveis.length}. Veja nas abas Fila e Pagamentos.`)] : [])
  )
}

function app (s: Sessao): void {
  atual = s
  limparDadosAntigos()
  const conteudo = h('main', { id: 'conteudo', class: 'tab', tabindex: -1 })
  const nav = h('nav', { class: 'tabs', 'aria-label': 'Seções do app' })
  const faixa = h('section', { class: 'avisos-fixos', 'aria-label': 'Avisos importantes', hidden: true })

  const mostrar = (): void => {
    const aba = abaDaUrl()
    nav.replaceChildren(...ABAS.map((a) =>
      h('a', { href: `#/${a.id}`, class: a.id === aba ? 'on' : '', 'aria-current': a.id === aba ? 'page' : undefined, 'data-aba': a.id },
        a.rotulo,
        // Quantos pedidos esperam no quiosque, em qualquer aba
        a.id === 'fila' ? h('span', { class: 'contagem', hidden: pendentes() === 0, 'aria-label': `${pendentes()} esperando` }, String(pendentes())) : null,
        // Quantas contas esperam o quiosque receber, em qualquer aba
        a.id === 'pagamentos' ? h('span', { class: 'contagem', hidden: aguardando() === 0, 'aria-label': `${aguardando()} para receber` }, String(aguardando())) : null)
    ))
    // A rolagem é da página (não do <main>): é ela que volta ao topo ao trocar de aba
    window.scrollTo(0, 0)
    lembrarTela(aba)
    limparAviso()
    // Diálogos da aba anterior (receber, cancelar, pausar) não ficam por cima da aba nova
    fecharSobreposicoes()
    document.title = `${ABAS.find((a) => a.id === aba)?.rotulo ?? 'App'} · MesaMar`
    switch (aba) {
      case 'clientes': abaClientes(conteudo, s); break
      case 'fila': abaFila(conteudo); break
      case 'pagamentos': abaPagamentos(conteudo, s); break
      case 'cardapio': abaCardapio(conteudo); break
      case 'mais': abaMais(conteudo, s, voltarAoLogin); break
    }
  }

  mount(root,
    // Barraca de treino: uma faixa que não some, para ninguém receber dinheiro de verdade por engano
    ...(s.barraca?.demo === true ? [h('p', { class: 'demo-faixa', role: 'note' }, 'Barraca de treino: nada aqui é de verdade')] : []),
    h('header', { class: 'topbar' },
      h('p', { class: 'brand' }, 'MesaMar'),
      h('p', { class: 'where' }, s.barraca?.nome ?? '', h('span', {}, ` · ${s.usuario?.nome ?? ''}`))
    ),
    faixa,
    conteudo,
    nav
  )
  // Trocou de aba por toque ou teclado: o foco vai para o conteúdo novo e o leitor de tela anuncia a aba
  window.onhashchange = () => {
    mostrar()
    conteudo.focus({ preventScroll: true })
    anunciar(`Aba ${ABAS.find((a) => a.id === abaDaUrl())?.rotulo ?? ''}`)
  }
  mostrar()
  // A fila fica ouvindo o servidor o tempo todo: o número na aba e o aviso de pedido novo valem em qualquer aba
  iniciarFila()
  iniciarPagamentos()
  void religarPush()
  desenharAvisosFixos(faixa)
  pararDeObservarAvisos = observarAvisos(() => { desenharAvisosFixos(faixa) })
  pararDeObservar = observar(() => {
    const numero = pendentes()
    const contagem = nav.querySelector<HTMLElement>('[data-aba="fila"] .contagem')
    if (contagem !== null) {
      contagem.textContent = String(numero)
      contagem.hidden = numero === 0
      contagem.setAttribute('aria-label', `${numero} esperando`)
    }
  })
  pararDeObservarPagamentos = observarPagamentos(() => {
    const numero = aguardando()
    const contagem = nav.querySelector<HTMLElement>('[data-aba="pagamentos"] .contagem')
    if (contagem !== null) {
      contagem.textContent = String(numero)
      contagem.hidden = numero === 0
      contagem.setAttribute('aria-label', `${numero} para receber`)
    }
  })
}

function voltarAoLogin (): void {
  atual = null
  // Tudo o que estava em andamento é descartado: nenhuma resposta atrasada repopula a fila, os pagamentos ou uma tela
  encerrarSessaoLocal()
  fecharSobreposicoes()
  pararFila()
  pararPagamentos()
  limparAvisos()
  pararDeObservar?.()
  pararDeObservar = null
  pararDeObservarPagamentos?.()
  pararDeObservarPagamentos = null
  pararDeObservarAvisos?.()
  pararDeObservarAvisos = null
  window.onhashchange = null
  document.title = 'MesaMar · Quiosque'
  // Quem entrar depois neste celular começa na aba padrão, não na última aba de quem saiu.
  history.replaceState(null, '', location.pathname)
  telaLogin(root, app)
}

eventos.addEventListener('sessao-expirada', () => { if (atual !== null) voltarAoLogin() })

async function iniciar (): Promise<void> {
  try {
    const s = await sessao()
    if (s.logado) app(s)
    else telaLogin(root, app)
  } catch (e) {
    mount(root, h('main', { class: 'login' },
      h('p', { class: 'brand' }, 'MesaMar'),
      h('div', { class: 'card' },
        h('p', {}, e instanceof ApiError ? e.message : 'Não deu para abrir o app.'),
        h('button', { type: 'button', class: 'btn btn-ink btn-block', onclick: () => { void iniciar() } }, 'Tentar de novo')
      )
    ))
  }
}

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register(`${import.meta.env.BASE_URL}sw.js`, { scope: import.meta.env.BASE_URL }).catch(() => undefined)
  })
}

void iniciar()
