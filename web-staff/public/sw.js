// Service worker do app do quiosque.
// - Páginas (/staff/): rede primeiro; sem rede, abre a última versão salva (o app não fica em branco).
// - /staff/assets/*: arquivos com hash no nome, então podem ficar no cache para sempre.
// - /api/: NUNCA passa pelo cache (dados de pedidos e sessão sempre vêm do servidor).
// O modo offline completo (fila de ações para reenviar) chega na Fase 6.
const VERSAO = 'mesamar-staff-v1'
const SHELL = ['/staff/', '/staff/manifest.webmanifest', '/staff/icons/icon-192.png']

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(VERSAO).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()))
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((nomes) => Promise.all(nomes.filter((n) => n !== VERSAO).map((n) => caches.delete(n))))
      .then(() => self.clients.claim())
  )
})

self.addEventListener('fetch', (event) => {
  const req = event.request
  const url = new URL(req.url)
  if (req.method !== 'GET' || url.origin !== self.location.origin) return
  if (url.pathname.startsWith('/api/')) return

  if (url.pathname.startsWith('/staff/assets/') || url.pathname.startsWith('/staff/fonts/') || url.pathname.startsWith('/staff/icons/')) {
    event.respondWith(
      caches.match(req).then((cached) => cached ?? fetch(req).then((res) => {
        if (res.ok) { const copia = res.clone(); caches.open(VERSAO).then((c) => c.put(req, copia)) }
        return res
      }))
    )
    return
  }

  if (req.mode === 'navigate' && url.pathname.startsWith('/staff/')) {
    event.respondWith(
      fetch(req)
        .then((res) => {
          if (res.ok) { const copia = res.clone(); caches.open(VERSAO).then((c) => c.put('/staff/', copia)) }
          return res
        })
        .catch(() => caches.match('/staff/').then((cached) => cached ?? Response.error()))
    )
  }
})
