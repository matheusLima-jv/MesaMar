package br.com.mesamar.landing

import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.header
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Landing com a configuração padrão (sem WhatsApp e sem analytics configurados). */
@IntegrationTest
class LandingPageTest(
    @Value("\${local.server.port}") port: Int,
) {
    private val http = HttpTestClient(port)
    private val html: String by lazy { http.get("/").body() }

    @Test
    fun `landing responde 200 em HTML`() {
        val response = http.get("/")

        assertEquals(200, response.statusCode())
        assertTrue(response.header("Content-Type").startsWith("text/html"))
    }

    @Test
    fun `landing esta em portugues e tem um unico h1 com a promessa principal`() {
        assertContains(html, """<html lang="pt-BR"""")
        assertEquals(1, Regex("<h1[ >]").findAll(html).count(), "a página deve ter exatamente um h1")
        val h1Text =
            Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)!!
                .groupValues[1]
                .replace(Regex("<[^>]+>"), "")
        assertEquals("Seu cliente pede pelo celular, você recebe na ordem certa e sabe quem já pagou.", h1Text)
    }

    @Test
    fun `landing tem as secoes combinadas no plano`() {
        listOf(
            "Como funciona",
            "Feito para a areia",
            "Piloto gratuito na temporada 2026/27",
            "Perguntas frequentes",
            "E se o cliente não tiver celular?",
            "E se a internet cair?",
            "E se o cliente for embora sem pagar?",
            "Quanto custa depois do piloto?",
            "Política de privacidade",
        ).forEach { assertContains(html, it) }
    }

    @Test
    fun `SEO basico e Open Graph usam a URL publica absoluta`() {
        assertNotNull(metaContent("description"), "meta description ausente")
        assertContains(html, """<link rel="canonical" href="http://localhost:8080/">""")
        assertEquals("http://localhost:8080/img/og-mesamar.png", metaProperty("og:image"))
        assertEquals("http://localhost:8080/", metaProperty("og:url"))
        assertEquals("pt_BR", metaProperty("og:locale"))
        assertNotNull(metaProperty("og:title"))
        assertNotNull(metaProperty("og:description"))
    }

    @Test
    fun `CSS sai com hash no nome e cache de um ano`() {
        val cssPath = Regex("""href="(/css/landing-[0-9a-f]{32}\.css)"""").find(html)?.groupValues?.get(1)
        assertNotNull(cssPath, "o link do CSS deveria estar versionado com hash")

        val css = http.get(cssPath)
        assertEquals(200, css.statusCode())
        assertContains(css.header("Cache-Control"), "max-age=31536000")
    }

    @Test
    fun `fonte propria e servida com hash, cache longo e preload`() {
        val preload = Regex("""<link rel="preload" href="(/fonts/bricolage-grotesque-latin-wght-[0-9a-f]{32}\.woff2)"""").find(html)
        assertNotNull(preload, "o preload da fonte deveria usar a URL com hash")

        val cssPath = Regex("""href="(/css/landing-[0-9a-f]{32}\.css)"""").find(html)!!.groupValues[1]
        assertContains(http.get(cssPath).body(), preload.groupValues[1].substringAfterLast('/'))

        val font = http.getBytes(preload.groupValues[1])
        assertEquals(200, font.statusCode())
        assertContains(font.header("Cache-Control"), "max-age=31536000")
        assertTrue(font.body().size > 10_000, "arquivo de fonte vazio ou truncado")
    }

    @Test
    fun `botao de WhatsApp e analytics ficam escondidos quando nao configurados`() {
        assertFalse(html.contains("wa.me"), "sem número configurado, não deve haver link de WhatsApp")
        assertFalse(html.contains("cloudflareinsights"), "sem token, não deve carregar o analytics")
    }

    @Test
    fun `pagina nao contem depoimentos nem numeros de clientes inventados`() {
        listOf("depoimento", "clientes satisfeitos", "barracas já usam").forEach {
            assertFalse(html.contains(it, ignoreCase = true), "texto proibido pelo plano: $it")
        }
    }

    @Test
    fun `politica de privacidade responde com header e footer da landing`() {
        val response = http.get("/privacidade")
        val body = response.body()

        assertEquals(200, response.statusCode())
        assertContains(body, "Lei Geral de Proteção de Dados")
        assertContains(body, "sem cookies")
        assertContains(body, """class="site-header"""")
        assertContains(body, """class="site-footer"""")
        assertContains(body, """<link rel="canonical" href="http://localhost:8080/privacidade">""")
    }

    @Test
    fun `imagens de compartilhamento e icones sao servidos`() {
        mapOf(
            "/img/og-mesamar.png" to "image/png",
            "/img/apple-touch-icon.png" to "image/png",
            "/img/favicon.svg" to "image/svg+xml",
        ).forEach { (path, type) ->
            val response = http.getBytes(path)
            assertEquals(200, response.statusCode(), "GET $path")
            assertTrue(response.header("Content-Type").startsWith(type), "$path deveria ser $type")
            assertTrue(response.body().isNotEmpty())
        }
    }

    private fun metaContent(name: String): String? = Regex("""<meta name="$name" content="([^"]*)"""").find(html)?.groupValues?.get(1)

    private fun metaProperty(property: String): String? =
        Regex("""<meta property="$property" content="([^"]*)"""").find(html)?.groupValues?.get(1)
}
