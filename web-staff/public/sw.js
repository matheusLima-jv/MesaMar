// Service worker do app do quiosque.
// - Páginas (/staff/): rede primeiro; sem rede, abre a última versão salva (o app não fica em branco).
// - /staff/assets/*: arquivos com hash no nome, então podem ficar no cache para sempre.
// - /api/: NUNCA passa pelo cache (dados de pedidos e sessão sempre vêm do servidor).
// - Avisos de pedido (Web Push): o servidor manda o aviso cifrado, o navegador o entrega aqui, e tocar nele abre a fila.
// O modo offline completo (fila de ações para reenviar) chega na Fase 6.
const VERSAO = 'mesamar-staff-v1'
const SHELL = ['/staff/', '/staff/manifest.json', '/staff/icons/icon-192.png']

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

// Tocar na notificação abre a fila: reaproveita o app se já estiver aberto.
self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const alvo = new URL((event.notification.data && event.notification.data.url) || '/staff/#/fila', self.location.origin).href
  event.waitUntil((async () => {
    const janelas = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
    const aberta = janelas.find((j) => j.url.startsWith(self.location.origin + '/staff/'))
    if (aberta) {
      await aberta.focus()
      try { await aberta.navigate(alvo) } catch { /* já está no app; o hash é trocado ao focar */ }
      return
    }
    await self.clients.openWindow(alvo)
  })())
})
