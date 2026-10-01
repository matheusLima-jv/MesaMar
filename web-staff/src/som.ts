// Aviso de pedido novo com o app aberto: dois "bips" e vibração. O navegador só deixa tocar som depois de
// um toque do usuário, então o áudio é destravado no primeiro toque na tela (o login já conta).
import { lembrar } from './dom'

let audio: AudioContext | null = null

function destravar (): void {
  try {
    audio ??= new AudioContext()
    if (audio.state === 'suspended') void audio.resume()
  } catch { /* sem áudio neste aparelho: segue só com a vibração e o destaque */ }
}

document.addEventListener('pointerdown', destravar, { once: false, passive: true })

export function somLigado (): boolean {
  return lembrar.get('som') !== 'off'
}

export function definirSom (ligado: boolean): void {
  lembrar.set('som', ligado ? 'on' : 'off')
}

function bip (inicio: number, frequencia: number): void {
  if (audio === null) return
  const osc = audio.createOscillator()
  const ganho = audio.createGain()
  osc.type = 'sine'
  osc.frequency.value = frequencia
  ganho.gain.setValueAtTime(0.0001, audio.currentTime + inicio)
  ganho.gain.exponentialRampToValueAtTime(0.4, audio.currentTime + inicio + 0.02)
  ganho.gain.exponentialRampToValueAtTime(0.0001, audio.currentTime + inicio + 0.25)
  osc.connect(ganho).connect(audio.destination)
  osc.start(audio.currentTime + inicio)
  osc.stop(audio.currentTime + inicio + 0.3)
}

/** Pedido parado além do tempo: três bips mais graves e uma vibração mais longa. */
export function avisarAtraso (): void {
  if (!somLigado()) return
  bip(0, 660)
  bip(0.28, 660)
  bip(0.56, 520)
  try { navigator.vibrate?.([400, 150, 400]) } catch { /* sem vibração */ }
}

/** O cliente está indo pagar: três notas subindo, para não confundir com pedido novo (duas notas) nem atraso (graves). */
export function avisarPagamento (): void {
  if (!somLigado()) return
  bip(0, 740)
  bip(0.22, 932)
  bip(0.44, 1245)
  try { navigator.vibrate?.([150, 80, 150, 80, 150]) } catch { /* sem vibração */ }
}

/** Pedido novo na fila: som (se estiver ligado) e vibração. */
export function avisarPedidoNovo (): void {
  if (!somLigado()) return
  bip(0, 880)
  bip(0.3, 1175)
  try { navigator.vibrate?.([200, 100, 200]) } catch { /* sem vibração */ }
}
