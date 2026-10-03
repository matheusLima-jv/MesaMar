import { EstadoCliente } from './estado'
import { NavegacaoCliente } from './navegacao'
import {
  ApiError, comanda, enviarPedido, meusPedidos, novoId, tokenDaUrl,
  type Comanda, type Produto
} from './api'
import { Carrinho, pendente } from './carrinho'
import { mountPreservando } from './a11y'
import { esquecerComanda, limparComandasAntigas, marcarVisto } from './armazenamento'
import { h, lembrar, mount } from './dom'
import { telaCardapio } from './views/cardapio'
import { telaCarrinho } from './views/carrinho'
import { telaConfirmacao, telaEnvio } from './views/envio'
import { telaPedidos } from './views/pedidos'
import { telaProblema } from './views/problema'
import { telaConta } from './views/conta'
import { criarContaStore } from './conta-store'
import { SincroniaCliente } from './sincronia'

export function criarAplicacao (root: HTMLElement) {
  const estado = new EstadoCliente()
  let cancelamento = new AbortController()
  const navegacao = new NavegacaoCliente(trocouDeTela)
  const ATUALIZAR_APOS_MS = 30_000
  const ESPERA_INICIAL_MS = 2_000
  const ESPERA_MAXIMA_MS = 15_000

  function indexar (dados: Comanda): Map<string, Produto> {
    return new Map(dados.cardapio.flatMap((s) => s.produtos).map((p) => [p.id, p]))
  }

  function usar (dados: Comanda): void {
    estado.ctx = { dados, carrinho: estado.ctx?.carrinho ?? new Carrinho(estado.token), produtos: indexar(dados), avisos: estado.ctx?.avisos ?? [], dispensarAvisos }
    estado.atualizadoEm = Date.now()
  }

  /**
   * Desenha a tela que combina com a situação: pedido em envio, carrinho, confirmação, meus pedidos ou
   * cardápio. `topo` leva a página ao início (as atualizações automáticas não mexem na rolagem).
   */
  function desenhar (topo = true): void {
    // Tela nova (a pessoa navegou): troca tudo e volta ao topo. Atualização automática (tempo real, consulta): troca mas
    // mantém o foco, o cursor e a rolagem de quem está usando (e espera quem está digitando num campo)
    const colocar = (...nodes: Node[]): void => {
      if (topo) mount(root, ...nodes)
      else mountPreservando(root, () => { desenhar(false) }, ...nodes)
    }
    const emEnvio = pendente.ler(estado.token)
    if (emEnvio !== null) {
      colocar(...telaEnvio(estado.ctx, emEnvio, estado.semSinal))
    } else if (location.hash === '#carrinho') {
      colocar(...telaCarrinho(estado.ctx, estado.erroCarrinho, () => { void enviar() }, () => { desenhar() }))
    } else if (location.hash === '#pedidos') {
      colocar(...telaPedidos(estado.ctx, estado.meus, estado.meusSemSinal))
    } else if (location.hash === '#problema') {
      colocar(...telaProblema(estado.ctx, estado.token, telaAnterior, estado.tempoReal))
    } else if (location.hash === '#conta') {
      colocar(...telaConta(estado.ctx, estadoConta, acoesConta))
    } else if (location.hash === '#pedido' && estado.confirmado !== null) {
      colocar(...telaConfirmacao(estado.ctx, estado.confirmado, () => { location.hash = '' }))
    } else {
      estado.erroCarrinho = null
      colocar(...telaCardapio(estado.ctx))
    }
    if (topo) window.scrollTo(0, 0)
  }

  /** Só o cardápio e "Meus pedidos" se redesenham sozinhos: carrinho, envio e confirmação não podem mudar debaixo do cliente. */
  function redesenharSeLivre (): void {
    if (pendente.ler(estado.token) === null && (location.hash === '' || location.hash === '#pedidos')) desenhar(false)
  }

  const motivoDe = (m: string | null): string => (m !== null && m !== '' ? `: ${m}` : '')

  /** Cancelamentos do quiosque que o cliente ainda não leu viram avisos no topo, com o motivo. */
  function calcularAvisos (): void {
    const textos: string[] = []
    const ids: string[] = []
    for (const p of estado.meus ?? []) {
      if (p.status === 'CANCELADO') {
        if (estado.avisados.has(p.id)) continue
        textos.push(`O quiosque cancelou o pedido nº ${p.numero}${motivoDe(p.motivoCancelamento)}.`)
        ids.push(p.id, ...p.itens.map((i) => i.id))
        continue
      }
      for (const i of p.itens) {
        if (!i.cancelado || estado.avisados.has(i.id)) continue
        textos.push(`O quiosque cancelou ${i.quantidade}× ${i.nome} do pedido nº ${p.numero}${motivoDe(i.motivoCancelamento)}.`)
        ids.push(i.id)
      }
    }
    estado.ctx.avisos = textos
    estado.idsDosAvisos = ids
  }

  function dispensarAvisos (): void {
    estado.idsDosAvisos.forEach((id) => estado.avisados.add(id))
    lembrar.set(`avisados.${estado.token}`, JSON.stringify([...estado.avisados]))
    estado.ctx.avisos = []
    estado.idsDosAvisos = []
    desenhar(false)
  }

  /** Atualiza a lista de pedidos, o saldo e os avisos. Se falhar (sem sinal), segue com a última lista que a página tem. */
  async function carregarPedidos (): Promise<void> {
    const atual = estado.geracao
    try {
      const r = await meusPedidos(estado.token)
      if (!estado.ativo || atual !== estado.geracao) return
      estado.meus = r.pedidos
      estado.meusSemSinal = false
      estado.ctx.dados.saldoCentavos = r.saldoCentavos
      calcularAvisos()
    } catch {
      if (!estado.ativo || atual !== estado.geracao) return
      estado.meusSemSinal = true
    }
    redesenharSeLivre()
  }

  const contaStore = criarContaStore(() => estado.token, () => estado.ctx, desenhar, atualizar)
  const estadoConta = contaStore.estado
  const acoesConta = contaStore.acoes
  const carregarConta = contaStore.carregar

  const sincronia = new SincroniaCliente(sincronizar, () => { void carregarPedidos() }, () => { void carregarConta() })
  async function sincronizar (): Promise<void> {
    if (!estado.ativo || estado.enviando) return
    await atualizar(false)
    await carregarPedidos()
    if (location.hash === '#conta') await carregarConta()
  }
  const acompanharPedidos = (): void => { sincronia.acompanhar() }

  /** A tela onde a pessoa estava antes de abrir "Reportar problema" (vai junto no aviso). */
  let telaAnterior = 'cardapio'

  function trocouDeTela (): void {
    const atual = location.hash === '' ? 'cardapio' : location.hash.slice(1)
    if (atual !== 'problema') telaAnterior = atual
    desenhar()
    // Navegou por toque ou teclado: o foco vai para o conteúdo novo (quem usa leitor de tela começa por ele)
    document.getElementById('conteudo')?.focus({ preventScroll: true })
    acompanharPedidos()
    // Voltando ao cardápio depois de um tempo: confere se algo acabou ou se o limite da conta mudou
    if (location.hash === '' && Date.now() - estado.atualizadoEm > 10_000) void atualizar(true)
  }

  /** O cliente tocou em "Enviar pedido": o pedido ganha o seu id (uma vez só) e começam as tentativas. */
  async function enviar (): Promise<void> {
    if (estado.ctx.carrinho.linhas.length === 0 || estado.enviando) return
    estado.erroCarrinho = null
    pendente.guardar(estado.token, { id: novoId(), itens: estado.ctx.carrinho.itens(estado.ctx.produtos) })
    await tentarEnviar()
  }

  /** Espera com ±20% de variação: vários celulares que perderam o sinal juntos não tentam todos no mesmo segundo. */
  function comVariacao (ms: number): number {
    return Math.round(ms * (0.8 + Math.random() * 0.4))
  }

  function esperar (ms: number): Promise<void> {
    return new Promise((resolve) => {
      const sinal = cancelamento.signal
      const fim = (): void => {
        window.clearTimeout(timer)
        window.removeEventListener('online', fim)
        sinal.removeEventListener('abort', fim)
        resolve()
      }
      const timer = window.setTimeout(fim, ms)
      // Voltou o sinal: não precisa esperar o resto
      window.addEventListener('online', fim)
      sinal.addEventListener('abort', fim, { once: true })
    })
  }

  /**
   * Repete o envio do mesmo pedido até o servidor responder. Sem sinal, erro do servidor ou limite de uso,
   * espera (2 s, 4 s… até 15 s) e tenta de novo: como o id é o mesmo, nunca vira pedido duplicado.
   * Recusa de verdade (item esgotado, pedidos pausados, conta fechada) encerra o envio e volta ao carrinho.
   */
  async function tentarEnviar (): Promise<void> {
    if (estado.enviando) return
    const atual = estado.geracao
    estado.enviando = true
    estado.semSinal = false
    desenhar()
    let espera = ESPERA_INICIAL_MS
    try {
      while (estado.ativo && atual === estado.geracao) {
        const pedido = pendente.ler(estado.token)
        if (pedido === null) return
        try {
          const feito = await enviarPedido(estado.token, { ...pedido, reenvio: pedido.reenvio === true })
          if (!estado.ativo || atual !== estado.geracao) return
          pendente.apagar(estado.token)
          estado.ctx.carrinho.limpar()
          estado.confirmado = feito
          estado.semSinal = false
          estado.meus = null
          // Saldo e bloqueios novos (a conta pode ter chegado ao limite com este pedido)
          await atualizar(false)
          if (!estado.ativo || atual !== estado.geracao) return
          if (location.hash === '#pedido') desenhar()
          else location.hash = '#pedido'
          return
        } catch (e) {
          if (!estado.ativo || atual !== estado.geracao) return
          if (!(e instanceof ApiError) || !e.temporario) {
            pendente.apagar(estado.token)
            await recusado(e instanceof ApiError ? e : new ApiError(0, 'erro', 'Algo deu errado. Tente de novo.'))
            return
          }
          // A próxima tentativa é um reenvio: se a equipe lançar este pedido nesse meio-tempo, o servidor reconhece
          if (pedido.reenvio !== true) pendente.guardar(estado.token, { ...pedido, reenvio: true })
          if (!estado.semSinal) { estado.semSinal = true; desenhar() }
          // 429: espera o que o servidor pediu. O resto: espera crescente com variação (aparelhos não voltam juntos)
          await esperar(e.esperaMs ?? comVariacao(espera))
          espera = Math.min(espera * 2, ESPERA_MAXIMA_MS)
        }
      }
    } finally {
      if (atual === estado.geracao) estado.enviando = false
    }
  }

  /** O servidor recusou o pedido: mostra o motivo no carrinho e deixa o cliente corrigir. */
  async function recusado (e: ApiError): Promise<void> {
    estado.erroCarrinho = e.message
    if (e.codigo === 'indisponivel') {
      // Tira do carrinho o que acabou e atualiza o cardápio, para o resto continuar valendo
      estado.ctx.carrinho.remover(e.produtos)
      await atualizar(false)
    } else if (e.codigo === 'preco-mudou') {
      // Cardápio novo: o carrinho passa a mostrar o total com o preço de agora, e o cliente envia de novo se concordar
      await atualizar(false)
    } else if (e.status === 409 && e.codigo !== 'conflito') {
      // Pedidos pausados, conta fechando…: o cardápio passa a avisar também
      await atualizar(false)
    }
    estado.semSinal = false
    if (location.hash === '#carrinho') desenhar()
    else location.hash = '#carrinho'
  }

  /** Cardápio e situação da comanda frescos (item esgotado, pedidos pausados…). */
  async function atualizar (redesenhar: boolean): Promise<void> {
    try {
      const atual = estado.geracao
      const dados = await comanda(estado.token)
      if (!estado.ativo || atual !== estado.geracao) return
      usar(dados)
      if (redesenhar) desenhar()
    } catch { /* sem sinal: segue com o que a página já tem */ }
  }

  function telaErro (titulo: string, texto: string, tentarDeNovo: boolean): void {
    mount(root, h('main', { class: 'page' },
      h('p', { class: 'shop' }, 'MesaMar'),
      h('div', { class: 'empty' },
        h('h1', {}, titulo),
        h('p', {}, texto),
        tentarDeNovo ? h('button', { type: 'button', class: 'btn btn-sun btn-big', onclick: () => { void iniciar() } }, 'Tentar de novo') : null
      )
    ))
  }

  async function iniciar (): Promise<void> {
    cancelamento.abort()
    cancelamento = new AbortController()
    estado.ativo = true
    estado.enviando = false
    estado.geracao++
    document.addEventListener('visibilitychange', aoVisivel)
    sincronia.parar()
    contaStore.iniciar()
    const t = tokenDaUrl()
    if (t === null) {
      telaErro('Link incompleto', 'Leia o QR do seu atendente de novo para abrir a sua comanda.', false)
      return
    }
    estado.token = t
    const atual = estado.geracao
    try {
      const dados = await comanda(estado.token)
      if (!estado.ativo || atual !== estado.geracao) return
      usar(dados)
    } catch (e) {
      if (!estado.ativo || atual !== estado.geracao) return
      if (e instanceof ApiError && e.status === 404) {
        // O link não vale mais (comanda vencida ou QR trocado): o que a página guardou dela no aparelho não serve para nada
        esquecerComanda(estado.token)
        telaErro('Esse link não vale mais', e.message, false)
      }
      else telaErro('Sem sinal', 'Não deu para abrir a sua comanda. Confira a internet e tente de novo.', true)
      return
    }
    // Esta comanda foi aberta agora; as de outros dias, sem uso há mais de 7 dias, saem do aparelho
    marcarVisto(estado.token)
    limparComandasAntigas(estado.token)
    try {
      estado.avisados = new Set(JSON.parse(lembrar.get(`avisados.${estado.token}`) ?? '[]') as string[])
    } catch { estado.avisados = new Set() }
    navegacao.iniciar()
    desenhar()
    // Tempo real: o quiosque avisa quando um pedido muda. Sem conexão, "Meus pedidos" volta a consultar a cada 15 s.
    sincronia.iniciar(estado.token, (conectado) => { estado.tempoReal = conectado })
    acompanharPedidos()
    // Cancelamentos que aconteceram com a página fechada também viram aviso
    void carregarPedidos()
    // Pedido que não chegou antes de a página fechar: continua de onde parou
    if (pendente.ler(estado.token) !== null) void tentarEnviar()
  }

  // Voltou para a página depois de um tempo (trocou de app, bloqueou a tela): confere se algo acabou
  const aoVisivel = (): void => {
    if (document.visibilityState === 'visible' && estado.ctx !== undefined && (location.hash === '#pedidos' || location.hash === '#conta')) acompanharPedidos()
    if (document.visibilityState === 'visible' && estado.ctx !== undefined && !estado.enviando && Date.now() - estado.atualizadoEm > ATUALIZAR_APOS_MS) {
      // Só redesenha o cardápio e o carrinho: a confirmação do pedido fica como está
      void atualizar(location.hash === '#carrinho' || location.hash === '')
    }
  }

  return {
    iniciar,
    parar: () => {
      estado.ativo = false
      cancelamento.abort()
      estado.geracao++
      sincronia.parar()
      contaStore.parar()
      navegacao.parar()
      document.removeEventListener('visibilitychange', aoVisivel)
    }
  }
}
