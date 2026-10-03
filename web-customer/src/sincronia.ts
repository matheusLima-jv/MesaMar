import { ouvirComanda } from './api'

/** Um ciclo de vida por aplicação: SSE, polling e debounce nunca sobrevivem a parar(). */
export class SincroniaCliente {
  private sair: (() => void) | undefined
  private timer: number | undefined
  private polling: number | undefined
  private conectado = false
  private ativo = false
  private geracao = 0
  private ocupada = false
  private novamente = false

  constructor (private readonly sincronizar: () => Promise<void>, private readonly pedidos: () => void,
    private readonly conta: () => void) {}

  iniciar (token: string, estado: (conectado: boolean) => void): void {
    this.parar()
    this.ativo = true
    const atual = this.geracao
    this.sair = ouvirComanda(token, () => { this.agendar() }, (conectado) => {
      if (!this.ativo || atual !== this.geracao) return
      this.conectado = conectado
      estado(conectado)
      if (conectado) this.agendar()
    })
  }

  agendar (): void {
    if (!this.ativo) return
    window.clearTimeout(this.timer)
    this.timer = window.setTimeout(() => { void this.executar() }, 250)
  }

  private async executar (): Promise<void> {
    if (!this.ativo) return
    if (this.ocupada) { this.novamente = true; return }
    const atual = this.geracao
    this.ocupada = true
    try { await this.sincronizar() } finally {
      if (atual === this.geracao) {
        this.ocupada = false
        if (this.novamente) { this.novamente = false; this.agendar() }
      }
    }
  }

  acompanhar (): void {
    window.clearTimeout(this.polling)
    if (!this.ativo) return
    const aba = location.hash
    if (aba !== '#pedidos' && aba !== '#conta') return
    const carregar = (): void => { if (aba === '#conta') this.conta(); else this.pedidos() }
    carregar()
    const volta = (): void => {
      if (!this.ativo || location.hash !== aba) return
      if (document.visibilityState === 'visible' && !this.conectado) this.agendar()
      this.polling = window.setTimeout(volta, 15_000)
    }
    this.polling = window.setTimeout(volta, 15_000)
  }

  parar (): void {
    this.ativo = false
    this.geracao++
    this.ocupada = false
    this.conectado = false
    this.novamente = false
    this.sair?.()
    this.sair = undefined
    window.clearTimeout(this.timer)
    window.clearTimeout(this.polling)
  }
}
