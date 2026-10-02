import { sair, type Sessao } from '../api'
import { ativarPush, desativarPush, estadoPush, testar, type EstadoPush } from '../push'
import { avisar } from '../toast'
import { h, mount } from '../dom'
import { cartaoProblema } from './problema'
import { instalacao, instalado, instalar, plataforma, podeInstalarDireto } from '../install'

export function abaMais (root: HTMLElement, sessao: Sessao, aoSair: () => void): void {
  const areaInstalar = h('section', { class: 'card', 'aria-labelledby': 't-instalar' })
  const desenharInstalar = (): void => { mount(areaInstalar, ...blocoInstalar()) }
  desenharInstalar()
  instalacao.addEventListener('mudou', desenharInstalar)

  const areaAvisos = h('section', { class: 'card', 'aria-labelledby': 't-avisos' })
  const desenharAvisos = (): void => {
    mount(areaAvisos, h('h2', { id: 't-avisos', class: 'card-title' }, 'Avisos de pedido'), h('p', { class: 'hint' }, 'Verificando…'))
    estadoPush()
      .then((estado) => { mount(areaAvisos, ...blocoAvisos(estado, desenharAvisos)) })
      .catch(() => { mount(areaAvisos, h('h2', { id: 't-avisos', class: 'card-title' }, 'Avisos de pedido'), h('p', { class: 'hint' }, 'Não deu para verificar os avisos agora.')) })
  }
  desenharAvisos()

  const botaoSair = h('button', {
    type: 'button',
    class: 'btn btn-line btn-block',
    onclick: () => {
      botaoSair.disabled = true
      // Quem sai deste celular deixa de receber os avisos de pedido nele
      desativarPush().catch(() => undefined).finally(() => { sair().catch(() => undefined).finally(aoSair) })
    }
  }, 'Sair deste celular')

  mount(root,
    h('header', { class: 'tab-head' }, h('h1', {}, 'Mais')),
    areaAvisos,
    areaInstalar,
    cartaoProblema(),
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
  const motivo = h('p', {}, 'Assim o MesaMar abre como um aplicativo, sem barra do navegador, e avisa de pedido novo mesmo com a tela bloqueada.')
  if (podeInstalarDireto()) {
    return [titulo, motivo, h('button', { type: 'button', class: 'btn btn-sun btn-block', onclick: () => { void instalar() } }, 'Instalar o app')]
  }
  const passos = plataforma() === 'iphone'
    ? ['Abra este endereço no Safari (no Chrome do iPhone não funciona).', 'Toque em Compartilhar (o quadrado com a seta para cima).', 'Role e toque em "Adicionar à Tela de Início".', 'Toque em "Adicionar". Abra o MesaMar pelo ícone novo.']
    : ['Abra este endereço no Chrome.', 'Toque no menu ⋮ (três pontinhos, em cima à direita).', 'Toque em "Instalar app" ou "Adicionar à tela inicial".', 'Confirme. Abra o MesaMar pelo ícone novo.']
  return [titulo, motivo, h('ol', { class: 'steps' }, ...passos.map((p) => h('li', {}, p)))]
}

/** O cartão "Avisos de pedido": o que dá para fazer em cada situação, sem botão que não funciona. */
function blocoAvisos (estado: EstadoPush, redesenhar: () => void): Node[] {
  const titulo = h('h2', { id: 't-avisos', class: 'card-title' }, 'Avisos de pedido')
  const explicacao = h('p', {}, 'Toca um aviso no celular quando chega um pedido novo, mesmo com o app fechado e a tela bloqueada.')
  const falha = (e: unknown): void => { avisar(e instanceof Error ? e.message : 'Não deu certo. Tente de novo.', true) }
  switch (estado) {
    case 'ativo':
      return [
        titulo,
        h('p', { class: 'ok' }, 'Ativados neste celular.'),
        explicacao,
        h('button', { type: 'button', class: 'btn btn-line btn-block', onclick: () => { testar().then(() => { avisar('Aviso de teste enviado. Deve chegar em instantes.') }).catch(falha) } }, 'Mandar um aviso de teste'),
        h('button', { type: 'button', class: 'link center-block', onclick: () => { desativarPush().then(redesenhar).catch(falha) } }, 'Desativar neste celular')
      ]
    case 'desativado':
      return [
        titulo,
        explicacao,
        h('button', { type: 'button', class: 'btn btn-sun btn-block', onclick: () => { ativarPush().then(redesenhar).catch(falha) } }, 'Ativar os avisos')
      ]
    case 'bloqueado':
      return [titulo, h('p', { class: 'alert' }, 'Os avisos estão bloqueados neste celular.'), h('p', {}, 'Libere em Configurações do navegador, em Notificações, para este site. Depois volte aqui.')]
    case 'precisa-instalar':
      return [titulo, explicacao, h('p', { class: 'alert' }, 'No iPhone, os avisos só funcionam com o app instalado na tela inicial. Siga os passos de "Instalar na tela inicial" e abra o MesaMar pelo ícone novo.')]
    case 'nao-suportado':
      return [titulo, h('p', {}, 'Este navegador não sabe receber avisos com o app fechado. No Android, use o Chrome. No iPhone, instale o app na tela inicial.')]
    case 'servidor-sem-chaves':
      return [titulo, explicacao, h('p', { class: 'hint' }, 'Os avisos ainda não foram configurados no servidor. Peça ao responsável pelo MesaMar para configurar as chaves (passo a passo no docs/deploy.md).')]
  }
}
