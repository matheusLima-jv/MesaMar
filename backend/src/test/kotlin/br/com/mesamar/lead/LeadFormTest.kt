package br.com.mesamar.lead

import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.TestIps
import br.com.mesamar.support.header
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@IntegrationTest
class LeadFormTest(
    @Value("\${local.server.port}") private val port: Int,
    private val jdbc: JdbcTemplate,
) {
    private fun client() = HttpTestClient(port, ip = TestIps.next())

    private fun validForm(whatsapp: String) =
        mapOf(
            "nome" to "Zé da Silva",
            "nomeBarraca" to "Barraca do Zé",
            "praia" to "ENSEADA",
            "whatsapp" to whatsapp,
            "consentimento" to "true",
            "_consentimento" to "on",
            "website" to "",
        )

    private fun countByWhatsapp(e164: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM lead WHERE whatsapp = ?", Int::class.java, e164)!!

    @Test
    fun `landing mostra o formulario com labels, praias e honeypot`() {
        val html = client().get("/").body()

        assertContains(html, """<form id="lista-de-espera"""")
        assertContains(html, """action="/lista-de-espera#lista-de-espera"""")
        listOf("Seu nome", "Nome da barraca ou quiosque", "Praia", "WhatsApp com DDD").forEach {
            assertContains(html, ">$it</label>")
        }
        listOf("Enseada", "Pitangueiras", "Astúrias", "Tombo", "Pernambuco", "Guaiúba").forEach {
            assertContains(html, ">$it</option>")
        }
        assertContains(html, """name="website"""")
        assertFalse(html.contains("challenges.cloudflare.com"), "Turnstile deve ficar desligado sem chaves")
    }

    @Test
    fun `envio valido salva o lead com WhatsApp normalizado e redireciona para o obrigado`() {
        val response = client().postForm("/lista-de-espera", validForm("(13) 99111-2201"))

        assertEquals(302, response.statusCode())
        assertTrue(response.header("Location").endsWith("/lista-de-espera/obrigado"))

        val row = jdbc.queryForMap("SELECT nome, nome_barraca, praia, origem FROM lead WHERE whatsapp = '+5513991112201'")
        assertEquals("Zé da Silva", row["nome"])
        assertEquals("Barraca do Zé", row["nome_barraca"])
        assertEquals("ENSEADA", row["praia"])
        assertEquals("landing", row["origem"])
    }

    @Test
    fun `pagina de obrigado responde e nao e indexada`() {
        val response = client().get("/lista-de-espera/obrigado")

        assertEquals(200, response.statusCode())
        assertContains(response.body(), "Pronto, você está na lista.")
        assertContains(response.body(), """<meta name="robots" content="noindex">""")
    }

    @Test
    fun `mesmo WhatsApp enviado duas vezes vira um unico lead atualizado`() {
        val http = client()
        http.postForm("/lista-de-espera", validForm("13 99111-2202"))
        http.postForm("/lista-de-espera", validForm("+55 13 99111-2202") + ("nomeBarraca" to "Quiosque Novo"))

        assertEquals(1, countByWhatsapp("+5513991112202"))
        val barraca = jdbc.queryForObject("SELECT nome_barraca FROM lead WHERE whatsapp = '+5513991112202'", String::class.java)
        assertEquals("Quiosque Novo", barraca)
    }

    @Test
    fun `campos vazios voltam para o formulario com as mensagens e nada e salvo`() {
        val before = jdbc.queryForObject("SELECT count(*) FROM lead", Int::class.java)!!
        val response = client().postForm("/lista-de-espera", mapOf("nome" to "", "website" to ""))
        val html = response.body()

        assertEquals(422, response.statusCode())
        listOf(
            "Conta pra gente o seu nome.",
            "Qual é o nome da sua barraca ou quiosque?",
            "Escolha a praia onde você trabalha.",
            "Deixe um WhatsApp para a gente falar com você.",
            "Para entrar na lista, é preciso concordar com o contato pelo WhatsApp.",
        ).forEach { assertContains(html, it) }
        assertContains(html, """aria-invalid="true"""")
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM lead", Int::class.java))
    }

    @Test
    fun `WhatsApp invalido e praia fora da lista sao recusados mantendo o que foi digitado`() {
        val form = validForm("(00) 1234") + ("praia" to "COPACABANA")
        val response = client().postForm("/lista-de-espera", form)
        val html = response.body()

        assertEquals(422, response.statusCode())
        assertContains(html, "Confira o número: DDD + celular")
        assertContains(html, "Escolha uma praia da lista.")
        assertContains(html, """value="Barraca do Zé"""") // não apaga o que a pessoa já digitou
    }

    @Test
    fun `sem consentimento nao salva`() {
        val form = validForm("13 99111-2203") - "consentimento"
        val response = client().postForm("/lista-de-espera", form)

        assertEquals(422, response.statusCode())
        assertContains(response.body(), "é preciso concordar")
        assertEquals(0, countByWhatsapp("+5513991112203"))
    }

    @Test
    fun `honeypot preenchido finge sucesso mas nao salva nada`() {
        val form = validForm("13 99111-2204") + ("website" to "http://spam.example")
        val response = client().postForm("/lista-de-espera", form)

        assertEquals(302, response.statusCode())
        assertTrue(response.header("Location").endsWith("/lista-de-espera/obrigado"))
        assertEquals(0, countByWhatsapp("+5513991112204"))
    }

    @Test
    fun `mais de 8 envios em 10 minutos do mesmo IP recebem 429`() {
        val http = client()
        repeat(8) { i ->
            val status = http.postForm("/lista-de-espera", mapOf("nome" to "Teste $i")).statusCode()
            assertEquals(422, status, "envio ${i + 1} ainda deveria ser aceito para validação")
        }
        val blocked = http.postForm("/lista-de-espera", validForm("13 99111-2205"))

        assertEquals(429, blocked.statusCode())
        assertContains(blocked.body(), "Recebemos muitos envios deste aparelho.")
        assertEquals(0, countByWhatsapp("+5513991112205"))

        // Outro aparelho (outro IP) continua conseguindo enviar.
        assertEquals(302, client().postForm("/lista-de-espera", validForm("13 99111-2205")).statusCode())
    }
}
