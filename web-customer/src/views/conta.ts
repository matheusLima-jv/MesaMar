import { tecladoDoGrupo } from '../a11y'
import { reais, type Conta, type Metodo, type Pedido } from '../api'
import { h } from '../dom'
import { qrSvg } from '../qr'
import { casca, type Contexto } from './casca'

/** O que a tela "Conta" lembra entre uma atualização e outra (para não perder o que o cliente digitou). */
export interface EstadoConta {
  /** `null` até a primeira resposta. */
  conta: Conta | null
  semSinal: boolean
  /** Por que a última ação foi recusada (ex.: "Este quiosque não recebe Pix pelo MesaMar"). */
  erro: string | null
  /** Uma ação está a caminho: os botões ficam desligados para um toque duplo não repetir. */
  ocupado: boolean
  /** A forma escolhida na lista, antes de fechar a conta. */
  metodo: Metodo | null
  /** O que foi digitado em "vou pagar com R$". */
  troco: string
  /** Com a conta aguardando: o cliente quer trocar a forma de pagamento. */
  trocando: boolean
  /** O código Pix foi copiado agora há pouco. */
  copiado: boolean
}

export interface AcoesConta {
  escolher: (m: Metodo) => void
  digitarTroco: (texto: string) => void
  fechar: () => void
  jaPaguei: () => void
  reabrir: () => void
  trocar: () => void
  copiar: (codigo: string) => void
}

const ROTULO_METODO: Record<Metodo, string> = { PIX: 'Pix', DINHEIRO: 'Dinheiro', CARTAO: 'Cartão' }

const AJUDA_METODO: Record<Metodo, string> = {
  PIX: 'O QR e o código aparecem aqui, no seu celular.',
  DINHEIRO: 'Você paga no quiosque. Pode avisar para quanto precisa de troco.',
  CARTAO: 'Você paga na maquininha do quiosque.'
}

/**
 * "Conta": o extrato, a escolha de como pagar e, depois de fechar, a espera pela confirmação do quiosque
 * (com o QR do Pix, quando é o caso). A comanda só deixa de aceitar pedidos ao fechar, e dá para voltar
 * enquanto o quiosque não confirmou nenhum pagamento.
 */
export function telaConta (ctx: Contexto, e: EstadoConta, acoes: AcoesConta): Node[] {
  const c = e.conta
  const titulo = h('h2', { class: 'screen-title' }, 'Conta')
  const semSinal = e.semSinal
    ? h('p', { class: 'alert', role: 'status' }, c === null ? 'Sem sinal. Não deu para carregar a conta.' : 'Sem sinal. Mostrando a última conta que carregou.')
    : ''
  const erro = e.erro !== null ? h('p', { class: 'alert', role: 'alert', id: 'conta-erro' }, e.erro) : ''
  if (c === null) {
    return casca(ctx, 'conta', titulo, semSinal, e.semSinal ? '' : h('p', { class: 'hint' }, 'Carregando a sua conta…'))
  }

  const corpo: Array<Node | string> = []
  switch (c.status) {
    case 'ABERTA':
      corpo.push(...extrato(c), ...escolha(c, e, acoes))
      break
    case 'AGUARDANDO_PAGAMENTO':
      corpo.push(...(e.trocando ? [...extrato(c), ...escolha(c, e, acoes)] : [...aguardando(c, e, acoes), ...extrato(c)]))
      break
    case 'FECHADA':
      if (c.encerradaComSaldo === true) {
        corpo.push(
          h('div', { class: 'done', role: 'status' },
            h('h2', {}, 'Conta encerrada pelo quiosque'),
            h('p', {}, `Ficaram ${reais(c.saldoCentavos)} sem pagamento registrado. Se você já pagou, fale com o quiosque.`)
          ),
          ...extrato(c)
        )
        break
      }
      corpo.push(
        h('div', { class: 'done', role: 'status' },
          h('p', { class: 'done-mark', 'aria-hidden': 'true' }, '✓'),
          h('h2', {}, 'Conta paga'),
          h('p', {}, 'Obrigado pela visita! O seu extrato fica aqui por 7 dias.')
        ),
        ...extrato(c)
      )
      break
    default:
      corpo.push(
        h('p', { class: 'alert', role: 'status' }, c.status === 'BLOQUEADA'
          ? 'Sua comanda está bloqueada. Fale com um atendente.'
          : 'Esta comanda foi cancelada. Fale com um atendente.'),
        ...extrato(c)
      )
  }
  return casca(ctx, 'conta', titulo, semSinal, erro, ...corpo)
}

/** Itens de todos os pedidos (os cancelados riscados) e os totais: pedido, pago e saldo. */
function extrato (c: Conta): Array<Node | string> {
  const lista = c.pedidos.length === 0
    ? h('p', { class: 'hint' }, 'Você ainda não pediu nada.')
    : h('ul', { class: 'orders' }, ...c.pedidos.map(cartaoPedido))
  return [
    lista,
    c.pagamentos.length > 0
      ? h('ul', { class: 'cart-list plain' }, ...c.pagamentos.map((p) =>
        h('li', {},
          h('strong', {}, `Pago em ${p.rotulo.toLowerCase()} · ${p.hora}`),
          p.valorRecebidoCentavos !== null && p.valorRecebidoCentavos > p.valorCentavos
            ? h('span', { class: 'desc' }, `Recebido ${reais(p.valorRecebidoCentavos)} · troco ${reais(p.valorRecebidoCentavos - p.valorCentavos)}`)
            : null,
          h('span', { class: 'price' }, `− ${reais(p.valorCentavos)}`)
        )))
      : '',
    h('dl', { class: 'resumo' },
      linhaResumo('Pedidos', reais(c.pedidoCentavos)),
      c.pagoCentavos > 0 ? linhaResumo('Já pago', `− ${reais(c.pagoCentavos)}`) : '',
      linhaResumo(c.status === 'FECHADA' && c.encerradaComSaldo === true ? 'Ficou sem pagar' : 'Falta pagar', reais(c.saldoCentavos), true)
    )
  ]
}

function linhaResumo (rotulo: string, valor: string, forte = false): HTMLElement {
  return h('div', { class: forte ? 'resumo-linha forte' : 'resumo-linha' }, h('dt', {}, rotulo), h('dd', {}, valor))
}

function cartaoPedido (p: Pedido): HTMLElement {
  const cancelado = p.status === 'CANCELADO'
  return h('li', { class: `order st-${p.status.toLowerCase()}` },
    h('div', { class: 'order-head' },
      h('strong', {}, `Pedido nº ${p.numero}`, h('span', { class: 'order-hora' }, ` · ${p.hora}`)),
      cancelado ? h('span', { class: 'badge' }, 'Cancelado') : null
    ),
    h('ul', { class: 'order-items' }, ...p.itens.map((i) =>
      h('li', { class: cancelado || i.cancelado ? 'is-sold' : '' },
        h('strong', {}, `${i.quantidade}× ${i.nome}`),
        h('span', { class: 'price' }, reais(i.subtotalCentavos))
      )
    ))
  )
}

/** As formas de pagamento: um grupo de radios (as setas movem e escolhem; o Tab entra e sai do grupo). */
function grupoDeFormas (formas: Metodo[], e: EstadoConta, acoes: AcoesConta): HTMLElement {
  const grupo = h('div', { class: 'formas', id: 'formas-pagamento', role: 'radiogroup', 'aria-label': 'Forma de pagamento' }, ...formas.map((m) =>
    h('button', {
      type: 'button',
      role: 'radio',
      class: e.metodo === m ? 'forma on' : 'forma',
      'aria-checked': e.metodo === m ? 'true' : 'false',
      onclick: () => { acoes.escolher(m) }
    }, h('strong', {}, ROTULO_METODO[m]), h('span', {}, AJUDA_METODO[m]))
  ))
  tecladoDoGrupo(grupo, (el) => el.getAttribute('aria-checked') === 'true')
  return grupo
}

/** A escolha da forma de pagamento e o botão que fecha a conta (ou troca a forma, se já estava fechando). */
function escolha (c: Conta, e: EstadoConta, acoes: AcoesConta): Array<Node | string> {
  if (c.saldoCentavos <= 0) {
    return [h('p', { class: 'info' }, 'Não há nada para pagar agora. Quando você pedir, a conta aparece aqui.')]
  }
  const formas: Metodo[] = c.aceitaPix ? ['PIX', 'DINHEIRO', 'CARTAO'] : ['DINHEIRO', 'CARTAO']
  const trocando = c.status === 'AGUARDANDO_PAGAMENTO'
  return [
    h('h3', { class: 'sub' }, 'Como você vai pagar?'),
    grupoDeFormas(formas, e, acoes),
    e.metodo === 'DINHEIRO'
      ? h('div', { class: 'field' },
        h('label', { for: 'troco' }, 'Vou pagar com R$ ', h('span', { class: 'opt' }, '(opcional, para separarem o troco)')),
        h('input', {
          id: 'troco',
          type: 'text',
          inputmode: 'decimal',
          autocomplete: 'off',
          placeholder: 'Ex.: 100',
          // O erro da conta fica ligado ao campo do troco quando é dele (o leitor de tela lê junto)
          'aria-invalid': e.erro !== null && e.erro.includes('troco') ? 'true' : undefined,
          'aria-describedby': e.erro !== null && e.erro.includes('troco') ? 'conta-erro' : undefined,
          value: e.troco,
          oninput: (ev: Event) => { acoes.digitarTroco((ev.target as HTMLInputElement).value) }
        }))
      : '',
    h('button', {
      type: 'button',
      class: 'btn btn-sun btn-big btn-block btn-gap',
      disabled: e.metodo === null || e.ocupado,
      onclick: acoes.fechar
    }, e.ocupado ? 'Um instante…' : trocando ? 'Trocar a forma de pagamento' : 'Fechar a conta'),
    trocando
      ? h('button', { type: 'button', class: 'btn btn-line btn-block btn-gap', onclick: acoes.trocar }, 'Manter como estava')
      : h('p', { class: 'hint' }, 'Ao fechar, novos pedidos ficam bloqueados. Você pode voltar e pedir mais até o quiosque confirmar o pagamento.')
  ]
}

/** Conta fechada, esperando o quiosque: o que fazer agora (Pix no celular ou ir ao quiosque) e como voltar. */
function aguardando (c: Conta, e: EstadoConta, acoes: AcoesConta): Array<Node | string> {
  const metodo = c.metodo ?? 'CARTAO'
  const blocos: Array<Node | string> = [
    h('div', { class: 'wait', role: 'status' },
      h('h2', {}, 'Aguardando confirmação do quiosque'),
      h('p', {}, `Falta pagar ${reais(c.saldoCentavos)} · ${ROTULO_METODO[metodo]}`)
    )
  ]
  if (metodo === 'PIX') {
    blocos.push(...pix(c, e, acoes))
  } else {
    blocos.push(
      h('p', { class: 'info' }, metodo === 'DINHEIRO'
        ? `Vá ao quiosque pagar ${reais(c.saldoCentavos)} em dinheiro${c.trocoParaCentavos !== null ? ` (troco para ${reais(c.trocoParaCentavos)})` : ''}.`
        : `Vá ao quiosque pagar ${reais(c.saldoCentavos)} na maquininha.`),
      c.avisadoEm !== null ? h('p', { class: 'hint' }, `O quiosque foi avisado às ${c.avisadoEm}.`) : ''
    )
  }
  if (c.pagoCentavos === 0) {
    blocos.push(h('button', { type: 'button', class: 'btn btn-line btn-block btn-gap', disabled: e.ocupado, onclick: acoes.reabrir }, 'Voltar e pedir mais'))
  } else {
    blocos.push(h('p', { class: 'hint' }, 'O quiosque já confirmou um pagamento. Para pedir mais, fale com um atendente.'))
  }
  blocos.push(h('button', { type: 'button', class: 'btn btn-line btn-block btn-gap', disabled: e.ocupado, onclick: acoes.trocar }, 'Trocar a forma de pagamento'))
  return blocos
}

function pix (c: Conta, e: EstadoConta, acoes: AcoesConta): Array<Node | string> {
  if (c.pix === null) return [h('p', { class: 'alert' }, 'Não deu para gerar o Pix. Troque a forma de pagamento ou fale com um atendente.')]
  const p = c.pix
  return [
    h('div', { class: 'pix' },
      qrSvg(p.codigo, `QR do Pix de ${reais(p.valorCentavos)} para ${p.nomeRecebedor}`),
      h('p', { class: 'pix-valor' }, reais(p.valorCentavos)),
      h('p', { class: 'hint' }, `Para ${p.nomeRecebedor} · ${p.cidade}`)
    ),
    h('button', { type: 'button', class: 'btn btn-line btn-block', onclick: () => { acoes.copiar(p.codigo) } },
      e.copiado ? 'Código copiado ✓' : 'Copiar código Pix'),
    h('p', { class: 'hint' }, 'No app do seu banco, escolha Pix, "copia e cola" (ou leia o QR com outro celular) e confira o valor antes de pagar.'),
    h('details', { class: 'codigo-pix' },
      h('summary', {}, 'Ver o código'),
      h('textarea', { readonly: true, rows: 5, 'aria-label': 'Código Pix copia e cola' }, p.codigo)),
    c.avisadoEm === null
      ? h('button', { type: 'button', class: 'btn btn-sun btn-big btn-block btn-gap', disabled: e.ocupado, onclick: acoes.jaPaguei }, 'Já paguei')
      : h('p', { class: 'info' }, `Você avisou às ${c.avisadoEm}. O quiosque vai conferir o Pix no app do banco.`)
  ]
}
