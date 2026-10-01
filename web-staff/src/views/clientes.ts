import { ApiError, clientes, guardaSois, novoCliente, novoId, type Comanda, type GuardaSolOpcao, type Sessao } from '../api'
import { h, mount } from '../dom'
import { qrSvg } from '../qr'

/**
 * Aba Clientes: check-in em até 20 segundos (nome → guarda-sol → WhatsApp opcional → consentimento)
 * e o QR pessoal em tela cheia. A lista mostra quem está com comanda aberta, por ordem de chegada.
 */
export function abaClientes (root: HTMLElement, sessao: Sessao): void {
  mount(root, h('p', { class: 'loading' }, 'Carregando os clientes…'))
  clientes()
    .then((lista) => desenharLista(root, sessao, lista))
    .catch((e: unknown) => {
      mount(root, h('div', { class: 'empty' },
        h('p', {}, e instanceof ApiError ? e.message : 'Não deu para carregar os clientes.'),
        h('button', { type: 'button', class: 'btn btn-line', onclick: () => abaClientes(root, sessao) }, 'Tentar de novo')
      ))
    })
}

function desenharLista (root: HTMLElement, sessao: Sessao, lista: Comanda[]): void {
  const novo = h('button', { type: 'button', class: 'btn btn-sun btn-block btn-big', onclick: () => formNovo(root, sessao) }, '+ Novo cliente')
  mount(root,
    h('header', { class: 'tab-head' },
      h('h1', {}, 'Clientes'),
      h('p', { class: 'hint' }, lista.length === 0 ? 'Ninguém com comanda aberta agora.' : `${lista.length} com comanda aberta, por ordem de chegada.`)
    ),
    novo,
    lista.length === 0
      ? h('p', { class: 'hint center' }, 'Quando alguém chegar: nome, guarda-sol e o cliente lê o QR com a câmera.')
      : h('ul', { class: 'people' }, ...lista.map((c) =>
        h('li', {},
          h('span', { class: 'arrival', 'aria-label': `Chegada número ${c.numeroChegada}` }, `${c.numeroChegada}`),
          h('div', { class: 'person' },
            h('strong', {}, c.nome),
            h('span', { class: 'hint' }, `${c.guardaSol === null ? 'Sem guarda-sol' : `Guarda-sol ${c.guardaSol}`} · ${c.hora}`)
          ),
          h('button', {
            type: 'button',
            class: 'sold',
            'aria-label': `Mostrar o QR de ${c.nome}`,
            onclick: () => telaQr(sessao, c, () => undefined)
          }, 'QR')
        )
      ))
  )
}

function formNovo (root: HTMLElement, sessao: Sessao): void {
  // O id nasce aqui e vale para todas as tentativas deste formulário: reenviar não duplica o cliente.
  const id = novoId()
  let escolhido: string | null = null

  const erroGeral = h('p', { class: 'alert', role: 'alert', hidden: true })
  const erros: Record<string, HTMLParagraphElement> = {}
  const erroDe = (campo: string): HTMLParagraphElement => {
    erros[campo] = h('p', { class: 'field-err', id: `erro-${campo}`, hidden: true })
    return erros[campo]
  }

  const nome = h('input', {
    id: 'nome', type: 'text', maxlength: 40, autocomplete: 'off', autocapitalize: 'words', enterkeyhint: 'next', required: true
  })
  const whatsapp = h('input', { id: 'whatsapp', type: 'tel', inputmode: 'tel', autocomplete: 'off', placeholder: '(13) 99999-8888' })
  const consentimento = h('input', { id: 'consentimento', type: 'checkbox' })
  const grade = h('div', { class: 'umbrellas', role: 'group', 'aria-labelledby': 't-gs' }, h('p', { class: 'hint' }, 'Carregando…'))
  const botao = h('button', { type: 'submit', class: 'btn btn-ink btn-block btn-big' }, 'Gerar QR')

  const desenharGrade = (opcoes: GuardaSolOpcao[]): void => {
    const opcao = (valor: string | null, texto: string, rotulo: string, ocupado: boolean): HTMLButtonElement =>
      h('button', {
        type: 'button',
        class: `umbrella${valor === null ? ' wide' : ''}${ocupado ? ' busy' : ''}${escolhido === valor ? ' on' : ''}`,
        'aria-pressed': String(escolhido === valor),
        'aria-label': rotulo,
        onclick: () => { escolhido = valor; desenharGrade(opcoes) }
      }, texto)
    grade.replaceChildren(
      opcao(null, 'Sem guarda-sol', 'Sem guarda-sol (balcão)', false),
      ...opcoes.map((g) => opcao(
        g.id,
        String(g.numero),
        g.clientes === 0 ? `Guarda-sol ${g.numero}` : `Guarda-sol ${g.numero}, já tem ${g.clientes} ${g.clientes === 1 ? 'cliente' : 'clientes'}`,
        g.clientes > 0
      ))
    )
    if (opcoes.length === 0) grade.append(h('p', { class: 'hint' }, 'Nenhum guarda-sol cadastrado. O dono cria em Painel → Guarda-sóis.'))
  }

  const mostrarErros = (campos: Record<string, string>): void => {
    for (const [campo, msg] of Object.entries(campos)) {
      const alvo = erros[campo] ?? erroGeral
      alvo.textContent = msg
      alvo.hidden = false
    }
    const primeiro = Object.keys(campos)[0]
    document.getElementById(primeiro === 'guardaSolId' ? 't-gs' : primeiro)?.focus()
  }

  guardaSois()
    .then(desenharGrade)
    .catch(() => { desenharGrade([]); grade.append(h('p', { class: 'hint' }, 'Não deu para carregar os guarda-sóis. Dá para seguir sem.')) })

  const form = h('form', {
    class: 'checkin',
    novalidate: true,
    onsubmit: (ev: Event) => {
      ev.preventDefault()
      erroGeral.hidden = true
      Object.values(erros).forEach((p) => { p.hidden = true })
      // O básico é conferido aqui mesmo (resposta na hora, sem depender do sinal); o servidor confere tudo de novo.
      const locais: Record<string, string> = {}
      if (nome.value.trim() === '') locais.nome = 'Digite o nome do cliente.'
      if (!consentimento.checked) locais.consentimento = 'Confirme que o cliente concordou.'
      if (Object.keys(locais).length > 0) {
        mostrarErros(locais)
        return
      }
      botao.disabled = true
      botao.textContent = 'Gerando…'
      novoCliente({ id, nome: nome.value, guardaSolId: escolhido, whatsapp: whatsapp.value, consentimento: consentimento.checked })
        .then((c) => {
          telaQr(sessao, c, () => abaClientes(root, sessao))
        })
        .catch((e: unknown) => {
          botao.disabled = false
          botao.textContent = 'Gerar QR'
          if (e instanceof ApiError && Object.keys(e.campos).length > 0) {
            mostrarErros(e.campos)
            return
          }
          erroGeral.textContent = e instanceof ApiError ? e.message : 'Não deu para salvar. Tente de novo.'
          erroGeral.hidden = false
        })
    }
  },
  erroGeral,
  h('div', { class: 'field' }, h('label', { for: 'nome' }, 'Nome do cliente'), nome, erroDe('nome')),
  h('div', { class: 'field' }, h('p', { id: 't-gs', class: 'label', tabindex: -1 }, 'Guarda-sol'), grade, erroDe('guardaSolId')),
  h('div', { class: 'field' },
    h('label', { for: 'whatsapp' }, 'WhatsApp ', h('span', { class: 'opt' }, '(opcional, para mandar o link)')),
    whatsapp, erroDe('whatsapp')),
  h('div', { class: 'consent' },
    consentimento,
    h('label', { for: 'consentimento' }, 'O cliente concorda em informar o nome (e o WhatsApp) só para identificar a comanda e receber o link.')
  ),
  erroDe('consentimento'),
  botao,
  h('button', { type: 'button', class: 'link center-block', onclick: () => abaClientes(root, sessao) }, 'Cancelar')
  )

  mount(root, h('header', { class: 'tab-head' }, h('h1', {}, 'Novo cliente')), form)
  nome.focus()
}

/** QR em tela cheia, grande o bastante para a câmera ler a ~50 cm, no sol. */
function telaQr (sessao: Sessao, c: Comanda, aoFechar: () => void): void {
  const anterior = document.activeElement as HTMLElement | null
  // Trocar de aba (ou voltar no navegador) com o QR aberto só tira a tela, sem redesenhar a aba antiga.
  const remover = (): void => {
    document.removeEventListener('keydown', teclas)
    window.removeEventListener('hashchange', remover)
    tela.remove()
  }
  const fechar = (): void => {
    remover()
    anterior?.focus()
    aoFechar()
  }
  const teclas = (ev: KeyboardEvent): void => { if (ev.key === 'Escape') fechar() }

  const texto = `Olá, ${c.nome}! Esta é a sua comanda na ${sessao.barraca?.nome ?? 'barraca'}: ${c.link} . Pelo link você vê o cardápio e pede pelo celular.`
  const pronto = h('button', { type: 'button', class: 'btn btn-ink btn-block btn-big', onclick: fechar }, 'Pronto')
  const tela = h('div', { class: 'qr-screen', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'qr-nome' },
    h('div', { class: 'qr-head' },
      h('h2', { id: 'qr-nome' }, c.nome),
      h('p', {}, `${c.guardaSol === null ? 'Sem guarda-sol' : `Guarda-sol ${c.guardaSol}`} · chegada nº ${c.numeroChegada}`)
    ),
    h('div', { class: 'qr-box' }, qrSvg(c.link, `QR da comanda de ${c.nome}`)),
    h('p', { class: 'qr-tip' }, 'Peça para o cliente abrir a câmera e apontar para o código.'),
    h('div', { class: 'qr-actions' },
      c.whatsapp !== null
        ? h('a', {
          class: 'btn btn-line btn-block',
          href: `https://wa.me/${c.whatsapp}?text=${encodeURIComponent(texto)}`,
          target: '_blank',
          rel: 'noopener'
        }, `Enviar pelo WhatsApp ${c.whatsappExibicao ?? ''}`.trim())
        : null,
      pronto
    )
  )
  document.body.append(tela)
  document.addEventListener('keydown', teclas)
  window.addEventListener('hashchange', remover)
  pronto.focus()
}
