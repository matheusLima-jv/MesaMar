import { ApiError, pausar } from '../api'
import { ativarModal, ligarErro } from '../a11y'
import { h, registrarSobreposicao } from '../dom'
import { avisar } from '../toast'

/**
 * "Pausar pedidos": o quiosque para de receber pedidos pelo celular dos clientes (no pico, ou quando o estoque
 * acaba). O recado é opcional e aparece para os clientes, que são avisados na hora.
 */
export function dialogoPausar (aoConcluir: () => void): void {
  const recado = h('input', { id: 'pausa-recado', type: 'text', maxlength: 80, autocomplete: 'off', placeholder: 'ex.: Voltamos em 10 minutos' })
  const erro = h('p', { class: 'field-err', id: 'pausa-erro', role: 'alert', hidden: true })
  const confirmar = h('button', { type: 'submit', class: 'btn btn-danger btn-block btn-big' }, 'Pausar pedidos')

  const fechar = (): void => {
    desregistrar()
    desativar()
  }

  const form = h('form', {
    novalidate: true,
    onsubmit: (ev: Event) => {
      ev.preventDefault()
      ligarErro(recado, erro, null)
      confirmar.disabled = true
      confirmar.textContent = 'Pausando…'
      pausar(true, recado.value)
        .then(() => {
          fechar()
          avisar('Pedidos pausados. Os clientes já foram avisados.')
          aoConcluir()
        })
        .catch((e: unknown) => {
          confirmar.disabled = false
          confirmar.textContent = 'Pausar pedidos'
          ligarErro(recado, erro, e instanceof ApiError ? e.message : 'Não deu para pausar. Tente de novo.')
        })
    }
  },
  h('p', {}, 'Os clientes não conseguem pedir enquanto estiver pausado. Quem já pediu continua na fila.'),
  h('div', { class: 'field' },
    h('label', { for: 'pausa-recado' }, 'Recado para os clientes ', h('span', { class: 'opt' }, '(opcional)')),
    recado
  ),
  erro,
  confirmar,
  h('button', { type: 'button', class: 'link center-block', onclick: fechar }, 'Voltar')
  )

  const tela = h('div', { class: 'dialogo', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'pausa-titulo' },
    h('div', { class: 'dialogo-painel' }, h('h2', { id: 'pausa-titulo' }, 'Pausar pedidos'), form)
  )
  // Trocou de aba, saiu ou a sessão acabou: o diálogo some junto (sem rodar o "ao concluir")
  const desativar = ativarModal(tela, { aoEscape: fechar, foco: () => recado })
  const desregistrar = registrarSobreposicao(desativar)
}
