import { ApiError, cancelarItem, cancelarPedido, type Motivo, type PedidoFila } from '../api'
import { h, registrarSobreposicao } from '../dom'
import { avisar } from '../toast'

const MOTIVOS: Array<{ valor: Motivo, rotulo: string }> = [
  { valor: 'ESGOTOU', rotulo: 'Esgotou' },
  { valor: 'CLIENTE_DESISTIU', rotulo: 'Cliente desistiu' },
  { valor: 'OUTRO', rotulo: 'Outro' }
]

/**
 * Cancelar o pedido inteiro ou só um item, com motivo obrigatório (o cliente vê o motivo na página dele).
 * `aoConcluir` roda depois de o servidor aceitar, para a fila se atualizar.
 */
export function dialogoCancelar (p: PedidoFila, aoConcluir: () => void): void {
  const anterior = document.activeElement as HTMLElement | null
  const ativos = p.itens.filter((i) => !i.cancelado)

  const opcao = (nome: string, valor: string, texto: string, marcado: boolean): HTMLLabelElement => {
    const radio = h('input', { type: 'radio', name: nome, value: valor, checked: marcado })
    return h('label', { class: 'opcao' }, radio, h('span', {}, texto))
  }
  const valorDe = (nome: string): string | null =>
    (painel.querySelector<HTMLInputElement>(`input[name="${nome}"]:checked`))?.value ?? null

  const detalhe = h('input', { id: 'cancelar-detalhe', type: 'text', maxlength: 60, autocomplete: 'off' })
  const campoDetalhe = h('div', { class: 'field', hidden: true },
    h('label', { for: 'cancelar-detalhe' }, 'Detalhe ', h('span', { class: 'opt' }, '(opcional)')),
    detalhe
  )
  const erro = h('p', { class: 'field-err', role: 'alert', hidden: true })
  const confirmar = h('button', { type: 'submit', class: 'btn btn-danger btn-block btn-big' }, 'Confirmar cancelamento')

  const fechar = (): void => {
    desregistrar()
    document.removeEventListener('keydown', teclas)
    tela.remove()
    anterior?.focus()
  }
  const teclas = (ev: KeyboardEvent): void => { if (ev.key === 'Escape') fechar() }

  const form = h('form', {
    novalidate: true,
    onsubmit: (ev: Event) => {
      ev.preventDefault()
      const motivo = valorDe('motivo') as Motivo | null
      if (motivo === null) {
        erro.textContent = 'Escolha o motivo do cancelamento.'
        erro.hidden = false
        return
      }
      erro.hidden = true
      const alvo = valorDe('alvo') ?? 'pedido'
      confirmar.disabled = true
      confirmar.textContent = 'Cancelando…'
      const chamada = alvo === 'pedido'
        ? cancelarPedido(p.id, motivo, detalhe.value)
        : cancelarItem(p.id, alvo, motivo, detalhe.value)
      chamada
        .then(() => {
          fechar()
          avisar(alvo === 'pedido' ? `Pedido nº ${p.numero} cancelado.` : `Item cancelado no pedido nº ${p.numero}.`)
          aoConcluir()
        })
        .catch((e: unknown) => {
          confirmar.disabled = false
          confirmar.textContent = 'Confirmar cancelamento'
          erro.textContent = e instanceof ApiError ? e.message : 'Não deu para cancelar. Tente de novo.'
          erro.hidden = false
        })
    }
  },
  h('fieldset', { class: 'grupo' },
    h('legend', {}, 'O que cancelar?'),
    opcao('alvo', 'pedido', 'O pedido inteiro', true),
    ...(ativos.length > 1 ? ativos.map((i) => opcao('alvo', i.id, `${i.quantidade}× ${i.nome}`, false)) : [])
  ),
  h('fieldset', { class: 'grupo' },
    h('legend', {}, 'Motivo'),
    ...MOTIVOS.map((m) => opcao('motivo', m.valor, m.rotulo, false))
  ),
  campoDetalhe,
  erro,
  confirmar,
  h('button', { type: 'button', class: 'link center-block', onclick: fechar }, 'Voltar')
  )
  // "Outro" abre o campo de detalhe
  form.addEventListener('change', () => { campoDetalhe.hidden = valorDe('motivo') !== 'OUTRO' })

  const painel = h('div', { class: 'dialogo-painel' },
    h('h2', { id: 'cancelar-titulo' }, `Cancelar o pedido nº ${p.numero}`),
    h('p', { class: 'hint' }, `${p.guardaSol === null ? 'Sem guarda-sol' : `Guarda-sol ${p.guardaSol}`} · ${p.cliente}`),
    form
  )
  const tela = h('div', { class: 'dialogo', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'cancelar-titulo' }, painel)
  document.body.append(tela)
  // Trocou de aba, saiu ou a sessão acabou: o diálogo some junto (sem rodar o "ao concluir")
  const desregistrar = registrarSobreposicao(() => { document.removeEventListener('keydown', teclas); tela.remove() })
  document.addEventListener('keydown', teclas)
  painel.querySelector<HTMLInputElement>('input[name="motivo"]')?.focus()
}
