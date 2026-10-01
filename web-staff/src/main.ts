import './style.css'
import './install'
import { ApiError, eventos, sessao, type Sessao } from './api'
import { h, mount } from './dom'
import { abaCardapio } from './views/cardapio'
import { abaClientes } from './views/clientes'
import { iniciarFila, observar, pararFila, pendentes } from './fila-store'
import { religarPush } from './push'
import { abaFila } from './views/fila'
import { telaLogin } from './views/login'
import { abaMais } from './views/mais'
import { limparAviso } from './toast'

type Aba = 'clientes' | 'fila' | 'cardapio' | 'mais'

const ABAS: Array<{ id: Aba, rotulo: string }> = [
  { id: 'clientes', rotulo: 'Clientes' },
  { id: 'fila', rotulo: 'Fila' },
  { id: 'cardapio', rotulo: 'Cardápio' },
  { id: 'mais', rotulo: 'Mais' }
]

const root = document.getElementById('app') as HTMLElement
let atual: Sessao | null = null
let pararDeObservar: (() => void) | null = null

function abaDaUrl (): Aba {
  const id = location.hash.replace('#/', '') as Aba
  // Clientes é a aba padrão: o check-in é a ação mais comum do dia.
  return ABAS.some((a) => a.id === id) ? id : 'clientes'
}

function app (s: Sessao): void {
  atual = s
  const conteudo = h('main', { id: 'conteudo', class: 'tab' })
  const nav = h('nav', { class: 'tabs', 'aria-label': 'Seções do app' })

  const mostrar = (): void => {
    const aba = abaDaUrl()
    nav.replaceChildren(...ABAS.map((a) =>
      h('a', { href: `#/${a.id}`, class: a.id === aba ? 'on' : '', 'aria-current': a.id === aba ? 'page' : undefined, 'data-aba': a.id },
        a.rotulo,
        // Quantos pedidos esperam no quiosque, em qualquer aba
        a.id === 'fila' ? h('span', { class: 'contagem', hidden: pendentes() === 0, 'aria-label': `${pendentes()} esperando` }, String(pendentes())) : null)
    ))
    conteudo.scrollTop = 0
    limparAviso()
    switch (aba) {
      case 'clientes': abaClientes(conteudo, s); break
      case 'fila': abaFila(conteudo); break
      case 'cardapio': abaCardapio(conteudo); break
      case 'mais': abaMais(conteudo, s, voltarAoLogin); break
    }
  }

  mount(root,
    h('header', { class: 'topbar' },
      h('p', { class: 'brand' }, 'MesaMar'),
      h('p', { class: 'where' }, s.barraca?.nome ?? '', h('span', {}, ` · ${s.usuario?.nome ?? ''}`))
    ),
    conteudo,
    nav
  )
  window.onhashchange = mostrar
  mostrar()
  // A fila fica ouvindo o servidor o tempo todo: o número na aba e o aviso de pedido novo valem em qualquer aba
  iniciarFila()
  void religarPush()
  pararDeObservar = observar(() => {
    const numero = pendentes()
    const contagem = nav.querySelector<HTMLElement>('[data-aba="fila"] .contagem')
    if (contagem !== null) {
      contagem.textContent = String(numero)
      contagem.hidden = numero === 0
      contagem.setAttribute('aria-label', `${numero} esperando`)
    }
  })
}

function voltarAoLogin (): void {
  atual = null
  pararFila()
  pararDeObservar?.()
  pararDeObservar = null
  window.onhashchange = null
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
