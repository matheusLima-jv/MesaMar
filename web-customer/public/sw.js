// Service worker da página do cliente. É servido em /c/sw.js (escopo /c/), a mesma pasta do link do QR.
// - Abrir /c/{token}: rede primeiro; sem sinal, abre a última página salva (o cliente não vê tela em branco).
// - GET /api/c/{token} (cardápio e comanda): rede primeiro; sem sinal, mostra o último cardápio visto.
// - /cliente/assets, /cliente/fonts e /img: arquivos com nome estável ou hash, ficam no cache.
// - POST (pedido) NUNCA passa pelo cache: o envio tem a sua própria fila de tentativas na página.
const VERSAO = 'mesamar-cliente-v1'
const PAGINA = '/cliente/'

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(VERSAO).then((cache) => cache.add(PAGINA)).then(() => self.skipWaiting()))
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((nomes) => Promise.all(nomes.filter((n) => n !== VERSAO).map((n) => caches.delete(n))))
      .then(() => self.clients.claim())
  )
})

function guardar (req, res) {
  if (res.ok) {
    const copia = res.clone()
    caches.open(VERSAO).then((c) => c.put(req, copia))
  }
  return res
}

self.addEventListener('fetch', (event) => {
  const req = event.request
  const url = new URL(req.url)
  if (req.method !== 'GET' || url.origin !== self.location.origin) return

  if (url.pathname.startsWith('/cliente/assets/') || url.pathname.startsWith('/cliente/fonts/') || url.pathname.startsWith('/img/')) {
    event.respondWith(caches.match(req).then((cached) => cached ?? fetch(req).then((res) => guardar(req, res))))
    return
  }

  // O cardápio e a comanda: só o GET /api/c/{token}, sem subcaminho
  if (/^\/api\/c\/[^/]+$/.test(url.pathname)) {
    event.respondWith(
      fetch(req)
        .then((res) => guardar(req, res))
        .catch(() => caches.match(req).then((cached) => cached ?? Response.error()))
    )
    return
  }

  if (req.mode === 'navigate' && url.pathname.startsWith('/c/')) {
    event.respondWith(
      fetch(req)
        .then((res) => guardar(PAGINA, res))
        .catch(() => caches.match(PAGINA).then((cached) => cached ?? Response.error()))
    )
  }
})
