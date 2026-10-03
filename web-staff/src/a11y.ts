// Acessibilidade e atualização automática sem atrapalhar quem está usando a tela:
// modais com foco preso, redesenho que preserva foco e cursor, teclado de grupos de opções, erros ligados ao campo.

// ---------------------------------------------------------------------------------------------------------------
// Modais (diálogos e o QR em tela cheia): fundo inerte, foco preso dentro e devolvido a quem abriu.
// ---------------------------------------------------------------------------------------------------------------

const SELETOR_FOCAVEL =
  'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

/** O que dá para focar dentro de um elemento (sem o que está escondido). */
export function focaveis (dentro: HTMLElement): HTMLElement[] {
  return Array.from(dentro.querySelectorAll<HTMLElement>(SELETOR_FOCAVEL))
    .filter((el) => el.closest('[hidden], [inert]') === null && el.getClientRects().length > 0)
}

const modaisAbertos: HTMLElement[] = []

export interface OpcoesModal {
  /** O que fazer quando a pessoa aperta Esc (normalmente, fechar). */
  aoEscape: () => void
  /** Quem recebe o foco ao abrir. Padrão: o primeiro controle do modal. */
  foco?: () => HTMLElement | null
}

/**
 * Mostra um modal: anexa `tela` ao body, deixa o resto do app **inerte** (não recebe foco nem clique e some para o
 * leitor de tela), prende o Tab dentro dele e trata o Esc. Devolve a função que o tira da tela e **devolve o foco**
 * a quem estava com ele antes (ou ao conteúdo, se esse elemento sumiu num redesenho). Não chama o "ao fechar" de ninguém.
 */
export function ativarModal (tela: HTMLElement, opcoes: OpcoesModal): () => void {
  const anterior = document.activeElement instanceof HTMLElement ? document.activeElement : null
  const app = document.getElementById('app')
  modaisAbertos[modaisAbertos.length - 1]?.setAttribute('inert', '')
  app?.setAttribute('inert', '')
  modaisAbertos.push(tela)
  tela.tabIndex = -1
  document.body.append(tela)

  const teclas = (ev: KeyboardEvent): void => {
    if (modaisAbertos[modaisAbertos.length - 1] !== tela) return
    if (ev.key === 'Escape') { ev.preventDefault(); opcoes.aoEscape(); return }
    if (ev.key !== 'Tab') return
    const itens = focaveis(tela)
    if (itens.length === 0) { ev.preventDefault(); tela.focus(); return }
    const primeiro = itens[0]
    const ultimo = itens[itens.length - 1]
    const ativo = document.activeElement
    const fora = !(ativo instanceof Node) || !tela.contains(ativo)
    if (ev.shiftKey && (ativo === primeiro || fora)) {
      ev.preventDefault()
      ultimo.focus()
    } else if (!ev.shiftKey && (ativo === ultimo || fora)) {
      ev.preventDefault()
      primeiro.focus()
    }
  }
  document.addEventListener('keydown', teclas)
  const inicial = opcoes.foco?.() ?? focaveis(tela)[0] ?? tela
  inicial.focus()

  let aberto = true
  return () => {
    if (!aberto) return
    aberto = false
    document.removeEventListener('keydown', teclas)
    const i = modaisAbertos.indexOf(tela)
    if (i >= 0) modaisAbertos.splice(i, 1)
    tela.remove()
    const topo = modaisAbertos[modaisAbertos.length - 1]
    if (topo !== undefined) topo.removeAttribute('inert')
    else app?.removeAttribute('inert')
    // Foco de volta a quem abriu; se ele sumiu (a tela se redesenhou), ao conteúdo
    const alvo = anterior !== null && anterior.isConnected ? anterior : document.getElementById('conteudo')
    alvo?.focus({ preventScroll: true })
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Atualizações automáticas sem tirar o foco nem o cursor de quem está usando a tela.
// ---------------------------------------------------------------------------------------------------------------

interface FocoGuardado {
  id: string
  rotulo: string | null
  nome: string | null
  tag: string
  texto: string
  inicio: number | null
  fim: number | null
}

function guardarFoco (el: HTMLElement): FocoGuardado {
  const campo = el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement ? el : null
  return {
    id: el.id,
    rotulo: el.getAttribute('aria-label'),
    nome: el.getAttribute('name'),
    tag: el.tagName,
    texto: el.textContent?.trim() ?? '',
    inicio: campo?.selectionStart ?? null,
    fim: campo?.selectionEnd ?? null
  }
}

function acharFoco (root: HTMLElement, g: FocoGuardado): HTMLElement | null {
  if (g.id !== '') return root.querySelector<HTMLElement>(`#${CSS.escape(g.id)}`)
  const candidatos = Array.from(root.querySelectorAll<HTMLElement>(g.tag.toLowerCase()))
  if (g.rotulo !== null) return candidatos.find((c) => c.getAttribute('aria-label') === g.rotulo) ?? null
  if (g.nome !== null) return candidatos.find((c) => c.getAttribute('name') === g.nome) ?? null
  return candidatos.find((c) => g.texto !== '' && (c.textContent?.trim() ?? '') === g.texto) ?? null
}

/**
 * Como `mount`, mas guarda onde estava o foco (e o cursor, num campo de texto) e a rolagem, e os devolve no
 * mesmo elemento da tela nova. Para as telas que se atualizam sozinhas (fila, pagamentos).
 */
export function mountPreservando (root: HTMLElement, ...nodes: Node[]): void {
  const ativo = document.activeElement
  const guardado = ativo instanceof HTMLElement && root.contains(ativo) ? guardarFoco(ativo) : null
  const rolagem = window.scrollY
  root.replaceChildren(...nodes)
  if (guardado !== null) {
    const novo = acharFoco(root, guardado)
    if (novo !== null) {
      novo.focus({ preventScroll: true })
      if (guardado.inicio !== null && (novo instanceof HTMLInputElement || novo instanceof HTMLTextAreaElement)) {
        try { novo.setSelectionRange(guardado.inicio, guardado.fim) } catch { /* tipos sem seleção (number, email…) */ }
      }
    }
  }
  window.scrollTo(0, rolagem)
}

/** Alguém está escolhendo ou digitando num campo desta tela? */
export function controleEmUso (root: HTMLElement): boolean {
  const ativo = document.activeElement
  return ativo instanceof HTMLElement && root.contains(ativo) && ['INPUT', 'SELECT', 'TEXTAREA'].includes(ativo.tagName)
}

const adiados = new WeakSet<HTMLElement>()

/**
 * Atualização automática: se a pessoa está num campo (digitando, ou com a lista de um select aberta), NÃO redesenha
 * agora (isso fecharia a lista e tiraria o cursor): espera ela sair do campo e redesenha então, com o que for mais novo.
 * Devolve `true` quando adiou.
 */
export function adiarSeEmUso (root: HTMLElement, redesenhar: () => void): boolean {
  if (!controleEmUso(root)) return false
  if (!adiados.has(root)) {
    adiados.add(root)
    root.addEventListener('focusout', () => {
      adiados.delete(root)
      // Depois de o foco ir para o próximo elemento (se foi para outro campo da tela, adia de novo)
      window.setTimeout(redesenhar, 0)
    }, { once: true })
  }
  return true
}

// ---------------------------------------------------------------------------------------------------------------
// Grupos de opções (radios e abas) com teclado: setas movem e escolhem, Tab entra e sai do grupo.
// ---------------------------------------------------------------------------------------------------------------

/**
 * Teclado de um grupo de `role="radio"` ou `role="tab"`: só o item marcado fica no Tab (tabindex 0); as setas (e Home e
 * End) movem para o vizinho, focam e **escolhem** (clicam). Chame de novo depois de redesenhar o grupo.
 */
export function tecladoDoGrupo (grupo: HTMLElement, itemSelecionado: (el: HTMLElement) => boolean): void {
  const itens = Array.from(grupo.querySelectorAll<HTMLElement>('[role="radio"], [role="tab"]'))
  const marcado = itens.find(itemSelecionado) ?? itens[0]
  itens.forEach((el) => { el.tabIndex = el === marcado ? 0 : -1 })
  if (grupo.dataset.teclado === '1') return
  grupo.dataset.teclado = '1'
  grupo.addEventListener('keydown', (ev) => {
    const lista = Array.from(grupo.querySelectorAll<HTMLElement>('[role="radio"]:not([disabled]), [role="tab"]:not([disabled])'))
    const i = lista.findIndex((el) => el === document.activeElement)
    if (i < 0) return
    let destino: number | null = null
    if (ev.key === 'ArrowRight' || ev.key === 'ArrowDown') destino = (i + 1) % lista.length
    else if (ev.key === 'ArrowLeft' || ev.key === 'ArrowUp') destino = (i - 1 + lista.length) % lista.length
    else if (ev.key === 'Home') destino = 0
    else if (ev.key === 'End') destino = lista.length - 1
    if (destino === null) return
    ev.preventDefault()
    // O clique pode redesenhar o grupo: depois dele o foco vai para o item certo
    const alvo = lista[destino]
    const rotulo = alvo.getAttribute('aria-label') ?? alvo.textContent?.trim() ?? ''
    alvo.click()
    window.setTimeout(() => {
      const vivo = grupo.id !== '' ? document.getElementById(grupo.id) ?? grupo : grupo
      const novos = Array.from(vivo.querySelectorAll<HTMLElement>('[role="radio"], [role="tab"]'))
      const certo = novos.find((el) => (el.getAttribute('aria-label') ?? el.textContent?.trim() ?? '') === rotulo)
      certo?.focus()
    }, 0)
  })
}

// ---------------------------------------------------------------------------------------------------------------
// Erros ligados ao campo (o leitor de tela lê o erro junto com o campo) e anúncios.
// ---------------------------------------------------------------------------------------------------------------

/**
 * Liga (ou desliga) a mensagem de erro ao campo: `aria-invalid` e `aria-describedby`. O elemento da mensagem precisa
 * ter `id`. Com `mensagem` nula ou vazia, desliga.
 */
export function ligarErro (campo: HTMLElement | null, erro: HTMLElement, mensagem: string | null): void {
  if (mensagem === null || mensagem === '') {
    erro.hidden = true
    campo?.removeAttribute('aria-invalid')
    return
  }
  erro.textContent = mensagem
  erro.hidden = false
  if (campo === null) return
  campo.setAttribute('aria-invalid', 'true')
  const atual = (campo.getAttribute('aria-describedby') ?? '').split(' ').filter((x) => x !== '')
  if (erro.id !== '' && !atual.includes(erro.id)) campo.setAttribute('aria-describedby', [...atual, erro.id].join(' '))
}

/** Fala algo para o leitor de tela sem mostrar nada (uma região `aria-live` só para isso, fora do app). */
export function anunciar (texto: string): void {
  const regiao = document.getElementById('anuncios')
  if (regiao === null) return
  regiao.textContent = ''
  // Trocar o texto depois de esvaziar faz o leitor repetir mesmo uma frase igual à anterior
  window.setTimeout(() => { regiao.textContent = texto }, 50)
}
