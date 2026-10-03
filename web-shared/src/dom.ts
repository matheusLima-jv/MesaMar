// Montagem de elementos sem innerHTML: todo texto vira textContent, então nada que o usuário
// digitou (nome de produto, de cliente…) é interpretado como HTML.

type Attrs = Record<string, string | number | boolean | undefined | null | EventListener>
type Child = Node | string | number | null | undefined | false

export function h<K extends keyof HTMLElementTagNameMap> (
  tag: K,
  attrs: Attrs = {},
  ...children: Child[]
): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag)
  for (const [key, value] of Object.entries(attrs)) {
    if (value === undefined || value === null || value === false) continue
    if (key.startsWith('on') && typeof value === 'function') {
      el.addEventListener(key.slice(2).toLowerCase(), value)
    } else if (key === 'class') {
      el.className = String(value)
    } else if (value === true) {
      el.setAttribute(key, '')
    } else {
      el.setAttribute(key, String(value))
    }
  }
  for (const child of children) {
    if (child === null || child === undefined || child === false) continue
    el.append(child instanceof Node ? child : document.createTextNode(String(child)))
  }
  return el
}

export function mount (root: HTMLElement, ...nodes: Node[]): void {
  root.replaceChildren(...nodes)
}

/** localStorage pode falhar (aba anônima, armazenamento bloqueado): o app segue sem lembrar. */
export const lembrar = {
  get (chave: string): string | null {
    try { return localStorage.getItem(`mesamar.${chave}`) } catch { return null }
  },
  set (chave: string, valor: string): void {
    try { localStorage.setItem(`mesamar.${chave}`, valor) } catch { /* segue sem lembrar */ }
  }
}
