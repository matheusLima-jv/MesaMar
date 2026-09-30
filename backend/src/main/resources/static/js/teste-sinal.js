// Teste de sinal: mede a abertura da página e o tempo de resposta do servidor,
// e envia o resultado (anônimo) para /api/teste-sinal/medicoes.
(function () {
  'use strict'

  const PINGS = 5
  const LIMITE_BOM_MS = 5000 // meta do Portão A: página abrindo em até 5 s

  const $ = (id) => document.getElementById(id)
  const botao = $('medir')
  const praia = $('praia')
  const veredito = $('veredito')

  function tempoDeCarregamento () {
    const nav = performance.getEntriesByType('navigation')[0]
    if (!nav) return null
    const fim = nav.loadEventEnd > 0 ? nav.loadEventEnd : nav.responseEnd
    return Math.round(fim - nav.startTime)
  }

  function mediana (valores) {
    const ordenado = [...valores].sort((a, b) => a - b)
    return ordenado[Math.floor(ordenado.length / 2)]
  }

  async function medirPing () {
    const tempos = []
    for (let i = 0; i < PINGS; i++) {
      const inicio = performance.now()
      const resposta = await fetch('/api/teste-sinal/ping?t=' + Date.now() + i, { cache: 'no-store' })
      if (!resposta.ok) throw new Error('ping falhou: ' + resposta.status)
      tempos.push(performance.now() - inicio)
    }
    return Math.round(mediana(tempos))
  }

  function formatarMs (ms) {
    return ms >= 1000 ? (ms / 1000).toFixed(1).replace('.', ',') + ' s' : ms + ' ms'
  }

  function tipoConexao () {
    const c = navigator.connection
    return c && c.effectiveType ? String(c.effectiveType).slice(0, 20) : null
  }

  async function medir () {
    if (!praia.value) {
      praia.focus()
      veredito.textContent = 'Escolha a praia antes de medir.'
      return
    }
    botao.disabled = true
    botao.textContent = 'Medindo…'
    veredito.textContent = ''

    try {
      const carregamentoMs = tempoDeCarregamento() ?? 0
      const pingMs = await medirPing()
      const conexao = tipoConexao()

      $('r-carregamento').textContent = formatarMs(carregamentoMs)
      $('r-ping').textContent = formatarMs(pingMs)
      $('r-conexao').textContent = conexao ? conexao.toUpperCase() : 'não informado pelo navegador'
      $('resultado').hidden = false

      const resposta = await fetch('/api/teste-sinal/medicoes', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ praia: praia.value, carregamentoMs, pingMs, tipoConexao: conexao })
      })
      if (!resposta.ok) throw new Error('envio falhou: ' + resposta.status)

      veredito.textContent = carregamentoMs <= LIMITE_BOM_MS
        ? '✅ Bom para o piloto. Medição salva.'
        : '⚠️ Lento: acima de 5 s. Medição salva. Vale priorizar o modo sem internet.'
      veredito.dataset.ok = String(carregamentoMs <= LIMITE_BOM_MS)
    } catch (erro) {
      veredito.textContent = '❌ Não deu para completar a medição (sem sinal ou servidor fora). Isso também é um resultado: anote a praia e o horário.'
      veredito.dataset.ok = 'false'
    } finally {
      botao.disabled = false
      botao.textContent = 'Medir de novo'
    }
  }

  botao.addEventListener('click', medir)
})()
