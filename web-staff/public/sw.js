// Service worker do app do quiosque.
// - Instalação: guarda a página e TODOS os arquivos desta publicação (JS, CSS, fontes, ícones), para a primeira
//   reabertura sem rede funcionar mesmo que esses arquivos tenham sido baixados antes de o service worker existir.
// - Páginas (/staff/): rede primeiro; sem rede, abre a última versão salva (o app não fica em branco).
// - /staff/assets/*: arquivos com hash no nome, servidos do cache.
// - /api/: NUNCA passa pelo cache (dados de pedidos e sessão sempre vêm do servidor).
// - Avisos de pedido (Web Push): o servidor manda o aviso cifrado, o navegador o entrega aqui, e tocar nele abre a fila.
// Um cache por publicação (a versão é gravada no build): ao ativar, apaga só as versões antigas DESTE app.
const PREFIXO = 'mesamar-staff-'
const VERSAO = 'mesamar-staff-dev'
const ARQUIVOS = /* ARQUIVOS */ []
const SHELL = ['/staff/', '/staff/manifest.json']

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(VERSAO).then((cache) => cache.addAll([...SHELL, ...ARQUIVOS])).then(() => self.skipWaiting()))
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((nomes) => Promise.all(nomes.filter((n) => n.startsWith(PREFIXO) && n !== VERSAO).map((n) => caches.delete(n))))
      .then(() => self.clients.claim())
  )
})

/** Guarda a cópia sem deixar o navegador encerrar o service worker no meio, e sem quebrar se o espaço acabou. */
function guardar (event, chave, res) {
  if (!res.ok) return res
  const copia = res.clone()
  event.waitUntil(caches.open(VERSAO).then((c) => c.put(chave, copia)).catch(() => undefined))
  return res
}

self.addEventListener('fetch', (event) => {
  const req = event.request
  const url = new URL(req.url)
  if (req.method !== 'GET' || url.origin !== self.location.origin) return
  if (url.pathname.startsWith('/api/')) return

  if (url.pathname.startsWith('/staff/assets/') || url.pathname.startsWith('/staff/fonts/') || url.pathname.startsWith('/staff/icons/')) {
    event.respondWith(caches.match(req).then((cached) => cached ?? fetch(req).then((res) => guardar(event, req, res))))
    return
  }

  if (req.mode === 'navigate' && url.pathname.startsWith('/staff/')) {
    event.respondWith(
      fetch(req)
        .then((res) => guardar(event, '/staff/', res))
        .catch(() => caches.match('/staff/').then((cached) => cached ?? Response.error()))
    )
  }
})

// Aviso de pedido novo ou atrasado. O navegador exige mostrar uma notificação para cada aviso recebido; com o app
// aberto, o som e o destaque de dentro dele já chamam a atenção, e a notificação só reforça.
self.addEventListener('push', (event) => {
  let dados = {}
  try {
    dados = event.data ? event.data.json() : {}
  } catch {
    dados = { titulo: 'MesaMar', corpo: event.data ? event.data.text() : '' }
  }
  event.waitUntil(
    self.registration.showNotification(dados.titulo || 'MesaMar', {
      body: dados.corpo || '',
      tag: dados.tag || 'mesamar',
      icon: '/staff/icons/icon-192.png',
      badge: '/staff/icons/icon-192.png',
      vibrate: dados.tipo === 'PEDIDO_ATRASADO' ? [400, 150, 400] : [200, 100, 200],
      renotify: true,
      data: { url: dados.url || '/staff/#/fila' }
    })
  )
})

// Tocar na notificação abre o destino dela (a fila, a aba Pagamentos ou o painel do dono): reaproveita a janela se já estiver aberta.
self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const alvo = new URL((event.notification.data && event.notification.data.url) || '/staff/#/fila', self.location.origin).href
  event.waitUntil((async () => {
    const janelas = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
    // Só reaproveita o app aberto quando o destino é do próprio app (/staff/); o resto (ex.: o painel do dono) abre numa janela nova
    const noApp = new URL(alvo).pathname.startsWith('/staff/')
    const aberta = noApp ? janelas.find((j) => j.url.startsWith(self.location.origin + '/staff/')) : janelas.find((j) => j.url === alvo)
    if (aberta) {
      await aberta.focus()
      try { await aberta.navigate(alvo) } catch { /* já está no app; o hash é trocado ao focar */ }
      return
    }
    await self.clients.openWindow(alvo)
  })())
})
