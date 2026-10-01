// Instalação na tela inicial. Android/Chrome: guardamos o evento beforeinstallprompt e mostramos
// um botão "Instalar". iPhone não tem esse evento: só dá para ensinar o caminho no Safari.

interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

let pedido: BeforeInstallPromptEvent | null = null
export const instalacao = new EventTarget()

window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault()
  pedido = e as BeforeInstallPromptEvent
  instalacao.dispatchEvent(new Event('mudou'))
})

window.addEventListener('appinstalled', () => {
  pedido = null
  instalacao.dispatchEvent(new Event('mudou'))
})

export type Plataforma = 'iphone' | 'android' | 'outro'

export function plataforma (): Plataforma {
  const ua = navigator.userAgent
  if (/iPhone|iPad|iPod/i.test(ua) || (ua.includes('Macintosh') && navigator.maxTouchPoints > 1)) return 'iphone'
  if (/Android/i.test(ua)) return 'android'
  return 'outro'
}

export function instalado (): boolean {
  const nav = navigator as Navigator & { standalone?: boolean }
  return window.matchMedia('(display-mode: standalone)').matches || nav.standalone === true
}

export function podeInstalarDireto (): boolean {
  return pedido !== null
}

export async function instalar (): Promise<boolean> {
  if (pedido === null) return false
  await pedido.prompt()
  const escolha = await pedido.userChoice
  pedido = null
  instalacao.dispatchEvent(new Event('mudou'))
  return escolha.outcome === 'accepted'
}
