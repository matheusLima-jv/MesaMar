import './style.css'
import {
  ApiError, comanda, conta, enviarPedido, fecharConta, lerReais, meusPedidos, novoId, ouvirComanda, pixPago, reabrirConta, tokenDaUrl,
  type Comanda, type Conta, type Metodo, type Pedido, type Produto
} from './api'
import { Carrinho, pendente } from './carrinho'
import { mountPreservando } from './a11y'
import { esquecerComanda, limparComandasAntigas, marcarVisto } from './armazenamento'
import { h, lembrar, mount } from './dom'
import { type Contexto } from './views/casca'
import { telaCardapio } from './views/cardapio'
import { telaCarrinho } from './views/carrinho'
import { telaConfirmacao, telaEnvio } from './views/envio'
import { telaPedidos } from './views/pedidos'
import { telaProblema } from './views/problema'
import { telaConta, type AcoesConta, type EstadoConta } from './views/conta'

const root = document.getElementById('app') as HTMLElement
const ATUALIZAR_APOS_MS = 30_000
const ESPERA_INICIAL_MS = 2_000
const ESPERA_MAXIMA_MS = 15_000
const ATUALIZAR_PEDIDOS_MS = 15_000

let token = ''
let ctx!: Contexto
let confirmado: Pedido | null = null
/** Último motivo de recusa do servidor, mostrado no carrinho (ex.: "Acabou: Água de coco"). */
let erroCarrinho: string | null = null
let semSinal = false
let enviando = false
let atualizadoEm = 0
/** Lista de "Meus pedidos" (`null` até a primeira resposta) e se a última tentativa de atualizar falhou. */
let meus: Pedido[] | null = null
let meusSemSinal = false
let timerPedidos: number | undefined
/** Conectado ao tempo real (SSE)? Se não, a lista de pedidos volta a se atualizar a cada 15 s. */
let tempoReal = false
let timerSincronia: number | undefined
/** Cancelamentos que o cliente já leu (ids de pedido e de item), guardados no navegador. */
let avisados = new Set<string>()
let idsDosAvisos: string[] = []
/** A tela "Conta": o extrato, a forma escolhida e o que o cliente digitou. */
const estadoConta: EstadoConta = { conta: null, semSinal: false, erro: null, ocupado: false, metodo: null, troco: '', trocando: false, copiado: false }

function indexar (dados: Comanda): Map<string, Produto> {
  return new Map(dados.cardapio.flatMap((s) => s.produtos).map((p) => [p.id, p]))
}

function usar (dados: Comanda): void {
  ctx = { dados, carrinho: ctx?.carrinho ?? new Carrinho(token), produtos: indexar(dados), avisos: ctx?.avisos ?? [], dispensarAvisos }
  atualizadoEm = Date.now()
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
  const emEnvio = pendente.ler(token)
  if (emEnvio !== null) {
    colocar(...telaEnvio(ctx, emEnvio, semSinal))
  } else if (location.hash === '#carrinho') {
    colocar(...telaCarrinho(ctx, erroCarrinho, () => { void enviar() }, () => { desenhar() }))
  } else if (location.hash === '#pedidos') {
    colocar(...telaPedidos(ctx, meus, meusSemSinal))
  } else if (location.hash === '#problema') {
    colocar(...telaProblema(ctx, token, telaAnterior, tempoReal))
  } else if (location.hash === '#conta') {
    colocar(...telaConta(ctx, estadoConta, acoesConta))
  } else if (location.hash === '#pedido' && confirmado !== null) {
    colocar(...telaConfirmacao(ctx, confirmado, () => { location.hash = '' }))
  } else {
    erroCarrinho = null
    colocar(...telaCardapio(ctx))
  }
  if (topo) window.scrollTo(0, 0)
}

/** Só o cardápio e "Meus pedidos" se redesenham sozinhos: carrinho, envio e confirmação não podem mudar debaixo do cliente. */
function redesenharSeLivre (): void {
  if (pendente.ler(token) === null && (location.hash === '' || location.hash === '#pedidos')) desenhar(false)
}

const motivoDe = (m: string | null): string => (m !== null && m !== '' ? `: ${m}` : '')

/** Cancelamentos do quiosque que o cliente ainda não leu viram avisos no topo, com o motivo. */
function calcularAvisos (): void {
  const textos: string[] = []
  const ids: string[] = []
  for (const p of meus ?? []) {
    if (p.status === 'CANCELADO') {
      if (avisados.has(p.id)) continue
      textos.push(`O quiosque cancelou o pedido nº ${p.numero}${motivoDe(p.motivoCancelamento)}.`)
      ids.push(p.id, ...p.itens.map((i) => i.id))
      continue
    }
    for (const i of p.itens) {
      if (!i.cancelado || avisados.has(i.id)) continue
      textos.push(`O quiosque cancelou ${i.quantidade}× ${i.nome} do pedido nº ${p.numero}${motivoDe(i.motivoCancelamento)}.`)
      ids.push(i.id)
    }
  }
  ctx.avisos = textos
  idsDosAvisos = ids
}

function dispensarAvisos (): void {
  idsDosAvisos.forEach((id) => avisados.add(id))
  lembrar.set(`avisados.${token}`, JSON.stringify([...avisados]))
  ctx.avisos = []
  idsDosAvisos = []
  desenhar(false)
}

/** Atualiza a lista de pedidos, o saldo e os avisos. Se falhar (sem sinal), segue com a última lista que a página tem. */
async function carregarPedidos (): Promise<void> {
  try {
    const r = await meusPedidos(token)
    meus = r.pedidos
    meusSemSinal = false
    ctx.dados.saldoCentavos = r.saldoCentavos
    calcularAvisos()
  } catch {
    meusSemSinal = true
  }
  redesenharSeLivre()
}

/** Busca o extrato da conta e redesenha a tela só se algo mudou (para não atrapalhar quem está digitando o troco). */
async function carregarConta (): Promise<void> {
  const eraSemSinal = estadoConta.semSinal
  const antes = JSON.stringify(estadoConta.conta)
  try {
    aplicarConta(await conta(token))
  } catch {
    estadoConta.semSinal = true
  }
  if (location.hash === '#conta' && (eraSemSinal !== estadoConta.semSinal || antes !== JSON.stringify(estadoConta.conta))) desenhar(false)
}

/** Guarda a conta nova e acerta o que depende dela: o saldo do cabeçalho e a forma já escolhida. */
function aplicarConta (nova: Conta): void {
  estadoConta.conta = nova
  estadoConta.semSinal = false
  ctx.dados.saldoCentavos = nova.saldoCentavos
  if (nova.status !== 'AGUARDANDO_PAGAMENTO') estadoConta.trocando = false
  // Conta já fechada em outro momento (ou em outro celular): a forma escolhida volta a aparecer marcada
  if (estadoConta.metodo === null || (nova.metodo !== null && !estadoConta.trocando)) estadoConta.metodo = nova.metodo ?? estadoConta.metodo
}

/** Uma ação da conta por vez: os botões ficam desligados enquanto ela viaja, e a recusa do servidor vira aviso na tela. */
async function agir (acao: () => Promise<Conta>): Promise<void> {
  if (estadoConta.ocupado) return
  estadoConta.ocupado = true
  estadoConta.erro = null
  desenhar(false)
  try {
    aplicarConta(await acao())
    estadoConta.trocando = false
  } catch (e) {
    estadoConta.erro = e instanceof ApiError ? e.message : 'Algo deu errado. Tente de novo.'
    // 409: a situação da conta mudou (outro celular, o quiosque): mostra a conta como está agora
    if (e instanceof ApiError && e.status === 409) await carregarConta().catch(() => undefined)
  } finally {
    estadoConta.ocupado = false
  }
  // O cardápio passa a avisar (ou deixa de avisar) que a conta está sendo fechada
  await atualizar(false)
  if (location.hash === '#conta') desenhar(false)
}

async function copiarCodigo (codigo: string): Promise<void> {
  let ok = false
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
  estadoConta.erro = ok ? null : 'Não deu para copiar. Abra "Ver o código" abaixo do QR e copie à mão.'
  estadoConta.copiado = ok
  desenhar(false)
  if (ok) window.setTimeout(() => { estadoConta.copiado = false; if (location.hash === '#conta') desenhar(false) }, 3_000)
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
    void agir(async () => await fecharConta(token, metodo, troco))
  },
  jaPaguei: () => { void agir(async () => await pixPago(token)) },
  reabrir: () => { void agir(async () => await reabrirConta(token)) },
  trocar: () => {
    estadoConta.trocando = !estadoConta.trocando
    estadoConta.erro = null
    desenhar(false)
  },
  copiar: (codigo: string) => { void copiarCodigo(codigo) }
}

/** Algo mudou no quiosque (status, item cancelado, conta): busca tudo de novo. */
async function sincronizar (): Promise<void> {
  if (enviando) return
  await atualizar(false)
  await carregarPedidos()
  if (location.hash === '#conta') await carregarConta()
}

/** Vários avisos seguidos viram uma busca só. */
function agendarSincronia (): void {
  window.clearTimeout(timerSincronia)
  timerSincronia = window.setTimeout(() => { void sincronizar() }, 250)
}

/** Enquanto "Meus pedidos" ou "Conta" está aberta: carrega agora e, sem o tempo real, de novo a cada 15 s. */
function acompanharPedidos (): void {
  window.clearTimeout(timerPedidos)
  const aba = location.hash
  if (aba !== '#pedidos' && aba !== '#conta') return
  void (aba === '#conta' ? carregarConta() : carregarPedidos())
  const volta = (): void => {
    if (location.hash !== aba) return
    if (document.visibilityState === 'visible' && !tempoReal) void (aba === '#conta' ? carregarConta() : carregarPedidos())
    timerPedidos = window.setTimeout(volta, ATUALIZAR_PEDIDOS_MS)
  }
  timerPedidos = window.setTimeout(volta, ATUALIZAR_PEDIDOS_MS)
}

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
  if (location.hash === '' && Date.now() - atualizadoEm > 10_000) void atualizar(true)
}

/** O cliente tocou em "Enviar pedido": o pedido ganha o seu id (uma vez só) e começam as tentativas. */
async function enviar (): Promise<void> {
  if (ctx.carrinho.linhas.length === 0 || enviando) return
  erroCarrinho = null
  pendente.guardar(token, { id: novoId(), itens: ctx.carrinho.itens(ctx.produtos) })
  await tentarEnviar()
}

/** Espera com ±20% de variação: vários celulares que perderam o sinal juntos não tentam todos no mesmo segundo. */
function comVariacao (ms: number): number {
  return Math.round(ms * (0.8 + Math.random() * 0.4))
}

function esperar (ms: number): Promise<void> {
  return new Promise((resolve) => {
    const fim = (): void => {
      window.clearTimeout(timer)
      window.removeEventListener('online', fim)
      resolve()
    }
    const timer = window.setTimeout(fim, ms)
    // Voltou o sinal: não precisa esperar o resto
    window.addEventListener('online', fim)
  })
}

/**
 * Repete o envio do mesmo pedido até o servidor responder. Sem sinal, erro do servidor ou limite de uso,
 * espera (2 s, 4 s… até 15 s) e tenta de novo: como o id é o mesmo, nunca vira pedido duplicado.
 * Recusa de verdade (item esgotado, pedidos pausados, conta fechada) encerra o envio e volta ao carrinho.
 */
async function tentarEnviar (): Promise<void> {
  if (enviando) return
  enviando = true
  semSinal = false
  desenhar()
  let espera = ESPERA_INICIAL_MS
  try {
    for (;;) {
      const pedido = pendente.ler(token)
      if (pedido === null) return
      try {
        const feito = await enviarPedido(token, { ...pedido, reenvio: pedido.reenvio === true })
        pendente.apagar(token)
        ctx.carrinho.limpar()
        confirmado = feito
        semSinal = false
        meus = null
        // Saldo e bloqueios novos (a conta pode ter chegado ao limite com este pedido)
        await atualizar(false)
        if (location.hash === '#pedido') desenhar()
        else location.hash = '#pedido'
        return
      } catch (e) {
        if (!(e instanceof ApiError) || !e.temporario) {
          pendente.apagar(token)
          await recusado(e instanceof ApiError ? e : new ApiError(0, 'erro', 'Algo deu errado. Tente de novo.'))
          return
        }
        // A próxima tentativa é um reenvio: se a equipe lançar este pedido nesse meio-tempo, o servidor reconhece
        if (pedido.reenvio !== true) pendente.guardar(token, { ...pedido, reenvio: true })
        if (!semSinal) { semSinal = true; desenhar() }
        // 429: espera o que o servidor pediu. O resto: espera crescente com variação (aparelhos não voltam juntos)
        await esperar(e.esperaMs ?? comVariacao(espera))
        espera = Math.min(espera * 2, ESPERA_MAXIMA_MS)
      }
    }
  } finally {
    enviando = false
  }
}

/** O servidor recusou o pedido: mostra o motivo no carrinho e deixa o cliente corrigir. */
async function recusado (e: ApiError): Promise<void> {
  erroCarrinho = e.message
  if (e.codigo === 'indisponivel') {
    // Tira do carrinho o que acabou e atualiza o cardápio, para o resto continuar valendo
    ctx.carrinho.remover(e.produtos)
    await atualizar(false)
  } else if (e.codigo === 'preco-mudou') {
    // Cardápio novo: o carrinho passa a mostrar o total com o preço de agora, e o cliente envia de novo se concordar
    await atualizar(false)
  } else if (e.status === 409 && e.codigo !== 'conflito') {
    // Pedidos pausados, conta fechando…: o cardápio passa a avisar também
    await atualizar(false)
  }
  semSinal = false
  if (location.hash === '#carrinho') desenhar()
  else location.hash = '#carrinho'
}

/** Cardápio e situação da comanda frescos (item esgotado, pedidos pausados…). */
async function atualizar (redesenhar: boolean): Promise<void> {
  try {
    usar(await comanda(token))
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
  const t = tokenDaUrl()
  if (t === null) {
    telaErro('Link incompleto', 'Leia o QR do seu atendente de novo para abrir a sua comanda.', false)
    return
  }
  token = t
  try {
    usar(await comanda(token))
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      // O link não vale mais (comanda vencida ou QR trocado): o que a página guardou dela no aparelho não serve para nada
      esquecerComanda(token)
      telaErro('Esse link não vale mais', e.message, false)
    }
    else telaErro('Sem sinal', 'Não deu para abrir a sua comanda. Confira a internet e tente de novo.', true)
    return
  }
  // Esta comanda foi aberta agora; as de outros dias, sem uso há mais de 7 dias, saem do aparelho
  marcarVisto(token)
  limparComandasAntigas(token)
  try {
    avisados = new Set(JSON.parse(lembrar.get(`avisados.${token}`) ?? '[]') as string[])
  } catch { avisados = new Set() }
  window.onhashchange = trocouDeTela
  desenhar()
  acompanharPedidos()
  // Tempo real: o quiosque avisa quando um pedido muda. Sem conexão, "Meus pedidos" volta a consultar a cada 15 s.
  ouvirComanda(token, agendarSincronia, (conectado) => {
    tempoReal = conectado
    if (conectado) agendarSincronia()
  })
  // Cancelamentos que aconteceram com a página fechada também viram aviso
  void carregarPedidos()
  // Pedido que não chegou antes de a página fechar: continua de onde parou
  if (pendente.ler(token) !== null) void tentarEnviar()
}

// Voltou para a página depois de um tempo (trocou de app, bloqueou a tela): confere se algo acabou
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && ctx !== undefined && (location.hash === '#pedidos' || location.hash === '#conta')) acompanharPedidos()
  if (document.visibilityState === 'visible' && ctx !== undefined && !enviando && Date.now() - atualizadoEm > ATUALIZAR_APOS_MS) {
    // Só redesenha o cardápio e o carrinho: a confirmação do pedido fica como está
    void atualizar(location.hash === '#carrinho' || location.hash === '')
  }
})

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

void iniciar()
