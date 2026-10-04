import { ApiError, descartada, pedidosAguardando, type ContaAPagar } from '../api'
import { ativarModal } from '../a11y'
import { h, registrarSobreposicao } from '../dom'
import { dialogoCancelar } from './cancelar'

/** Revisão antes de receber: cancelar usa o mesmo fluxo com motivo, autoria e proteção de saldo. */
export function dialogoRevisarPedidos (conta: ContaAPagar, aoConcluir: () => void): void {
  let aberta = true
  const corpo = h('div')
  const erro = h('p', { class: 'alert', role: 'alert', hidden: true })
  const fechar = (): void => { aberta = false; desregistrar(); desativar() }
  const carregar = (): void => {
    erro.hidden = true
    corpo.replaceChildren(h('p', { class: 'loading' }, 'Carregando os pedidos…'))
    pedidosAguardando(conta.comandaId).then(pedidos => {
      if (!aberta) return
      corpo.replaceChildren(...(pedidos.length === 0
        ? [h('p', {}, 'Nenhum pedido aguarda pagamento nesta conta.')]
        : pedidos.map(p => h('section', { class: 'menu-sec' },
          h('h3', {}, `Pedido nº ${p.numero}`),
          h('ul', {}, ...p.itens.filter(i => !i.cancelado).map(i => h('li', {}, `${i.quantidade}× ${i.nome}${i.observacao === null ? '' : ` · ${i.observacao}`}`))),
          h('button', { type: 'button', class: 'btn btn-line', 'aria-label': `Cancelar pedido não pago ${p.numero}`, onclick: () => {
            dialogoCancelar(p, () => { if (aberta) carregar(); aoConcluir() })
          } }, 'Cancelar pedido ou item')
        ))))
    }).catch((e: unknown) => {
      if (!aberta || descartada(e)) return
      erro.textContent = e instanceof ApiError ? e.message : 'Não deu para carregar. Confira o sinal e tente de novo.'
      erro.hidden = false
      corpo.replaceChildren(h('button', { type: 'button', class: 'btn btn-line', onclick: carregar }, 'Tentar de novo'))
    })
  }
  const painel = h('div', { class: 'dialogo-painel' },
    h('h2', { id: 'revisar-titulo' }, `Pedidos ainda não pagos · ${conta.cliente}`),
    h('p', { class: 'hint' }, 'Confira os itens antes de receber. Cancelar exige motivo e atualiza o saldo da conta.'),
    erro, corpo, h('button', { type: 'button', class: 'btn btn-line btn-block', onclick: fechar }, 'Voltar')
  )
  const tela = h('div', { class: 'dialogo', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'revisar-titulo' }, painel)
  const desativar = ativarModal(tela, { aoEscape: fechar })
  const desregistrar = registrarSobreposicao(() => { aberta = false; desativar() })
  carregar()
}
