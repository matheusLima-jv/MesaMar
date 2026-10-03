import { ApiError, conta, fecharConta, lerReais, pixPago, reabrirConta, type Conta, type Metodo } from './api'
import { type Contexto } from './views/casca'
import { type AcoesConta, type EstadoConta } from './views/conta'

/** Conta tem estado próprio e cancela timers/respostas quando a aplicação sai. */
export function criarContaStore (getToken: () => string, getContexto: () => Contexto,
  desenhar: (topo?: boolean) => void, atualizar: (redesenhar: boolean) => Promise<void>) {
  let ativo = true
  let geracao = 0
  let timer: number | undefined
  /** A tela "Conta": o extrato, a forma escolhida e o que o cliente digitou. */
  const estadoConta: EstadoConta = { conta: null, semSinal: false, erro: null, ocupado: false, metodo: null, troco: '', trocando: false, copiado: false }

  /** Busca o extrato da conta e redesenha a tela só se algo mudou (para não atrapalhar quem está digitando o troco). */
  async function carregarConta (): Promise<void> {
    const atual = geracao
    const eraSemSinal = estadoConta.semSinal
    const antes = JSON.stringify(estadoConta.conta)
    try {
      const nova = await conta(getToken())
      if (!ativo || atual !== geracao) return
      aplicarConta(nova)
    } catch {
      if (!ativo || atual !== geracao) return
      estadoConta.semSinal = true
    }
    if (location.hash === '#conta' && (eraSemSinal !== estadoConta.semSinal || antes !== JSON.stringify(estadoConta.conta))) desenhar(false)
  }

  /** Guarda a conta nova e acerta o que depende dela: o saldo do cabeçalho e a forma já escolhida. */
  function aplicarConta (nova: Conta): void {
    estadoConta.conta = nova
    estadoConta.semSinal = false
    getContexto().dados.saldoCentavos = nova.saldoCentavos
    if (nova.status !== 'AGUARDANDO_PAGAMENTO') estadoConta.trocando = false
    // Conta já fechada em outro momento (ou em outro celular): a forma escolhida volta a aparecer marcada
    if (estadoConta.metodo === null || (nova.metodo !== null && !estadoConta.trocando)) estadoConta.metodo = nova.metodo ?? estadoConta.metodo
  }

  /** Uma ação da conta por vez: os botões ficam desligados enquanto ela viaja, e a recusa do servidor vira aviso na tela. */
  async function agir (acao: () => Promise<Conta>): Promise<void> {
    if (!ativo || estadoConta.ocupado) return
    const atual = geracao
    estadoConta.ocupado = true
    estadoConta.erro = null
    desenhar(false)
    try {
      const nova = await acao()
      if (!ativo || atual !== geracao) return
      aplicarConta(nova)
      estadoConta.trocando = false
    } catch (e) {
      if (!ativo || atual !== geracao) return
      estadoConta.erro = e instanceof ApiError ? e.message : 'Algo deu errado. Tente de novo.'
      // 409: a situação da conta mudou (outro celular, o quiosque): mostra a conta como está agora
      if (e instanceof ApiError && e.status === 409) await carregarConta().catch(() => undefined)
    } finally {
      if (atual === geracao) estadoConta.ocupado = false
    }
    if (!ativo || atual !== geracao) return
    // O cardápio passa a avisar (ou deixa de avisar) que a conta está sendo fechada
    await atualizar(false)
    if (!ativo || atual !== geracao) return
    if (location.hash === '#conta') desenhar(false)
  }

  async function copiarCodigo (codigo: string): Promise<void> {
    const atual = geracao
    let ok: boolean
    try {
      await navigator.clipboard.writeText(codigo)
      ok = true
    } catch {
      // Sem permissão ou sem HTTPS: tenta o jeito antigo, com um campo escondido
      const campo = document.createElement('textarea')
      campo.value = codigo
      campo.setAttribute('readonly', '')
      campo.className = 'fora-da-tela'
      document.body.append(campo)
      campo.select()
      try { ok = document.execCommand('copy') } catch { ok = false }
      campo.remove()
    }
    if (!ativo || atual !== geracao) return
    estadoConta.erro = ok ? null : 'Não deu para copiar. Abra "Ver o código" abaixo do QR e copie à mão.'
    estadoConta.copiado = ok
    desenhar(false)
    window.clearTimeout(timer)
    if (ok) timer = window.setTimeout(() => { estadoConta.copiado = false; if (location.hash === '#conta') desenhar(false) }, 3_000)
  }

  const acoesConta: AcoesConta = {
    escolher: (m: Metodo) => {
      estadoConta.metodo = m
      estadoConta.erro = null
      desenhar(false)
    },
    digitarTroco: (texto: string) => { estadoConta.troco = texto },
    fechar: () => {
      const metodo = estadoConta.metodo
      if (metodo === null) return
      let troco: number | null = null
      if (metodo === 'DINHEIRO' && estadoConta.troco.trim() !== '') {
        troco = lerReais(estadoConta.troco)
        if (troco === null || troco <= 0) {
          estadoConta.erro = 'Não entendemos o valor do troco. Digite em reais, por exemplo: 100 ou 50,00.'
          desenhar(false)
          return
        }
      }
      void agir(async () => await fecharConta(getToken(), metodo, troco))
    },
    jaPaguei: () => { void agir(async () => await pixPago(getToken())) },
    reabrir: () => { void agir(async () => await reabrirConta(getToken())) },
    trocar: () => {
      estadoConta.trocando = !estadoConta.trocando
      estadoConta.erro = null
      desenhar(false)
    },
    copiar: (codigo: string) => { void copiarCodigo(codigo) }
  }

  return {
    estado: estadoConta, acoes: acoesConta, carregar: carregarConta,
    iniciar: () => { ativo = true; geracao++; estadoConta.ocupado = false },
    parar: () => { ativo = false; geracao++; window.clearTimeout(timer) }
  }
}
