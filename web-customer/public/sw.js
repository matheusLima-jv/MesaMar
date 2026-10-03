// Service worker da página do cliente. É servido em /c/sw.js (escopo /c/), a mesma pasta do link do QR.
// - Instalação: guarda a página e TODOS os arquivos desta publicação (JS, CSS, fontes), para a primeira reabertura
//   sem sinal funcionar mesmo que esses arquivos tenham sido baixados antes de o service worker existir.
// - Abrir /c/{token}: rede primeiro; sem sinal, abre a última página salva (o cliente não vê tela em branco).
// - GET /api/c/{token} (cardápio e comanda): rede primeiro; sem sinal, a última cópia, marcada com a hora em que foi
//   salva (cabeçalho X-MesaMar-Salvo-Em): a página avisa que o cardápio pode estar desatualizado.
// - POST (pedido) NUNCA passa pelo cache: o envio tem a sua própria fila de tentativas na página.
// Um cache por publicação (a versão é gravada no build): ao ativar, apaga só as versões antigas DESTE app.
const PREFIXO = 'mesamar-cliente-'
const VERSAO = 'mesamar-cliente-dev'
const ARQUIVOS = /* ARQUIVOS */ []
const PAGINA = '/cliente/'
/** Comandas guardadas para abrir sem sinal: só as mais recentes (o aparelho pode ter lido QRs de outros dias). */
const MAX_COMANDAS = 3

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(VERSAO)
      .then((cache) => cache.addAll([PAGINA, ...ARQUIVOS]))
      .then(() => self.skipWaiting())
  )
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((nomes) => Promise.all(nomes.filter((n) => n.startsWith(PREFIXO) && n !== VERSAO).map((n) => caches.delete(n))))
      .then(() => self.clients.claim())
  )
})

/** Guarda a cópia sem deixar o navegador encerrar o service worker no meio, e sem quebrar se o espaço acabou. */
function guardar (event, chave, res, marcar) {
  if (!res.ok) return res
  const copia = res.clone()
  event.waitUntil((async () => {
    try {
      const cache = await caches.open(VERSAO)
      let resposta = copia
      if (marcar) {
        const cabecalhos = new Headers(copia.headers)
        cabecalhos.set('X-MesaMar-Salvo-Em', String(Date.now()))
        resposta = new Response(await copia.blob(), { status: copia.status, statusText: copia.statusText, headers: cabecalhos })
      }
      await cache.put(chave, resposta)
      if (marcar) await limitarComandas(cache)
    } catch {
      // Sem espaço (quota) ou cache indisponível: a página segue funcionando com a rede
    }
  })())
  return res
}

async function limitarComandas (cache) {
  const chaves = (await cache.keys()).filter((r) => /\/api\/c\/[^/]+$/.test(new URL(r.url).pathname))
  // keys() vem na ordem de inserção, e o put substitui: as primeiras são as mais antigas
  await Promise.all(chaves.slice(0, Math.max(0, chaves.length - MAX_COMANDAS)).map((r) => cache.delete(r)))
}

self.addEventListener('fetch', (event) => {
  const req = event.request
  const url = new URL(req.url)
  if (req.method !== 'GET' || url.origin !== self.location.origin) return

  if (url.pathname.startsWith('/cliente/assets/') || url.pathname.startsWith('/cliente/fonts/') || url.pathname.startsWith('/img/')) {
    event.respondWith(caches.match(req).then((cached) => cached ?? fetch(req).then((res) => guardar(event, req, res, false))))
    return
  }

  // O cardápio e a comanda: só o GET /api/c/{token}, sem subcaminho
  if (/^\/api\/c\/[^/]+$/.test(url.pathname)) {
    event.respondWith(
      fetch(req)
        .then((res) => guardar(event, req, res, true))
        .catch(() => caches.match(req).then((cached) => cached ?? Response.error()))
    )
    return
  }

  if (req.mode === 'navigate' && url.pathname.startsWith('/c/')) {
    event.respondWith(
      fetch(req)
        .then((res) => guardar(event, PAGINA, res, false))
        .catch(() => caches.match(PAGINA).then((cached) => cached ?? Response.error()))
    )
  }
})
