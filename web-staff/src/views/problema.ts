import { ApiError, reportarProblema } from '../api'
import { h } from '../dom'
import { estado } from '../fila-store'
import { avisar } from '../toast'

/** A última aba aberta antes de "Mais": é onde a pessoa estava quando algo deu errado. */
let ultimaTela = 'clientes'

export function lembrarTela (aba: string): void {
  if (aba !== 'mais') ultimaTela = aba
}

interface ConexaoNavegador { effectiveType?: string }

/** "online · 4g · tempo real conectado": o que ajuda a entender um problema de sinal. */
export function descreverConexao (): string {
  const rede = (navigator as Navigator & { connection?: ConexaoNavegador }).connection?.effectiveType
  return [navigator.onLine ? 'online' : 'offline', rede, estado().conectado ? 'tempo real conectado' : 'sem tempo real'].filter(Boolean).join(' · ')
}

/** O cartão "Reportar problema" da aba Mais. Uma frase basta: tela, hora e conexão vão junto. */
export function cartaoProblema (): HTMLElement {
  const texto = h('textarea', { id: 'problema-texto', rows: 3, maxlength: 500, placeholder: 'Ex.: o pedido do guarda-sol 5 não apareceu na fila' })
  const erro = h('p', { class: 'field-err', role: 'alert', hidden: true })
  const enviar = h('button', { type: 'submit', class: 'btn btn-line btn-block' }, 'Enviar aviso')
  const form = h('form', {
    novalidate: true,
    onsubmit: (ev: Event) => {
      ev.preventDefault()
      erro.hidden = true
      enviar.disabled = true
      enviar.textContent = 'Enviando…'
      reportarProblema(texto.value, ultimaTela, descreverConexao())
        .then(() => {
          texto.value = ''
          avisar('Aviso enviado. Obrigado!')
        })
        .catch((e: unknown) => {
          erro.textContent = e instanceof ApiError ? e.message : 'Não deu para enviar agora. Tente de novo quando o sinal voltar.'
          erro.hidden = false
        })
        .finally(() => {
          enviar.disabled = false
          enviar.textContent = 'Enviar aviso'
        })
    }
  },
  h('div', { class: 'field' }, h('label', { for: 'problema-texto' }, 'O que aconteceu?'), texto),
  erro,
  enviar
  )
  return h('section', { class: 'card', 'aria-labelledby': 't-problema' },
    h('h2', { id: 't-problema', class: 'card-title' }, 'Reportar problema'),
    h('p', { class: 'hint' }, 'Algo errado ou estranho? Conte em uma frase. O aviso leva junto a tela, a hora e o estado da conexão.'),
    form
  )
}
