import { sair, type Sessao } from '../api'
import { h, mount } from '../dom'
import { instalacao, instalado, instalar, plataforma, podeInstalarDireto } from '../install'

export function abaMais (root: HTMLElement, sessao: Sessao, aoSair: () => void): void {
  const areaInstalar = h('section', { class: 'card', 'aria-labelledby': 't-instalar' })
  const desenharInstalar = (): void => { mount(areaInstalar, ...blocoInstalar()) }
  desenharInstalar()
  instalacao.addEventListener('mudou', desenharInstalar)

  const botaoSair = h('button', {
    type: 'button',
    class: 'btn btn-line btn-block',
    onclick: () => {
      botaoSair.disabled = true
      sair().catch(() => undefined).finally(aoSair)
    }
  }, 'Sair deste celular')

  mount(root,
    h('header', { class: 'tab-head' }, h('h1', {}, 'Mais')),
    areaInstalar,
    h('section', { class: 'card' },
      h('h2', { class: 'card-title' }, sessao.usuario?.nome ?? ''),
      h('p', { class: 'hint' },
        `${sessao.usuario?.papel === 'DONO' ? 'Dono' : 'Equipe'} · ${sessao.barraca?.nome ?? ''} · código `,
        h('span', { class: 'nowrap' }, sessao.barraca?.codigo ?? '')),
      sessao.usuario?.papel === 'DONO'
        ? h('p', {}, h('a', { href: '/painel', class: 'link' }, 'Abrir o painel (preços, Pix, equipe)'))
        : null,
      botaoSair
    )
  )
}

function blocoInstalar (): Node[] {
  const titulo = h('h2', { id: 't-instalar', class: 'card-title' }, 'Instalar na tela inicial')
  if (instalado()) {
    return [titulo, h('p', { class: 'ok' }, 'Pronto: o app está instalado neste celular.')]
  }
  const motivo = h('p', {}, 'Assim o MesaMar abre como um aplicativo, sem barra do navegador, e (na próxima etapa) avisa de pedido novo mesmo com a tela bloqueada.')
  if (podeInstalarDireto()) {
    return [titulo, motivo, h('button', { type: 'button', class: 'btn btn-sun btn-block', onclick: () => { void instalar() } }, 'Instalar o app')]
  }
  const passos = plataforma() === 'iphone'
    ? ['Abra este endereço no Safari (no Chrome do iPhone não funciona).', 'Toque em Compartilhar (o quadrado com a seta para cima).', 'Role e toque em "Adicionar à Tela de Início".', 'Toque em "Adicionar". Abra o MesaMar pelo ícone novo.']
    : ['Abra este endereço no Chrome.', 'Toque no menu ⋮ (três pontinhos, em cima à direita).', 'Toque em "Instalar app" ou "Adicionar à tela inicial".', 'Confirme. Abra o MesaMar pelo ícone novo.']
  return [titulo, motivo, h('ol', { class: 'steps' }, ...passos.map((p) => h('li', {}, p)))]
}
