(function () {
  'use strict'
  const $ = id => document.getElementById(id)
  const botao = $('medir'), praia = $('praia'), veredito = $('veredito')
  let primeira = true
  const formatar = ms => ms === null ? 'não disponível' : ms >= 1000 ? (ms / 1000).toFixed(1).replace('.', ',') + ' s' : ms + ' ms'
  async function medir () {
    if (!praia.value) { praia.focus(); veredito.textContent = 'Escolha a praia antes de medir.'; return }
    try { localStorage.setItem('mesamar.medicoes.praia', praia.value) } catch { /* sem armazenamento */ }
    botao.disabled = true; botao.textContent = 'Medindo…'
    let carregamentoMs = null
    const amostras = []
    try {
      if (primeira) {
        const nav = performance.getEntriesByType('navigation')[0]
        const fim = nav && (nav.loadEventEnd > 0 ? nav.loadEventEnd : nav.responseEnd)
        if (nav && fim > nav.startTime) carregamentoMs = Math.min(120000, Math.round(fim - nav.startTime))
      } else {
        const inicio = performance.now()
        try { const r = await fetch('/teste-sinal?medicao=' + crypto.randomUUID(), { cache: 'no-store', signal: AbortSignal.timeout(15000) }); if (r.ok) { await r.arrayBuffer(); carregamentoMs = Math.min(120000, Math.round(performance.now() - inicio)) } } catch { /* ausência permanece null */ }
      }
      primeira = false
      for (let i = 0; i < 5; i++) {
        const inicio = performance.now()
        let sucesso = false
        try { const r = await fetch('/api/teste-sinal/ping?t=' + crypto.randomUUID(), { cache: 'no-store', signal: AbortSignal.timeout(10000) }); sucesso = r.ok } catch { /* falha entra na amostra */ }
        amostras.push({ duracaoMs: Math.min(120000, Math.round(performance.now() - inicio)), sucesso })
      }
      const tempos = amostras.filter(a => a.sucesso).map(a => a.duracaoMs).sort((a, b) => a - b)
      const pingMs = tempos.length > 0 ? tempos[Math.floor(tempos.length / 2)] : null
      const sucesso = carregamentoMs !== null && amostras.every(a => a.sucesso)
      $('r-carregamento').textContent = formatar(carregamentoMs)
      $('r-ping').textContent = `${formatar(pingMs)} · ${amostras.filter(a => !a.sucesso).length}/5 falhas`
      $('r-conexao').textContent = navigator.connection?.effectiveType?.toUpperCase() || 'não informado pelo navegador'
      $('resultado').hidden = false
      const preservada = window.mesaMarMedicoes?.registrar({ praia: praia.value, carregamentoMs, pingMs, tipoConexao: navigator.connection?.effectiveType || null, metrica: 'TESTE_SINAL', sucesso, amostras })
      veredito.textContent = `${sucesso && carregamentoMs <= 5000 ? '✅ Bom para o piloto.' : '⚠️ Medição com lentidão ou falhas.'} ${preservada ? 'Amostra guardada neste aparelho para envio, incluindo falhas.' : 'Sem armazenamento local: confira a conexão antes de fechar.'}`
      veredito.dataset.ok = String(sucesso && carregamentoMs <= 5000)
    } finally { botao.disabled = false; botao.textContent = 'Medir de novo' }
  }
  botao.addEventListener('click', () => { void medir() })
})()
