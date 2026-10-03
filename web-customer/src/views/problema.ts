import { ligarErro } from '../a11y'
import { ApiError, reportarProblema } from '../api'
import { h } from '../dom'
import { casca, type Contexto } from './casca'

interface ConexaoNavegador { effectiveType?: string }

function conexao (tempoReal: boolean): string {
  const rede = (navigator as Navigator & { connection?: ConexaoNavegador }).connection?.effectiveType
  return [navigator.onLine ? 'online' : 'offline', rede, tempoReal ? 'tempo real conectado' : 'sem tempo real'].filter(Boolean).join(' · ')
}

/** "Reportar problema": uma frase e pronto. A tela de onde a pessoa veio e a conexão vão junto. */
export function telaProblema (ctx: Contexto, token: string, telaAnterior: string, tempoReal: boolean): Node[] {
  const texto = h('textarea', { id: 'problema-texto', rows: 4, maxlength: 500, placeholder: 'Conte o que aconteceu' })
  const erro = h('p', { class: 'alert', role: 'alert', id: 'problema-erro', hidden: true })
  const ok = h('p', { class: 'info', role: 'status', hidden: true }, 'Aviso enviado. Obrigado! Se for urgente, chame um atendente.')
  const enviar = h('button', { type: 'submit', class: 'btn btn-sun btn-big btn-block' }, 'Enviar aviso')
  const form = h('form', {
    novalidate: true,
    onsubmit: (ev: Event) => {
      ev.preventDefault()
      ligarErro(texto, erro, null)
      ok.hidden = true
      enviar.disabled = true
      enviar.textContent = 'Enviando…'
      reportarProblema(token, texto.value, telaAnterior, conexao(tempoReal))
        .then(() => { texto.value = ''; ok.hidden = false })
        .catch((e: unknown) => {
          ligarErro(texto, erro, e instanceof ApiError ? e.message : 'Não deu para enviar agora. Tente de novo quando o sinal voltar.')
        })
        .finally(() => { enviar.disabled = false; enviar.textContent = 'Enviar aviso' })
    }
  },
  h('div', { class: 'field' }, h('label', { for: 'problema-texto' }, 'O que aconteceu?'), texto,
    h('p', { class: 'hint' }, 'Não escreva nomes, telefones ou outros dados pessoais: o aviso fica guardado por 90 dias.')),
  erro,
  ok,
  enviar
  )
  return casca(
    ctx,
    null,
    h('h2', { class: 'screen-title' }, 'Reportar um problema'),
    h('p', { class: 'hint' }, 'Algo não funcionou? Conte em uma frase. Não precisa dizer o seu nome.'),
    form,
    h('a', { href: '#', class: 'btn btn-line btn-block btn-gap' }, 'Voltar')
  )
}
