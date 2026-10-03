// Acessibilidade e atualização automática sem atrapalhar quem está usando a página (mesmas ideias do app do quiosque).

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

/** Alguém está digitando ou escolhendo num campo desta página? */
export function controleEmUso (root: HTMLElement): boolean {
  const ativo = document.activeElement
  return ativo instanceof HTMLElement && root.contains(ativo) && ['INPUT', 'SELECT', 'TEXTAREA'].includes(ativo.tagName)
}

/**
 * Como `mount`, mas guarda onde estava o foco (e o cursor, num campo de texto) e a rolagem, e os devolve no mesmo
 * elemento da tela nova. Para as atualizações automáticas (tempo real, consulta periódica): quem navega pelo teclado
 * ou está digitando não perde o lugar. Se a pessoa está num campo de texto, a atualização espera ela sair dele.
 */
export function mountPreservando (root: HTMLElement, redesenhar: () => void, ...nodes: Node[]): void {
  if (controleEmUso(root)) {
    root.addEventListener('focusout', () => { window.setTimeout(redesenhar, 0) }, { once: true })
    return
  }
  const ativo = document.activeElement
  const guardado = ativo instanceof HTMLElement && root.contains(ativo) ? guardarFoco(ativo) : null
  const rolagem = window.scrollY
  root.replaceChildren(...nodes)
  if (guardado !== null) {
    const novo = acharFoco(root, guardado)
    if (novo !== null) {
      novo.focus({ preventScroll: true })
      if (guardado.inicio !== null && (novo instanceof HTMLInputElement || novo instanceof HTMLTextAreaElement)) {
        try { novo.setSelectionRange(guardado.inicio, guardado.fim) } catch { /* tipos sem seleção */ }
      }
    }
  }
  window.scrollTo(0, rolagem)
}

/**
 * Teclado de um grupo de `role="radio"`: só o marcado fica no Tab; as setas (e Home e End) movem para o vizinho, focam e
 * escolhem. O grupo precisa de `id` se for redesenhado a cada escolha (o foco volta ao item certo no grupo novo).
 */
export function tecladoDoGrupo (grupo: HTMLElement, marcado: (el: HTMLElement) => boolean): void {
  const itens = Array.from(grupo.querySelectorAll<HTMLElement>('[role="radio"]'))
  const atual = itens.find(marcado) ?? itens[0]
  itens.forEach((el) => { el.tabIndex = el === atual ? 0 : -1 })
  grupo.addEventListener('keydown', (ev) => {
    const lista = Array.from(grupo.querySelectorAll<HTMLElement>('[role="radio"]:not([disabled])'))
    const i = lista.findIndex((el) => el === document.activeElement)
    if (i < 0) return
    let destino: number | null = null
    if (ev.key === 'ArrowRight' || ev.key === 'ArrowDown') destino = (i + 1) % lista.length
    else if (ev.key === 'ArrowLeft' || ev.key === 'ArrowUp') destino = (i - 1 + lista.length) % lista.length
    else if (ev.key === 'Home') destino = 0
    else if (ev.key === 'End') destino = lista.length - 1
    if (destino === null) return
    ev.preventDefault()
    const alvo = lista[destino]
    const rotulo = alvo.textContent?.trim() ?? ''
    alvo.click()
    window.setTimeout(() => {
      const vivo = grupo.id !== '' ? document.getElementById(grupo.id) ?? grupo : grupo
      Array.from(vivo.querySelectorAll<HTMLElement>('[role="radio"]')).find((el) => (el.textContent?.trim() ?? '') === rotulo)?.focus()
    }, 0)
  })
}

/** Liga (ou desliga) a mensagem de erro ao campo: `aria-invalid` e `aria-describedby`. A mensagem precisa ter `id`. */
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

/** Fala algo para o leitor de tela sem mostrar nada (região `aria-live` própria, fora do app). */
export function anunciar (texto: string): void {
  const regiao = document.getElementById('anuncios')
  if (regiao === null) return
  regiao.textContent = ''
  window.setTimeout(() => { regiao.textContent = texto }, 50)
}
