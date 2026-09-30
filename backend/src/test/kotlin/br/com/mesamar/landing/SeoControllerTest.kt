package br.com.mesamar.landing

import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.header
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@IntegrationTest
class SeoControllerTest(
    @Value("\${local.server.port}") port: Int,
) {
    private val http = HttpTestClient(port)

    @Test
    fun `robots libera a landing, bloqueia os apps e aponta o sitemap`() {
        val response = http.get("/robots.txt")
        val body = response.body()

        assertEquals(200, response.statusCode())
        assertTrue(response.header("Content-Type").startsWith("text/plain"))
        assertContains(body, "Allow: /")
        listOf("/staff/", "/cliente/", "/actuator/").forEach { assertContains(body, "Disallow: $it") }
        assertContains(body, "Sitemap: http://localhost:8080/sitemap.xml")
    }

    @Test
    fun `sitemap e um XML valido com as paginas publicas em URL absoluta`() {
        val response = http.get("/sitemap.xml")
        assertEquals(200, response.statusCode())
        assertTrue(response.header("Content-Type").startsWith("application/xml"))

        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(response.body().byteInputStream())
        val locs =
            document
                .getElementsByTagNameNS("http://www.sitemaps.org/schemas/sitemap/0.9", "loc")
                .let { nodes -> (0 until nodes.length).map { (nodes.item(it) as Element).textContent } }

        assertEquals(listOf("http://localhost:8080/", "http://localhost:8080/privacidade"), locs)
    }
}
