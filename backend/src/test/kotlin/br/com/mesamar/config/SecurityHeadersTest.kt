package br.com.mesamar.config

import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.TestIps
import br.com.mesamar.support.header
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@IntegrationTest
class SecurityHeadersTest(
    @Value("\${local.server.port}") private val port: Int,
) {
    private val http = HttpTestClient(port, TestIps.next())

    @Test
    fun `paginas publicas saem com os cabecalhos de seguranca`() {
        val response = http.get("/")

        assertContains(response.header("Content-Security-Policy"), "default-src 'self'")
        assertContains(response.header("Content-Security-Policy"), "frame-ancestors 'none'")
        assertEquals("nosniff", response.header("X-Content-Type-Options"))
        assertEquals("DENY", response.header("X-Frame-Options"))
        assertEquals("strict-origin-when-cross-origin", response.header("Referrer-Policy"))
    }

    @Test
    fun `landing publica nao cria cookie nenhum (promessa da politica de privacidade)`() {
        listOf("/", "/privacidade", "/teste-sinal", "/lista-de-espera/obrigado").forEach { path ->
            val response = http.get(path)
            assertEquals(200, response.statusCode(), path)
            assertTrue(response.headers().allValues("Set-Cookie").isEmpty(), "$path não deveria criar cookie")
        }
        val post = http.postForm("/lista-de-espera", mapOf("nome" to "", "website" to ""))
        assertTrue(post.headers().allValues("Set-Cookie").isEmpty(), "o envio do formulário não deveria criar cookie")
    }

    @Test
    fun `recursos estaticos continuam com cache longo mesmo com o Spring Security`() {
        val html = http.get("/").body()
        val cssPath = Regex("""href="(/css/landing-[0-9a-f]{32}\.css)"""").find(html)!!.groupValues[1]

        assertContains(http.get(cssPath).header("Cache-Control"), "max-age=31536000")
    }
}
