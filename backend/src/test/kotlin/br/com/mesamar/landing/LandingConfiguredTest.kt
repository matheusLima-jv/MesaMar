package br.com.mesamar.landing

import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** Landing com WhatsApp, e-mail, analytics e domínio de produção configurados. */
@IntegrationTest
@TestPropertySource(
    properties = [
        "mesamar.site.public-base-url=https://mesamar.exemplo.com.br",
        "mesamar.site.whatsapp-number=5513999998888",
        "mesamar.site.contact-email=privacidade@exemplo.com.br",
        "mesamar.site.cloudflare-analytics-token=token-de-teste",
    ],
)
class LandingConfiguredTest(
    @Value("\${local.server.port}") port: Int,
) {
    private val http = HttpTestClient(port)

    @Test
    fun `botoes de WhatsApp aparecem com mensagem pre-preenchida`() {
        val html = http.get("/").body()
        val links = Regex("""href="(https://wa\.me/[^"]+)"""").findAll(html).map { it.groupValues[1] }.toList()

        assertEquals(2, links.size, "esperado: botão do topo e botão do piloto")
        links.forEach {
            assertContains(it, "https://wa.me/5513999998888?text=Ol%C3%A1%21%20Vi%20o%20site%20do%20MesaMar")
        }
    }

    @Test
    fun `canonical, Open Graph e sitemap usam o dominio de producao`() {
        val html = http.get("/").body()

        assertContains(html, """<link rel="canonical" href="https://mesamar.exemplo.com.br/">""")
        assertContains(html, """content="https://mesamar.exemplo.com.br/img/og-mesamar.png"""")
        assertContains(http.get("/sitemap.xml").body(), "<loc>https://mesamar.exemplo.com.br/privacidade</loc>")
        assertContains(http.get("/robots.txt").body(), "Sitemap: https://mesamar.exemplo.com.br/sitemap.xml")
    }

    @Test
    fun `analytics do Cloudflare entra com o token configurado`() {
        val html = http.get("/").body()

        assertContains(html, "https://static.cloudflareinsights.com/beacon.min.js")
        assertContains(html, "token-de-teste")
    }

    @Test
    fun `privacidade mostra os canais de contato configurados`() {
        val html = http.get("/privacidade").body()

        assertContains(html, "mailto:privacidade@exemplo.com.br")
        assertContains(html, "https://wa.me/5513999998888")
        assertContains(html, "token-de-teste") // analytics também na página de privacidade
    }
}
