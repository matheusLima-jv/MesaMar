package br.com.mesamar.admin

import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.TestIps
import br.com.mesamar.support.assertHtmlContains
import br.com.mesamar.support.header
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@IntegrationTest
class AdminPanelTest(
    @Value("\${local.server.port}") private val port: Int,
) {
    private fun anonymous() = HttpTestClient(port, TestIps.next())

    private fun session() = HttpTestClient(port, TestIps.next(), cookies = true)

    private fun csrfFrom(html: String): String =
        requireNotNull(Regex("""name="_csrf" value="([^"]+)"""").find(html)) { "token CSRF não encontrado" }.groupValues[1]

    private fun login(
        http: HttpTestClient,
        password: String = "senha-de-teste",
    ) = http.postForm(
        "/admin/login",
        mapOf("username" to "admin", "password" to password, "_csrf" to csrfFrom(http.get("/admin/login").body())),
    )

    private fun loggedIn(): HttpTestClient {
        val http = session()
        val response = login(http)
        assertEquals(302, response.statusCode())
        assertTrue(response.header("Location").endsWith("/admin"), "após o login deveria ir para /admin")
        return http
    }

    @Test
    fun `painel e CSVs exigem login`() {
        listOf("/admin", "/admin/leads.csv", "/admin/medicoes.csv").forEach { path ->
            val response = anonymous().get(path)
            assertEquals(302, response.statusCode(), path)
            assertContains(response.header("Location"), "/admin/login")
        }
    }

    @Test
    fun `pagina de login abre com CSRF e fora do Google`() {
        val html = anonymous().get("/admin/login").body()

        assertContains(html, """<meta name="robots" content="noindex">""")
        assertContains(html, """name="_csrf"""")
        assertContains(html, "Entrar no painel")
    }

    @Test
    fun `senha errada volta para o login com mensagem`() {
        val http = session()
        val response = login(http, password = "chute")

        assertEquals(302, response.statusCode())
        assertTrue(response.header("Location").endsWith("/admin/login?erro"))
        assertContains(http.get("/admin/login?erro").body(), "Usuário ou senha não conferem.")
    }

    @Test
    fun `painel mostra leads e o resumo do sinal por praia`() {
        anonymous().postForm(
            "/lista-de-espera",
            mapOf(
                "nome" to "Carla Painel",
                "nomeBarraca" to "Quiosque da Carla",
                "praia" to "ASTURIAS",
                "whatsapp" to "13 99111-3301",
                "consentimento" to "true",
                "website" to "",
            ),
        )
        anonymous().postJson("/api/teste-sinal/medicoes", """{"praia":"ASTURIAS","carregamentoMs":2000,"pingMs":150}""")

        val html = loggedIn().get("/admin").body()

        assertHtmlContains(html, "Quiosque da Carla", anchor = "<main")
        assertHtmlContains(html, "(13) 99111-3301", anchor = "<main")
        assertHtmlContains(html, """href="https://wa.me/5513991113301"""", anchor = "<main")
        assertHtmlContains(html, "Sinal por praia", anchor = "<main")
        assertHtmlContains(html, "Astúrias", anchor = "<main")
    }

    @Test
    fun `CSV de leads abre no Excel em portugues e neutraliza formulas`() {
        anonymous().postForm(
            "/lista-de-espera",
            mapOf(
                "nome" to "=HYPERLINK(\"http://mal.example\";\"clique\")",
                "nomeBarraca" to "Barraca; com ponto e vírgula",
                "praia" to "TOMBO",
                "whatsapp" to "13 99111-3302",
                "consentimento" to "true",
                "website" to "",
            ),
        )

        val response = loggedIn().getBytes("/admin/leads.csv")
        val body = String(response.body(), Charsets.UTF_8)

        assertEquals(200, response.statusCode())
        assertContains(response.header("Content-Type"), "text/csv")
        assertContains(response.header("Content-Disposition"), "attachment")
        assertContains(response.header("Content-Disposition"), "mesamar-leads-")
        assertTrue(body.startsWith("﻿Data;Nome;Barraca;Praia;WhatsApp\r\n"), "BOM + cabeçalho com ;")
        assertContains(body, "\"'=HYPERLINK(\"\"http://mal.example\"\";\"\"clique\"\")\"")
        assertContains(body, "\"Barraca; com ponto e vírgula\"")
        assertContains(body, "(13) 99111-3302")
    }

    @Test
    fun `CSV de medicoes traz a praia pelo nome`() {
        anonymous().postJson("/api/teste-sinal/medicoes", """{"praia":"GUAIUBA","carregamentoMs":7100,"pingMs":900,"tipoConexao":"3g"}""")

        val body = String(loggedIn().getBytes("/admin/medicoes.csv").body(), Charsets.UTF_8)

        assertTrue(body.startsWith("﻿Data;Praia;Carregamento (ms);Resposta do servidor (ms);Conexão\r\n"))
        assertContains(body, ";Guaiúba;7100;900;3g")
    }

    @Test
    fun `sair encerra a sessao`() {
        val http = loggedIn()
        val csrf = csrfFrom(http.get("/admin").body())

        val logout = http.postForm("/admin/logout", mapOf("_csrf" to csrf))
        assertEquals(302, logout.statusCode())
        assertTrue(logout.header("Location").endsWith("/admin/login?saiu"))
        assertEquals(302, http.get("/admin").statusCode())
    }

    @Test
    fun `mais de 10 tentativas de login do mesmo IP recebem 429`() {
        val http = session()
        repeat(10) { assertEquals(302, login(http, password = "chute-$it").statusCode()) }

        val blocked = login(http)
        assertEquals(429, blocked.statusCode())
        assertContains(blocked.body(), "Muitas tentativas de login")
    }
}
