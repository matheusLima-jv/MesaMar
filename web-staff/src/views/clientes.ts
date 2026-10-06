import { tokenLocal } from '../fila-offline'
import { ApiError, clientes, descartada, guardaSois, novoCliente, novoId, novoQr, recusaVista, type Comanda, type GuardaSolOpcao, type NovoCliente, type Sessao } from '../api'
import { ativarModal, ligarErro, tecladoDoGrupo } from '../a11y'
import { CONSENTIMENTO_TEXTO, CONSENTIMENTO_VERSAO } from '../consentimento'
import { abrirTela, h, mount, registrarSobreposicao } from '../dom'
import { pendencias } from '../pendencias'
import { qrSvg } from '../qr'
import { lancar } from './lancar'

type Situacao = 'todas' | 'ABERTA' | 'AGUARDANDO_PAGAMENTO' | 'BLOQUEADA'

const SITUACOES: Array<{ valor: Situacao, rotulo: string }> = [
  { valor: 'todas', rotulo: 'Todas' },
  { valor: 'ABERTA', rotulo: 'Abertas' },
  { valor: 'AGUARDANDO_PAGAMENTO', rotulo: 'Fechando a conta' },
  { valor: 'BLOQUEADA', rotulo: 'Bloqueadas' }
]

const ROTULO_SITUACAO: Record<string, string> = {
  AGUARDANDO_PAGAMENTO: 'Fechando a conta',
  BLOQUEADA: 'Bloqueada'
}

// A busca e o filtro ficam fora da tela: voltar do QR ou do "Pedir" não os perde
let pagina = 0
let busca = ''
let situacao: Situacao = 'todas'

/** Sem acento e sem maiúscula, para "jose" achar "José". */
const normalizar = (texto: string): string => texto.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()

/** Nome, guarda-sol ("12", "guarda-sol 12", "balcão") e número de chegada: todas as palavras da busca precisam aparecer. */
export function combinaComBusca (c: Comanda, texto: string): boolean {
  const palavras = normalizar(texto).split(/\s+/).filter((p) => p !== '')
  if (palavras.length === 0) return true
  const feno = normalizar(`${c.nome} ${c.guardaSol === null ? 'balcao sem guarda-sol' : `guarda-sol ${c.guardaSol} gs${c.guardaSol}`} chegada ${c.numeroChegada}`)
  return palavras.every((p) => feno.includes(p))
}

/**
 * Aba Clientes: check-in em até 20 segundos (nome → guarda-sol → WhatsApp opcional → consentimento)
 * e o QR pessoal em tela cheia. A lista mostra quem está com comanda aberta, por ordem de chegada, com busca por
 * nome ou guarda-sol e filtro pela situação da comanda (no pico, achar o cliente certo rápido).
 */
export function abaClientes (root: HTMLElement, sessao: Sessao): void {
  // Resposta que chega depois de a pessoa ter trocado de aba (ou aberto outra tela) é descartada
  const aberta = abrirTela(root)
  mount(root, h('p', { class: 'loading' }, 'Carregando os clientes…'))
  clientes(pagina)
    .then((lista) => { if (aberta()) desenharLista(root, sessao, lista) })
    .catch((e: unknown) => {
      if (!aberta() || descartada(e)) return
      mount(root, h('div', { class: 'empty' },
        h('p', {}, e instanceof ApiError ? e.message : 'Não deu para carregar os clientes.'),
        h('button', { type: 'button', class: 'btn btn-line', onclick: () => abaClientes(root, sessao) }, 'Tentar de novo')
      ))
    })
}

function desenharLista (root: HTMLElement, sessao: Sessao, lista: Comanda[]): void {
  const novo = h('button', { type: 'button', class: 'btn btn-sun btn-block btn-big', onclick: () => formNovo(root, sessao) }, '+ Novo cliente')
  const resultado = h('p', { class: 'hint', id: 'resultado-clientes', role: 'status' })
  const corpo = h('div', { id: 'lista-clientes' })

  const linha = (c: Comanda): HTMLLIElement =>
    h('li', {},
      h('span', { class: 'arrival', 'aria-label': c.numeroChegada === 0 ? 'Aguardando sincronização' : `Chegada número ${c.numeroChegada}` }, c.numeroChegada === 0 ? '…' : `${c.numeroChegada}`),
      h('div', { class: 'person' },
        h('strong', {}, c.nome, ROTULO_SITUACAO[c.status] !== undefined ? h('span', { class: 'tag' }, ROTULO_SITUACAO[c.status]) : null),
        h('span', { class: 'hint' }, `${c.guardaSol === null ? 'Sem guarda-sol' : `Guarda-sol ${c.guardaSol}`} · ${c.hora}`)
      ),
      h('button', {
        type: 'button',
        class: 'sold',
        'aria-label': `Lançar pedido para ${c.nome}`,
        onclick: () => lancar(root, c, () => abaClientes(root, sessao))
      }, 'Pedir'),
      h('button', {
        type: 'button',
        class: 'sold',
        'aria-label': `Mostrar o QR de ${c.nome}`,
        onclick: () => telaQr(sessao, c, () => undefined)
      }, 'QR')
    )

  // Só a lista é redesenhada ao digitar: o campo de busca fica no lugar, com o foco e o cursor
  const filtrar = (): void => {
    const mostrados = lista.filter((c) => (situacao === 'todas' || c.status === situacao) && combinaComBusca(c, busca))
    const filtrando = busca.trim() !== '' || situacao !== 'todas'
    resultado.textContent = lista.length === 0
      ? 'Ninguém com comanda aberta agora.'
      : filtrando
        ? `${mostrados.length} de ${lista.length} ${lista.length === 1 ? 'cliente' : 'clientes'}.`
        : `${lista.length} nesta página, por ordem de chegada.`
    if (lista.length === 0) {
      corpo.replaceChildren(h('p', { class: 'hint center' }, 'Quando alguém chegar: nome, guarda-sol e o cliente lê o QR com a câmera.'))
    } else if (mostrados.length === 0) {
      corpo.replaceChildren(h('div', { class: 'empty' },
        h('p', {}, 'Ninguém combina com essa busca.'),
        h('button', {
          type: 'button',
          class: 'btn btn-line',
          onclick: () => { busca = ''; situacao = 'todas'; campoBusca.value = ''; seletor.value = 'todas'; filtrar(); campoBusca.focus() }
        }, 'Limpar a busca')
      ))
    } else {
      corpo.replaceChildren(h('ul', { class: 'people' }, ...mostrados.map(linha)))
    }
  }

  const campoBusca = h('input', {
    id: 'busca-clientes', type: 'search', autocomplete: 'off', enterkeyhint: 'search', placeholder: 'Nome ou guarda-sol nesta página', value: busca,
    'aria-describedby': 'resultado-clientes',
    oninput: () => { busca = campoBusca.value; filtrar() }
  })
  const seletor = h('select', {
    id: 'situacao-clientes',
    onchange: () => { situacao = seletor.value as Situacao; filtrar() }
  }, ...SITUACOES.map((s) => h('option', { value: s.valor, selected: s.valor === situacao }, s.rotulo)))

  mount(root,
    h('header', { class: 'tab-head' },
      h('h1', {}, 'Clientes'),
      resultado
    ),
    novo,
    ...(lista.length > 0
      ? [h('div', { class: 'clientes-filtro' },
          h('div', { class: 'field' }, h('label', { for: 'busca-clientes' }, 'Buscar cliente'), campoBusca),
          h('div', { class: 'field' }, h('label', { for: 'situacao-clientes' }, 'Situação da comanda'), seletor))]
      : []),
    corpo,
    h('nav', { 'aria-label': 'Páginas de clientes', class: 'acoes' },
      h('button', { type: 'button', class: 'btn btn-line', disabled: pagina === 0,
        onclick: () => { pagina--; abaClientes(root, sessao) } }, 'Anterior'),
      h('span', {}, `Página ${pagina + 1}`),
      h('button', { type: 'button', class: 'btn btn-line', disabled: lista.length < 100,
        onclick: () => { pagina++; abaClientes(root, sessao) } }, 'Próxima'))
  )
  filtrar()
}

/** Check-ins sem resposta ficam guardados por barraca: o mesmo aparelho pode entrar em outra barraca depois. */
const escopoCheckin = (sessao: Sessao): string => `${sessao.barraca?.codigo ?? 'sem-barraca'}:${sessao.usuario?.id ?? 'sem-autor'}`

function formNovo (root: HTMLElement, sessao: Sessao): void {
  const aberta = abrirTela(root)
  // Um check-in anterior ficou sem resposta: o formulário volta com os mesmos dados e o mesmo id, travado, para
  // reenviar. Se ele já tinha chegado, o servidor devolve a mesma comanda (não cria outra).
  let pendente = pendencias.ler<NovoCliente>('checkin', escopoCheckin(sessao))
  // O id nasce aqui e vale para todas as tentativas deste formulário: reenviar não duplica o cliente.
  const id = pendente?.id ?? novoId()
  let escolhido: string | null = pendente?.dados.guardaSolId ?? null
  let travado = pendente !== null
  let opcoesAtuais: GuardaSolOpcao[] = []

  const erroGeral = h('p', { class: 'alert', role: 'alert', hidden: true })
  const erros: Record<string, HTMLParagraphElement> = {}
  const erroDe = (campo: string): HTMLParagraphElement => {
    erros[campo] = h('p', { class: 'field-err', id: `erro-${campo}`, hidden: true })
    return erros[campo]
  }

  const nome = h('input', {
    id: 'nome', type: 'text', maxlength: 40, autocomplete: 'off', autocapitalize: 'words', enterkeyhint: 'next', required: true,
    value: pendente?.dados.nome, readonly: travado
  })
  const whatsapp = h('input', {
    id: 'whatsapp', type: 'tel', inputmode: 'tel', autocomplete: 'off', placeholder: '(13) 99999-8888', value: pendente?.dados.whatsapp, readonly: travado,
    maxlength: WHATSAPP_MAX
  })
  const consentimento = h('input', { id: 'consentimento', type: 'checkbox', checked: pendente?.dados.consentimento === true, disabled: travado })
  // Escolha única entre vários botões: é um grupo de radios (setas movem e escolhem; o Tab entra e sai do grupo)
  const grade = h('div', { class: 'umbrellas', role: 'radiogroup', 'aria-labelledby': 't-gs' }, h('p', { class: 'hint' }, 'Carregando…'))
  const botao = h('button', { type: 'submit', class: 'btn btn-ink btn-block btn-big' }, 'Gerar QR')

  const campoDe: Record<string, HTMLElement> = { nome, whatsapp, consentimento, guardaSolId: grade }

  const desenharGrade = (opcoes: GuardaSolOpcao[]): void => {
    opcoesAtuais = opcoes
    const opcao = (valor: string | null, texto: string, rotulo: string, ocupado: boolean): HTMLButtonElement =>
      h('button', {
        type: 'button',
        role: 'radio',
        class: `umbrella${valor === null ? ' wide' : ''}${ocupado ? ' busy' : ''}${escolhido === valor ? ' on' : ''}`,
        'aria-checked': String(escolhido === valor),
        'aria-label': rotulo,
        disabled: travado && escolhido !== valor,
        onclick: () => { if (travado) return; escolhido = valor; desenharGrade(opcoes) }
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
    tecladoDoGrupo(grade, (el) => el.getAttribute('aria-checked') === 'true')
  }

  const mostrarErros = (campos: Record<string, string>): void => {
    for (const [campo, msg] of Object.entries(campos)) {
      // O erro fica ligado ao campo (o leitor de tela lê junto); o que não é de campo nenhum vai no aviso geral
      if (erros[campo] !== undefined) ligarErro(campoDe[campo] ?? null, erros[campo], msg)
      else { erroGeral.textContent = msg; erroGeral.hidden = false }
    }
    const primeiro = Object.keys(campos)[0]
    const alvo = primeiro === 'guardaSolId' ? grade.querySelector<HTMLElement>('[role="radio"]') : campoDe[primeiro] ?? null
    alvo?.focus()
  }

  guardaSois()
    .then((opcoes) => { if (aberta()) desenharGrade(opcoes) })
    .catch((e: unknown) => {
      if (!aberta() || descartada(e)) return
      desenharGrade([])
      grade.append(h('p', { class: 'hint' }, 'Não deu para carregar os guarda-sóis. Dá para seguir sem.'))
    })

  const form = h('form', {
    class: 'checkin',
    novalidate: true,
    onsubmit: (ev: Event) => {
      ev.preventDefault()
      erroGeral.hidden = true
      for (const campo of Object.keys(erros)) ligarErro(campoDe[campo] ?? null, erros[campo], null)
      // O básico é conferido aqui mesmo (resposta na hora, sem depender do sinal); o servidor confere tudo de novo.
      const locais: Record<string, string> = {}
      if (nome.value.trim() === '') locais.nome = 'Digite o nome do cliente.'
      if (whatsapp.value.length > WHATSAPP_MAX) locais.whatsapp = 'WhatsApp longo demais. Exemplo: (13) 99999-8888.'
      if (!consentimento.checked) locais.consentimento = 'Confirme que o cliente concordou.'
      if (Object.keys(locais).length > 0) {
        mostrarErros(locais)
        return
      }
      const envio: NovoCliente = pendente?.dados ?? {
        id, token: tokenLocal(), nome: nome.value, guardaSolId: escolhido, whatsapp: whatsapp.value, consentimento: consentimento.checked,
        // A versão do texto que estava na tela: fica gravada no cadastro (docs/consentimento.md)
        consentimentoVersao: CONSENTIMENTO_VERSAO
      }
      // Guardado antes de sair: trocar de aba ou recarregar o app no meio não perde o id deste check-in
      try { pendencias.guardar('checkin', escopoCheckin(sessao), id, envio) } catch {
        erroGeral.textContent = 'Não deu para preparar o check-in. Confira os dados e tente de novo.'
        erroGeral.hidden = false
        return
      }
      botao.disabled = true
      botao.textContent = 'Gerando…'
      novoCliente(envio)
        .then((c) => {
          pendencias.apagar('checkin', escopoCheckin(sessao))
          if (aberta()) telaQr(sessao, c, () => abaClientes(root, sessao))
        })
        .catch(async (e: unknown) => {
          if (descartada(e)) return
          // Recusa avaliada pelo servidor: nada foi criado, dá para corrigir. Sem resposta, sessão vencida ou limite:
          // pode ter sido criado, então continua pendente e repete o mesmo id
          const definitiva = e instanceof ApiError && e.recusaDefinitiva
          if (definitiva) pendencias.apagar('checkin', escopoCheckin(sessao))
          if (!aberta()) return
          // A recusa aparece aqui: sai da fila antes de liberar o botão, para o envio corrigido não repetir os dados antigos
          if (definitiva) await recusaVista(id)
          if (!aberta()) return
          if (!travado && e instanceof ApiError && e.status === 0) { formNovo(root, sessao); return }
          if (definitiva && travado) destravar()
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
  h('div', { class: 'field' }, h('p', { id: 't-gs', class: 'label' }, 'Guarda-sol'), grade, erroDe('guardaSolId')),
  h('div', { class: 'field' },
    h('label', { for: 'whatsapp' }, 'WhatsApp ', h('span', { class: 'opt' }, '(opcional, para mandar o link)')),
    whatsapp, erroDe('whatsapp')),
  h('div', { class: 'consent' },
    consentimento,
    h('label', { for: 'consentimento' }, CONSENTIMENTO_TEXTO)
  ),
  erroDe('consentimento'),
  botao,
  h('button', { type: 'button', class: 'link center-block', onclick: () => abaClientes(root, sessao) }, 'Cancelar')
  )

  const avisoPendente = pendente === null
    ? null
    : h('p', { class: 'alert', role: 'status' },
      `O check-in de ${pendente.dados.nome} foi enviado e a resposta não chegou. Toque em Gerar QR: se ele já tinha chegado, a comanda é a mesma (não vira outra).`)

  /** O servidor recusou o check-in que estava pendente: nada foi criado, então os dados voltam a poder ser corrigidos. */
  function destravar (): void {
    pendente = null
    travado = false
    nome.readOnly = false
    whatsapp.readOnly = false
    consentimento.disabled = false
    if (avisoPendente !== null) avisoPendente.hidden = true
    desenharGrade(opcoesAtuais)
  }

  mount(root,
    h('header', { class: 'tab-head' }, h('h1', {}, 'Novo cliente')),
    ...(avisoPendente !== null ? [avisoPendente] : []),
    form)
  if (travado) botao.focus()
  else nome.focus()
}

/** O mesmo limite da fila do aparelho (`novoCliente` em web-shared/src/validacao.ts). */
const WHATSAPP_MAX = 25

/** QR em tela cheia, grande o bastante para a câmera ler a ~50 cm, no sol. */
function telaQr (sessao: Sessao, c: Comanda, aoFechar: () => void): void {
  // Trocar de aba (ou voltar no navegador) com o QR aberto só tira a tela, sem redesenhar a aba antiga.
  const remover = (): void => {
    desregistrar()
    window.removeEventListener('hashchange', remover)
    desativar()
  }
  const fechar = (): void => {
    remover()
    aoFechar()
  }

  const texto = `Olá, ${c.nome}! Esta é a sua comanda na ${sessao.barraca?.nome ?? 'barraca'}: ${c.link} . Pelo link você vê o cardápio e pede pelo celular.`
  const pronto = h('button', { type: 'button', class: 'btn btn-ink btn-block btn-big', onclick: fechar }, 'Pronto')

  // "QR novo": o cliente compartilhou ou perdeu o link. Em dois passos, para não trocar sem querer
  const aviso = h('p', { class: 'alert', role: 'alert', hidden: true })
  const confirmarNovo = h('div', { class: 'qr-novo', hidden: true },
    h('p', {}, 'O QR e o link atuais deixam de abrir. A comanda, os pedidos e a conta continuam. ' +
      'Um pedido que ainda estava sendo enviado pelo celular do cliente precisa ser conferido com ele.'),
    h('button', {
      type: 'button',
      class: 'btn btn-line btn-block',
      onclick: (ev: Event) => {
        const botao = ev.currentTarget as HTMLButtonElement
        botao.disabled = true
        botao.textContent = 'Gerando…'
        aviso.hidden = true
        novoQr(c.id)
          .then((nova) => {
            // Troca o QR na tela sem voltar para a lista (o foco devolvido no fim ainda é o de quem abriu o primeiro QR)
            remover()
            telaQr(sessao, nova, aoFechar)
          })
          .catch((e: unknown) => {
            if (descartada(e)) return
            botao.disabled = false
            botao.textContent = 'Gerar QR novo agora'
            aviso.textContent = e instanceof ApiError ? e.message : 'Não deu para gerar o QR novo. Tente de novo.'
            aviso.hidden = false
          })
      }
    }, 'Gerar QR novo agora')
  )
  const novo = h('button', {
    type: 'button',
    class: 'link center-block',
    'aria-expanded': 'false',
    onclick: (ev: Event) => {
      const aberto = confirmarNovo.hidden
      confirmarNovo.hidden = !aberto
      ;(ev.currentTarget as HTMLElement).setAttribute('aria-expanded', String(aberto))
      if (aberto) confirmarNovo.querySelector<HTMLElement>('button')?.focus()
    }
  }, 'Trocar o QR deste cliente')

  const tela = h('div', { class: 'qr-screen', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'qr-nome' },
    h('div', { class: 'qr-head' },
      h('h2', { id: 'qr-nome' }, c.nome),
      h('p', {}, `${c.guardaSol === null ? 'Sem guarda-sol' : `Guarda-sol ${c.guardaSol}`} · ${c.numeroChegada === 0 ? 'aguardando sincronização' : `chegada nº ${c.numeroChegada}`}`)
    ),
    h('div', { class: 'qr-box' }, qrSvg(c.link, `QR da comanda de ${c.nome}`)),
    h('p', { class: 'qr-tip', role: 'status' }, c.numeroChegada === 0 ? 'Cadastro preservado. O link só funciona depois da sincronização; aguarde antes de entregar o QR.' : 'Peça para o cliente abrir a câmera e apontar para o código.'),
    h('div', { class: 'qr-actions' },
      c.whatsapp !== null
        ? h('a', {
          class: 'btn btn-line btn-block',
          href: `https://wa.me/${c.whatsapp}?text=${encodeURIComponent(texto)}`,
          target: '_blank',
          rel: 'noopener'
        }, `Enviar pelo WhatsApp ${c.whatsappExibicao ?? ''}`.trim())
        : null,
      pronto,
      aviso,
      confirmarNovo,
      novo
    )
  )
  // O resto do app fica inerte, o Tab não sai do QR e o Esc fecha
  const desativar = ativarModal(tela, { aoEscape: fechar, foco: () => pronto })
  // Sessão acabou ou o app saiu: o QR some sem voltar para a lista de clientes
  const desregistrar = registrarSobreposicao(remover)
  window.addEventListener('hashchange', remover)
}
