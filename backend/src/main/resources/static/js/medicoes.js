// Medições anônimas, habilitadas pela escolha da praia em /teste-sinal. Sem URL, token, nome ou corpo de pedido.
;(function () {
  'use strict'
  const PRAIAS = ['PITANGUEIRAS', 'ENSEADA', 'ASTURIAS', 'TOMBO', 'GUAIUBA']
  const CHAVE = 'mesamar.medicoes.v1'
  const fetchOriginal = window.fetch.bind(window)
  let enviando = false
  function ler () {
    try {
      const bruto = localStorage.getItem(CHAVE) || '[]'
      if (bruto.length > 100000) return []
      const v = JSON.parse(bruto)
      return Array.isArray(v) ? v.filter(x => x && PRAIAS.includes(x.praia) && typeof x.id === 'string' && /^[0-9a-f-]{36}$/i.test(x.id) && ['TESTE_SINAL', 'ABERTURA_FRIA', 'REABERTURA', 'PEDIDO', 'FILA', 'PAGAMENTO'].includes(x.metrica) && typeof x.sucesso === 'boolean' && [x.carregamentoMs, x.pingMs].every(n => n === null || Number.isInteger(n) && n >= 0 && n <= 120000) && (x.tipoConexao === null || typeof x.tipoConexao === 'string' && x.tipoConexao.length <= 30) && Array.isArray(x.amostras) && x.amostras.length <= 10 && x.amostras.every(a => a && (a.duracaoMs === null || Number.isInteger(a.duracaoMs) && a.duracaoMs >= 0 && a.duracaoMs <= 120000) && typeof a.sucesso === 'boolean') && x.em > Date.now() - 3 * 86400000 && x.em <= Date.now() + 300000).slice(-100) : []
    } catch { return [] }
  }
  function salvar (v) { try { localStorage.setItem(CHAVE, JSON.stringify(v)) } catch { /* quota: informa no resultado */ } }
  async function drenar () {
    if (enviando) return
    enviando = true
    try {
      for (const v of ler()) {
        let res
        try { res = await fetchOriginal('/api/teste-sinal/medicoes', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(v), signal: AbortSignal.timeout(10000) }) } catch { break }
        if (res.ok || res.status === 400) salvar(ler().filter(x => x.id !== v.id))
        else break
      }
    } finally { enviando = false }
  }
  function registrar (medicao) {
    const v = { ...medicao, id: crypto.randomUUID(), em: Date.now() }
    salvar([...ler(), v].slice(-100))
    void drenar()
    return ler().some(x => x.id === v.id)
  }
  window.mesaMarMedicoes = { registrar, drenar }
  window.addEventListener('online', () => { void drenar() })
  function praia () { try { const p = localStorage.getItem('mesamar.medicoes.praia'); return PRAIAS.includes(p) ? p : null } catch { return null } }
  window.fetch = async function (input, init) {
    const p = praia()
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const method = init?.method || (input instanceof Request ? input.method : 'GET')
    const caminho = new URL(url, location.origin).pathname
    const metrica = method === 'POST' && /\/pedidos$/.test(caminho) ? 'PEDIDO' : /\/fila$/.test(caminho) ? 'FILA' : /\/pagamentos$|\/conta(?:\/.*)?$/.test(caminho) ? 'PAGAMENTO' : null
    if (!p || !metrica) return await fetchOriginal(input, init)
    const inicio = performance.now()
    try {
      const res = await fetchOriginal(input, init)
      // Inclui leitura do corpo: receber cabeçalhos não significa receber uma confirmação completa.
      void res.clone().arrayBuffer().then(() => medir(res.ok)).catch(() => medir(false))
      return res
    } catch (e) { medir(false); throw e }
    function medir (sucesso) { registrar({ praia: p, metrica, sucesso, carregamentoMs: null, pingMs: null, tipoConexao: navigator.connection?.effectiveType || null, amostras: [{ duracaoMs: Math.min(120000, Math.round(performance.now() - inicio)), sucesso }] }) }
  }
  window.addEventListener('load', () => {
    const p = praia()
    const nav = performance.getEntriesByType('navigation')[0]
    if (p && nav) {
      const fim = nav.loadEventEnd > 0 ? nav.loadEventEnd : nav.responseEnd
      if (fim > nav.startTime) {
        registrar({ praia: p, metrica: nav.transferSize > 0 && nav.type === 'navigate' ? 'ABERTURA_FRIA' : 'REABERTURA', sucesso: true, carregamentoMs: Math.min(120000, Math.round(fim - nav.startTime)), pingMs: null, tipoConexao: navigator.connection?.effectiveType || null, amostras: [] })
      }
    }
    void drenar()
  })
})()
