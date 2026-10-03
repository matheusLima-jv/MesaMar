/** Registro removível; iniciar novamente não acumula handlers de navegação. */
export class NavegacaoCliente {
  constructor (private readonly aoTrocar: () => void) {}

  iniciar (): void {
    this.parar()
    window.addEventListener('hashchange', this.aoTrocar)
  }

  parar (): void {
    window.removeEventListener('hashchange', this.aoTrocar)
  }
}
