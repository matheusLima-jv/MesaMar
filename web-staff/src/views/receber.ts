import { ApiError, fecharSemReceber, lerReais, novoId, receberPagamento, reais, type ContaAPagar, type Metodo } from '../api'
import { h } from '../dom'
import { avisar } from '../toast'

const FORMAS: Array<{ valor: Metodo, rotulo: string }> = [
  { valor: 'PIX', rotulo: 'Pix' },
  { valor: 'DINHEIRO', rotulo: 'Dinheiro' },
  { valor: 'CARTAO', rotulo: 'Cartão' }
]

const local = (c: ContaAPagar): string => (c.guardaSol === null ? 'Balcão' : `Guarda-sol ${c.guardaSol}`)

/** Um diálogo em tela cheia, com Esc para sair e o foco devolvido a quem abriu. */
function abrirDialogo (titulo: string, idTitulo: string, conteudo: Array<Node | null>, foco: () => HTMLElement | null): () => void {
  const anterior = document.activeElement as HTMLElement | null
  const fechar = (): void => {
    document.removeEventListener('keydown', teclas)
    tela.remove()
    anterior?.focus()
  }
  const teclas = (ev: KeyboardEvent): void => { if (ev.key === 'Escape') fechar() }
  const tela = h('div', { class: 'dialogo', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': idTitulo },
    h('div', { class: 'dialogo-painel' }, h('h2', { id: idTitulo }, titulo), ...conteudo)
  )
  document.body.append(tela)
  document.addEventListener('keydown', teclas)
  foco()?.focus()
  return fechar
}

/**
 * Receber um pagamento: a forma, quanto entra (total ou uma parte) e, no dinheiro, quanto o cliente entregou, com o
 * troco calculado. O pagamento ganha um id ao abrir o diálogo: tocar de novo ou repetir depois de uma queda de sinal
 * nunca cobra duas vezes. Pix lembra de conferir no app do banco antes de confirmar.
 */
export function dialogoReceber (c: ContaAPagar, aoConcluir: () => void): void {
  const idDoPagamento = novoId()
  const saldo = c.saldoCentavos
  let forma: Metodo = c.metodo ?? 'DINHEIRO'

  const valor = h('input', { id: 'rec-valor', type: 'text', inputmode: 'decimal', autocomplete: 'off', value: centavosParaCampo(saldo) })
  const entregue = h('input', { id: 'rec-entregue', type: 'text', inputmode: 'decimal', autocomplete: 'off', value: centavosParaCampo(c.trocoParaCentavos ?? saldo) })
  const troco = h('p', { class: 'troco', role: 'status' })
  const avisoPix = h('p', { class: 'aviso-pix', role: 'note', hidden: true }, 'Confira no app do seu banco antes de confirmar.')
  const campoEntregue = h('div', { class: 'field', hidden: true },
    h('label', { for: 'rec-entregue' }, 'O cliente entregou R$'),
    entregue,
    troco
  )
  const erro = h('p', { class: 'field-err', role: 'alert', hidden: true })
  const confirmar = h('button', { type: 'submit', class: 'btn btn-sun btn-block btn-big' }, 'Confirmar')

  const valorDigitado = (): number | null => lerReais(valor.value)
  const entregueDigitado = (): number | null => lerReais(entregue.value)

  const atualizar = (): void => {
    campoEntregue.hidden = forma !== 'DINHEIRO'
    avisoPix.hidden = forma !== 'PIX'
    const v = valorDigitado()
    const e = entregueDigitado()
    if (forma === 'DINHEIRO' && v !== null && e !== null && e >= v) {
      troco.textContent = e === v ? 'Sem troco.' : `Devolver de troco: ${reais(e - v)}`
    } else if (forma === 'DINHEIRO' && e !== null && v !== null) {
      troco.textContent = 'O cliente entregou menos que o valor.'
    } else {
      troco.textContent = ''
    }
    confirmar.textContent = v === null || v <= 0 ? 'Confirmar' : `Confirmar ${reais(v)} recebido`
  }

  const opcao = (m: { valor: Metodo, rotulo: string }): HTMLLabelElement => {
    const radio = h('input', {
      type: 'radio', name: 'forma', value: m.valor, checked: m.valor === forma,
      onchange: () => { forma = m.valor; erro.hidden = true; atualizar() }
    })
    return h('label', { class: 'opcao' }, radio, h('span', {}, m.rotulo))
  }

  const fechar = abrirDialogo(`Receber de ${c.cliente}`, 'receber-titulo', [
    h('p', { class: 'hint' }, `${local(c)} · falta pagar ${reais(saldo)}`),
    c.metodo !== null
      ? h('p', { class: 'hint' }, `O cliente escolheu ${FORMAS.find((f) => f.valor === c.metodo)?.rotulo ?? ''}${c.trocoParaCentavos !== null ? ` e vai pagar com ${reais(c.trocoParaCentavos)}` : ''}.`)
      : null,
    h('form', {
      novalidate: true,
      onsubmit: (ev: Event) => {
        ev.preventDefault()
        const v = valorDigitado()
        if (v === null || v <= 0) { mostrarErro('Digite quanto o cliente pagou, por exemplo 25 ou 25,50.'); return }
        if (v > saldo) { mostrarErro(`O valor passa do que falta pagar (${reais(saldo)}).`); return }
        let recebido: number | null = null
        if (forma === 'DINHEIRO') {
          recebido = entregueDigitado()
          if (recebido === null || recebido < v) { mostrarErro('Quanto o cliente entregou? Digite um valor igual ou maior que o recebido.'); return }
        }
        erro.hidden = true
        confirmar.disabled = true
        confirmar.textContent = 'Registrando…'
        receberPagamento(c.comandaId, { id: idDoPagamento, metodo: forma, valorCentavos: v, valorRecebidoCentavos: recebido })
          .then((r) => {
            fechar()
            const sobra = r.pagamento.trocoCentavos > 0 ? ` Troco: ${reais(r.pagamento.trocoCentavos)}.` : ''
            avisar(r.fechada ? `Conta de ${c.cliente} paga e encerrada.${sobra}` : `Recebido ${reais(v)} de ${c.cliente}. Ainda falta ${reais(r.conta.saldoCentavos)}.${sobra}`)
            aoConcluir()
          })
          .catch((e: unknown) => {
            confirmar.disabled = false
            atualizar()
            mostrarErro(e instanceof ApiError ? e.message : 'Não deu para registrar. Tente de novo (o pagamento não será cobrado duas vezes).')
          })
      }
    },
    h('fieldset', { class: 'grupo' }, h('legend', {}, 'Forma de pagamento'), ...FORMAS.map(opcao)),
    h('div', { class: 'field' }, h('label', { for: 'rec-valor' }, 'Valor recebido agora R$'), valor,
      h('p', { class: 'hint' }, 'Para receber só uma parte (conta dividida), diminua o valor. O resto continua na conta.')),
    campoEntregue,
    avisoPix,
    erro,
    confirmar,
    h('button', { type: 'button', class: 'link center-block', onclick: () => { fechar() } }, 'Voltar')
    )
  ], () => valor)

  const mostrarErro = (texto: string): void => {
    erro.textContent = texto
    erro.hidden = false
  }
  valor.addEventListener('input', atualizar)
  entregue.addEventListener('input', atualizar)
  atualizar()
}

/**
 * Fechamento forçado (só o dono): o cliente foi embora sem pagar. O motivo é obrigatório e fica registrado junto com o
 * valor que ficou sem receber.
 */
export function dialogoFecharSemReceber (c: ContaAPagar, aoConcluir: () => void): void {
  const motivo = h('input', { id: 'forcar-motivo', type: 'text', maxlength: 120, autocomplete: 'off', placeholder: 'ex.: foi embora sem pagar' })
  const erro = h('p', { class: 'field-err', role: 'alert', hidden: true })
  const confirmar = h('button', { type: 'submit', class: 'btn btn-danger btn-block btn-big' }, 'Fechar sem receber')
  const fechar = abrirDialogo('Fechar sem receber', 'forcar-titulo', [
    h('p', { class: 'hint' }, `${local(c)} · ${c.cliente} · falta ${reais(c.saldoCentavos)}`),
    h('p', {}, `Vai registrar ${reais(c.saldoCentavos)} como não recebido e encerrar a conta. O motivo fica guardado.`),
    h('form', {
      novalidate: true,
      onsubmit: (ev: Event) => {
        ev.preventDefault()
        const texto = motivo.value.trim()
        if (texto.length < 3) { erro.textContent = 'Explique o motivo, com pelo menos 3 letras.'; erro.hidden = false; return }
        erro.hidden = true
        confirmar.disabled = true
        confirmar.textContent = 'Fechando…'
        fecharSemReceber(c.comandaId, texto)
          .then(() => {
            fechar()
            avisar(`Conta de ${c.cliente} encerrada sem receber.`)
            aoConcluir()
          })
          .catch((e: unknown) => {
            confirmar.disabled = false
            confirmar.textContent = 'Fechar sem receber'
            erro.textContent = e instanceof ApiError ? e.message : 'Não deu para fechar. Tente de novo.'
            erro.hidden = false
          })
      }
    },
    h('div', { class: 'field' }, h('label', { for: 'forcar-motivo' }, 'Motivo'), motivo),
    erro,
    confirmar,
    h('button', { type: 'button', class: 'link center-block', onclick: () => { fechar() } }, 'Voltar')
    )
  ], () => motivo)
}

/** 2500 → "25,00" (o que o campo mostra e `lerReais` entende). */
function centavosParaCampo (centavos: number): string {
  return `${Math.floor(centavos / 100)},${String(centavos % 100).padStart(2, '0')}`
}
