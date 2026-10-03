import { ApiError, atendentes, entrarAtendente, entrarDono, type AtendenteOpcao, type Sessao } from '../api'
import { ligarErro, tecladoDoGrupo } from '../a11y'
import { h, lembrar, mount } from '../dom'

type Modo = 'equipe' | 'dono'

/**
 * Tela de entrada. Atendente: código da barraca (lembrado no aparelho) → toca no próprio nome → PIN.
 * Dono: e-mail e senha do painel.
 */
export function telaLogin (root: HTMLElement, aoEntrar: (s: Sessao) => void): void {
  let modo: Modo = lembrar.get('modo') === 'dono' ? 'dono' : 'equipe'

  // Abas de verdade: o painel abaixo é o `tabpanel` da aba escolhida, e as setas trocam de aba
  const area = h('div', { class: 'login-body', role: 'tabpanel', id: 'login-painel' })
  const abas = h('div', { class: 'segmented', role: 'tablist', 'aria-label': 'Quem vai entrar' })

  function desenhar (): void {
    abas.replaceChildren(
      aba('equipe', 'Sou da equipe'),
      aba('dono', 'Sou o dono')
    )
    area.setAttribute('aria-labelledby', `aba-${modo}`)
    tecladoDoGrupo(abas, (el) => el.getAttribute('aria-selected') === 'true')
    if (modo === 'dono') formDono(area, aoEntrar)
    else passoCodigo(area, aoEntrar)
  }

  function aba (valor: Modo, texto: string): HTMLButtonElement {
    return h('button', {
      type: 'button',
      role: 'tab',
      id: `aba-${valor}`,
      'aria-selected': String(modo === valor),
      'aria-controls': 'login-painel',
      class: modo === valor ? 'on' : '',
      onclick: () => { modo = valor; lembrar.set('modo', valor); desenhar() }
    }, texto)
  }

  mount(root,
    h('main', { class: 'login' },
      h('div', { class: 'login-head' },
        h('p', { class: 'brand' }, 'MesaMar'),
        h('h1', {}, 'App do quiosque')
      ),
      abas,
      area
    )
  )
  desenhar()
}

let contadorDeErros = 0

function erroBox (): HTMLParagraphElement {
  contadorDeErros += 1
  return h('p', { class: 'alert', role: 'alert', id: `login-erro-${contadorDeErros}`, hidden: true })
}

/** Mostra o erro e o liga ao campo (aria-invalid e aria-describedby), para o leitor de tela ler os dois juntos. */
function mostrarErro (box: HTMLElement, e: unknown, campo: HTMLElement | null = null): void {
  ligarErro(campo, box, e instanceof ApiError ? e.message : 'Algo deu errado. Tente de novo.')
}

async function enviando<T> (botao: HTMLButtonElement, texto: string, acao: () => Promise<T>): Promise<T> {
  const original = botao.textContent ?? ''
  botao.disabled = true
  botao.textContent = texto
  try {
    return await acao()
  } finally {
    botao.disabled = false
    botao.textContent = original
  }
}

function formDono (area: HTMLElement, aoEntrar: (s: Sessao) => void): void {
  const erro = erroBox()
  const email = h('input', { id: 'email', type: 'email', autocomplete: 'username', inputmode: 'email', required: true })
  const senha = h('input', { id: 'senha', type: 'password', autocomplete: 'current-password', required: true })
  const botao = h('button', { class: 'btn btn-ink btn-block', type: 'submit' }, 'Entrar')

  const form = h('form', {
    class: 'card',
    novalidate: true,
    onsubmit: (ev: Event) => {
      ev.preventDefault()
      ligarErro(senha, erro, null)
      void enviando(botao, 'Entrando…', async () => await entrarDono(email.value, senha.value))
        .then(aoEntrar)
        .catch((e: unknown) => { mostrarErro(erro, e, senha); senha.value = ''; senha.focus() })
    }
  },
  erro,
  h('div', { class: 'field' }, h('label', { for: 'email' }, 'E-mail'), email),
  h('div', { class: 'field' }, h('label', { for: 'senha' }, 'Senha'), senha),
  botao,
  h('p', { class: 'hint' }, 'O mesmo e-mail e senha do painel da barraca.'),
  // Esqueceu a senha? O fluxo é o mesmo do painel (o link chega por e-mail); a recuperação não acontece dentro do app
  h('p', { class: 'hint' }, h('a', { class: 'link', href: '/painel/esqueci-senha' }, 'Esqueci a minha senha'))
  )
  mount(area, form)
  email.focus()
}

function passoCodigo (area: HTMLElement, aoEntrar: (s: Sessao) => void): void {
  const erro = erroBox()
  const codigo = h('input', {
    id: 'codigo',
    type: 'text',
    autocomplete: 'off',
    autocapitalize: 'off',
    spellcheck: 'false',
    required: true,
    value: lembrar.get('codigo') ?? ''
  })
  const botao = h('button', { class: 'btn btn-ink btn-block', type: 'submit' }, 'Continuar')

  const form = h('form', {
    class: 'card',
    novalidate: true,
    onsubmit: (ev: Event) => {
      ev.preventDefault()
      ligarErro(codigo, erro, null)
      if (codigo.value.trim() === '') { mostrarErro(erro, new ApiError(0, 'vazio', 'Digite o código da barraca.'), codigo); codigo.focus(); return }
      void enviando(botao, 'Buscando…', async () => await atendentes(codigo.value))
        .then((lista) => {
          lembrar.set('codigo', codigo.value.trim().toLowerCase())
          passoNome(area, codigo.value.trim().toLowerCase(), lista, aoEntrar)
        })
        .catch((e: unknown) => mostrarErro(erro, e, codigo))
    }
  },
  erro,
  h('div', { class: 'field' },
    h('label', { for: 'codigo' }, 'Código da barraca'),
    codigo,
    h('p', { class: 'hint' }, 'O dono vê o código no painel, em Equipe. O app lembra dele neste celular.')
  ),
  botao
  )
  mount(area, form)
  if (codigo.value === '') codigo.focus()
}

function passoNome (area: HTMLElement, codigo: string, lista: AtendenteOpcao[], aoEntrar: (s: Sessao) => void): void {
  const voltar = h('button', { type: 'button', class: 'link', onclick: () => passoCodigo(area, aoEntrar) }, `Barraca: ${codigo} · trocar`)
  if (lista.length === 0) {
    mount(area, h('div', { class: 'card' },
      voltar,
      h('p', {}, 'Esta barraca ainda não tem atendentes. O dono cadastra a equipe no painel, em Equipe.'),
      h('p', { class: 'hint' }, 'Se você é o dono, use "Sou o dono".')
    ))
    return
  }
  const ultimo = lembrar.get('atendente')
  mount(area, h('div', { class: 'card' },
    voltar,
    h('h2', { class: 'card-title' }, 'Quem é você?'),
    h('ul', { class: 'names' },
      ...lista.map((a) => h('li', {},
        h('button', {
          type: 'button',
          class: a.id === ultimo ? 'name on' : 'name',
          onclick: () => passoPin(area, codigo, a, lista, aoEntrar)
        }, a.nome)
      ))
    )
  ))
}

function passoPin (area: HTMLElement, codigo: string, atendente: AtendenteOpcao, lista: AtendenteOpcao[], aoEntrar: (s: Sessao) => void): void {
  const erro = erroBox()
  const pin = h('input', {
    id: 'pin',
    type: 'password',
    inputmode: 'numeric',
    pattern: '[0-9]*',
    autocomplete: 'current-password',
    maxlength: 6,
    required: true
  })
  const botao = h('button', { class: 'btn btn-ink btn-block', type: 'submit' }, 'Entrar')

  const form = h('form', {
    class: 'card',
    novalidate: true,
    onsubmit: (ev: Event) => {
      ev.preventDefault()
      ligarErro(pin, erro, null)
      void enviando(botao, 'Conferindo…', async () => await entrarAtendente(codigo, atendente.id, pin.value))
        .then((s) => { lembrar.set('atendente', atendente.id); aoEntrar(s) })
        .catch((e: unknown) => { mostrarErro(erro, e, pin); pin.value = ''; pin.focus() })
    }
  },
  h('button', { type: 'button', class: 'link', onclick: () => passoNome(area, codigo, lista, aoEntrar) }, 'Não sou eu'),
  h('h2', { class: 'card-title' }, `Olá, ${atendente.nome}`),
  erro,
  h('div', { class: 'field' }, h('label', { for: 'pin' }, 'Seu PIN'), pin),
  botao,
  // Esqueceu o PIN? Quem troca é o dono, no painel (o app não tem como recuperar sozinho)
  h('p', { class: 'hint' }, 'Esqueceu o PIN? Peça ao dono para trocar, no painel, em Equipe → Trocar o PIN.')
  )
  mount(area, form)
  pin.focus()
}
