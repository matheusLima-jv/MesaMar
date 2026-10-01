// Aviso curto no rodapé (acima das abas). Lido por leitores de tela (role=status/alert).
let timer: number | undefined

export function avisar (texto: string, erro = false): void {
  let el = document.getElementById('toast')
  if (el === null) {
    el = document.createElement('div')
    el.id = 'toast'
    document.body.append(el)
  }
  el.setAttribute('role', erro ? 'alert' : 'status')
  el.className = erro ? 'toast show err' : 'toast show'
  el.textContent = texto
  window.clearTimeout(timer)
  timer = window.setTimeout(() => { el?.classList.remove('show') }, erro ? 5000 : 2500)
}

/** Ao trocar de aba, o aviso da aba anterior some (não faz sentido fora do contexto). */
export function limparAviso (): void {
  window.clearTimeout(timer)
  document.getElementById('toast')?.classList.remove('show')
}
