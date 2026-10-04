import { ApiError, fecharSemReceber, lerReais, novoId, receberPagamento, reais, type ContaAPagar, type Metodo } from '../api'
import { ativarModal, ligarErro } from '../a11y'
import { h, lembrar, registrarSobreposicao } from '../dom'
import { avisar } from '../toast'

const FORMAS: Array<{ valor: Metodo, rotulo: string }> = [
  { valor: 'PIX', rotulo: 'Pix' },
  { valor: 'DINHEIRO', rotulo: 'Dinheiro' },
  { valor: 'CARTAO', rotulo: 'Cartão' }
]

const local = (c: ContaAPagar): string => (c.guardaSol === null ? 'Balcão' : `Guarda-sol ${c.guardaSol}`)

/**
 * Um diálogo em tela cheia: o resto do app fica inerte, o Tab não sai dele, o Esc fecha e o foco volta a quem abriu.
 * Some junto se a pessoa trocar de aba, sair ou a sessão acabar.
 */
function abrirDialogo (titulo: string, idTitulo: string, conteudo: Array<Node | null>, foco: () => HTMLElement | null): () => void {
  const fechar = (): void => {
    desregistrar()
    desativar()
  }
  const tela = h('div', { class: 'dialogo', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': idTitulo },
    h('div', { class: 'dialogo-painel' }, h('h2', { id: idTitulo }, titulo), ...conteudo)
  )
  const desativar = ativarModal(tela, { aoEscape: fechar, foco })
  const desregistrar = registrarSobreposicao(desativar)
  return fechar
}

/** Um pagamento enviado que ficou sem resposta: pode ter sido gravado ou não. */
interface PagamentoIncerto { id: string, metodo: Metodo, valorCentavos: number, valorRecebidoCentavos: number | null }

const incertos = new Map<string, PagamentoIncerto | null>()
const chaveIncerto = (comandaId: string): string => `pagamento-incerto.${comandaId}`

/**
 * O pagamento desta comanda que saiu e não teve resposta. Fica na memória e no armazenamento do aparelho, para
 * sobreviver a fechar o diálogo (e a recarregar o app): reabrir reenvia o MESMO id, e o servidor não cobra duas vezes.
 */
export const pagamentoIncerto = {
  ler (comandaId: string): PagamentoIncerto | null {
    if (incertos.has(comandaId)) return incertos.get(comandaId) ?? null
    try {
      const p = JSON.parse(lembrar.get(chaveIncerto(comandaId)) ?? 'null') as PagamentoIncerto | null
      return p !== null && typeof p.id === 'string' && typeof p.valorCentavos === 'number' ? p : null
    } catch { return null }
  },
  guardar (comandaId: string, p: PagamentoIncerto): void {
    incertos.set(comandaId, p)
    // `em` só serve para expirar (limparDadosAntigos): o id de um pagamento esquecido não fica no aparelho para sempre
    lembrar.set(chaveIncerto(comandaId), JSON.stringify({ ...p, em: Date.now() }))
  },
  apagar (comandaId: string): void {
    incertos.set(comandaId, null)
    lembrar.set(chaveIncerto(comandaId), 'null')
  }
}

/**
 * Receber um pagamento: a forma, quanto entra (total ou uma parte) e, no dinheiro, quanto o cliente entregou, com o
 * troco calculado. O pagamento ganha um id ao abrir o diálogo: tocar de novo ou repetir depois de uma queda de sinal
 * nunca cobra duas vezes. Pix lembra de conferir no app do banco antes de confirmar.
 */
export function dialogoReceber (c: ContaAPagar, aoConcluir: () => void): void {
  // Um envio anterior ficou sem resposta: repete o MESMO pagamento (mesmo id e valores), sem deixar mudar nada
  const incerto = pagamentoIncerto.ler(c.comandaId)
  const idDoPagamento = incerto?.id ?? novoId()
  const saldo = c.saldoCentavos
  let forma: Metodo = incerto?.metodo ?? c.metodo ?? 'DINHEIRO'

  const valor = h('input', {
    id: 'rec-valor', type: 'text', inputmode: 'decimal', autocomplete: 'off', readonly: incerto !== null,
    value: centavosParaCampo(incerto?.valorCentavos ?? saldo)
  })
  const entregue = h('input', {
    id: 'rec-entregue', type: 'text', inputmode: 'decimal', autocomplete: 'off', readonly: incerto !== null,
    value: centavosParaCampo(incerto?.valorRecebidoCentavos ?? c.trocoParaCentavos ?? saldo)
  })
  const avisoIncerto = incerto === null
    ? null
    : h('p', { class: 'alert', role: 'alert' },
      `O último pagamento (${reais(incerto.valorCentavos)}) foi enviado e a resposta não chegou. Confirme de novo: se ele já tinha sido registrado, não será cobrado duas vezes.`)
  const troco = h('p', { class: 'troco', role: 'status' })
  const avisoPix = h('p', { class: 'aviso-pix', role: 'note', hidden: true }, 'Confira no app do seu banco antes de confirmar.')
  const campoEntregue = h('div', { class: 'field', hidden: true },
    h('label', { for: 'rec-entregue' }, 'O cliente entregou R$'),
    entregue,
    troco
  )
  const erro = h('p', { class: 'field-err', id: 'rec-erro', role: 'alert', hidden: true })
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
      type: 'radio', name: 'forma', value: m.valor, checked: m.valor === forma, disabled: incerto !== null && m.valor !== forma,
      onchange: () => { forma = m.valor; ligarErro(valor, erro, null); atualizar() }
    })
    return h('label', { class: 'opcao' }, radio, h('span', {}, m.rotulo))
  }

  const fechar = abrirDialogo(`Receber de ${c.cliente}`, 'receber-titulo', [
    avisoIncerto,
    h('p', { class: 'hint' }, `${local(c)} · falta pagar ${reais(saldo)}`),
    c.metodo !== null
      ? h('p', { class: 'hint' }, `O cliente escolheu ${FORMAS.find((f) => f.valor === c.metodo)?.rotulo ?? ''}${c.trocoParaCentavos !== null ? ` e vai pagar com ${reais(c.trocoParaCentavos)}` : ''}.`)
      : null,
    h('form', {
      novalidate: true,
      onsubmit: (ev: Event) => {
        ev.preventDefault()
        let envio: PagamentoIncerto
        if (incerto !== null) {
          // O saldo mostrado pode já descontar o envio anterior: não confere de novo aqui, o servidor decide
          envio = incerto
        } else {
          const digitado = valorDigitado()
          if (digitado === null || digitado <= 0) { mostrarErro('Digite quanto o cliente pagou, por exemplo 25 ou 25,50.'); return }
          if (digitado > saldo) { mostrarErro(`O valor passa do que falta pagar (${reais(saldo)}).`); return }
          let recebido: number | null = null
          if (forma === 'DINHEIRO') {
            recebido = entregueDigitado()
            if (recebido === null || recebido < digitado) { mostrarErro('Quanto o cliente entregou? Digite um valor igual ou maior que o recebido.'); return }
          }
          envio = { id: idDoPagamento, metodo: forma, valorCentavos: digitado, valorRecebidoCentavos: recebido }
        }
        const v = envio.valorCentavos
        ligarErro(valor, erro, null)
        confirmar.disabled = true
        confirmar.textContent = 'Registrando…'
        // Guardado antes de sair: se a resposta se perder (ou o app fechar no meio), o próximo envio usa o mesmo id
        pagamentoIncerto.guardar(c.comandaId, envio)
        receberPagamento(c.comandaId, envio)
          .then((r) => {
            pagamentoIncerto.apagar(c.comandaId)
            fechar()
            const sobra = r.pagamento.trocoCentavos > 0 ? ` Troco: ${reais(r.pagamento.trocoCentavos)}.` : ''
            avisar(r.fechada ? `Conta de ${c.cliente} paga e encerrada.${sobra}` : `Recebido ${reais(v)} de ${c.cliente}. Ainda falta ${reais(r.conta.saldoCentavos)}.${sobra}`)
            aoConcluir()
          })
          .catch((e: unknown) => {
            // Recusa avaliada pelo servidor: nada foi gravado, o próximo pagamento pode ser outro. Sem resposta, sessão
            // vencida ou limite (401, 408, 429): o envio anterior pode ter sido gravado, então continua incerto e repete o id.
            if (e instanceof ApiError && e.recusaDefinitiva) pagamentoIncerto.apagar(c.comandaId)
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
    // O erro fica ligado ao campo que o motivou (valor ou, no dinheiro, quanto o cliente entregou)
    const campo = texto.includes('entregou') && forma === 'DINHEIRO' ? entregue : valor
    ligarErro(campo, erro, texto)
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
  // Texto livre: some junto com os dados do cliente aos 90 dias, mas não vale a pena escrever dado pessoal
  const erro = h('p', { class: 'field-err', id: 'forcar-erro', role: 'alert', hidden: true })
  const confirmar = h('button', { type: 'submit', class: 'btn btn-danger btn-block btn-big' }, 'Fechar sem receber')
  const fechar = abrirDialogo('Fechar sem receber', 'forcar-titulo', [
    h('p', { class: 'hint' }, `${local(c)} · ${c.cliente} · falta ${reais(c.saldoCentavos)}`),
    h('p', {}, `Vai registrar ${reais(c.saldoCentavos)} como não recebido e encerrar a conta. O motivo fica guardado.`),
    h('form', {
      novalidate: true,
      onsubmit: (ev: Event) => {
        ev.preventDefault()
        const texto = motivo.value.trim()
        if (texto.length < 3) { ligarErro(motivo, erro, 'Explique o motivo, com pelo menos 3 letras.'); motivo.focus(); return }
        ligarErro(motivo, erro, null)
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
            ligarErro(motivo, erro, e instanceof ApiError ? e.message : 'Não deu para fechar. Tente de novo.')
          })
      }
    },
    h('div', { class: 'field' }, h('label', { for: 'forcar-motivo' }, 'Motivo'), motivo,
      h('p', { class: 'hint' }, 'Não escreva dados pessoais (como nome completo ou telefone).')),
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
