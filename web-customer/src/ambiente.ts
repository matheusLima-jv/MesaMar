/** Integrações do navegador, instaladas uma única vez pelo bootstrap. */
export function iniciarAmbiente (): void {
if ('serviceWorker' in navigator) {
  // O service worker fica em /c/ para controlar esta página (o escopo é a pasta dele).
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/c/sw.js', { scope: '/c/' }).catch(() => undefined)
  })
}

/** Campos de texto abertos = teclado na tela: a barra fixa do pedido sai do caminho (ver `body.teclado` no CSS). */
function ehCampoDeTexto (el: EventTarget | null): boolean {
  if (!(el instanceof HTMLElement) || !['INPUT', 'TEXTAREA'].includes(el.tagName)) return false
  return !(el instanceof HTMLInputElement) || !['checkbox', 'radio', 'button', 'submit', 'range'].includes(el.type)
}
document.addEventListener('focusin', (ev) => { if (ehCampoDeTexto(ev.target)) document.body.classList.add('teclado') })
document.addEventListener('focusout', () => {
  window.setTimeout(() => { if (!ehCampoDeTexto(document.activeElement)) document.body.classList.remove('teclado') }, 100)
})

}
