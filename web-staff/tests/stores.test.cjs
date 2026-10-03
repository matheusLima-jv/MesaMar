const { test } = require('node:test')
const assert = require('node:assert/strict')
const carregar = require('./carregar.cjs')
const tick = () => new Promise(r => setImmediate(r))
function preparar(arquivo, nome) {
  const respostas = []
  const mocks = { './api': { [nome]: () => new Promise(r => respostas.push(r)), descartada: () => false, ApiError: Error }, './busca': { umaPorVez: f => f, comVariacao: n => n }, './som': { avisarPedidoNovo() {}, avisarAtraso() {}, avisarPagamento() {} }, './pagamentos-store': { agendarBusca() {} }, './toast': { avisar() {} }, './avisos-store': { limparAvisos() {}, sincronizarAvisos() {} } }
  const es = []
  class EventSource { constructor() { es.push(this) } addEventListener() {} close() {} }
  const { modulo: m } = carregar(arquivo, { EventSource, document: { addEventListener() {}, removeEventListener() {}, visibilityState: 'visible' }, window: { setTimeout: () => 1, clearTimeout() {}, setInterval: () => 1, clearInterval() {} } }, mocks)
  return { m, respostas, es }
}
test('fila descarta resposta e callback SSE da geração anterior', async () => {
  const { m, respostas, es } = preparar('fila-store','fila')
  m.iniciarFila(); m.pararFila(); m.iniciarFila()
  const atual = { agora: new Date().toISOString(), tempoAlertaMin:10, pedidos:[], marcador:'novo' }
  respostas[1](atual); await tick(); respostas[0]({ ...atual, marcador:'velho' }); await tick()
  assert.equal(m.estado().dados.marcador,'novo'); assert.equal(m.estado().conectado,false)
  es[0].onopen(); assert.equal(m.estado().conectado,false); m.pararFila()
})
test('pagamentos descarta página e sessão antigas', async () => {
  const { m, respostas } = preparar('pagamentos-store','pagamentos')
  m.iniciarPagamentos(); m.mudarPagina(1)
  const novo = { pagina:1, agora:new Date().toISOString(), aguardando:[], abertas:[], totalAguardando:0 }
  respostas[1](novo); await tick(); respostas[0]({ ...novo, pagina:0 }); await tick()
  assert.equal(m.estado().dados.pagina,1)
  m.pararPagamentos(); m.iniciarPagamentos(); m.pararPagamentos(); respostas[2](novo); await tick()
  assert.equal(m.estado().dados,null)
})
test('sessão encerrada enquanto renova CSRF não repete ação na sessão nova', async () => {
  let liberar; let chamadas=0
  const { modulo: api }=carregar('api',{ fetch: async url=>{
    chamadas++
    if(url.endsWith('/sessao')) return await new Promise(r=>{liberar=r})
    return new Response(JSON.stringify({erro:'csrf',mensagem:'Venceu'}),{status:403})
  } })
  const tentativa=api.pausar(true)
  await tick(); api.encerrarSessaoLocal(); liberar(new Response(JSON.stringify({logado:false,csrf:'novo'})))
  await assert.rejects(tentativa,e=>e.descartada); assert.equal(chamadas,2)
})

test('suspender preserva sessão offline; revogar elimina cópia e não renova validade offline', () => {
  const { modulo: api, storage } = carregar('api', { navigator: { onLine: false }, window: { setTimeout, clearTimeout, setInterval: () => 1, clearInterval() {}, addEventListener() {}, removeEventListener() {} } })
  const s = { logado: true, csrf: '', usuario: { id: '10000000-0000-4000-8000-000000000001', nome: 'Ana', papel: 'DONO' }, barraca: { codigo: 'praia', nome: 'Barraca', praia: 'ENSEADA' } }
  const em = Date.now() - 60000
  storage.set('mesamar.equipe.sessao', JSON.stringify({ versao: 1, em, dados: s }))
  api.prepararOffline(s); api.suspenderOffline()
  assert.equal(api.restaurarSessaoOffline().usuario.id, s.usuario.id)
  assert.equal(JSON.parse(storage.get('mesamar.equipe.sessao')).em, em)
  api.prepararOffline({ logado: false, csrf: '' })
  assert.equal(api.restaurarSessaoOffline(), null)
})

test('reabrir após logout sem rede mostra login e mantém revogação pendente', async () => {
  let chamadas = 0
  const { modulo: api, storage } = carregar('api', { navigator: { onLine: false }, fetch: async () => { chamadas++; throw new Error('offline') } })
  storage.set('mesamar.equipe.saida-pendente', '1')
  assert.equal((await api.sessao()).logado, false)
  assert.equal(chamadas, 0); assert.equal(storage.get('mesamar.equipe.saida-pendente'), '1')
})
