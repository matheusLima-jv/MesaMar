package br.com.mesamar.admin

import br.com.mesamar.common.SecureTokens
import br.com.mesamar.conta.ConviteRepository
import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.PainelTestSupport.Companion.csrfFrom
import br.com.mesamar.support.TestIps
import br.com.mesamar.support.assertHtmlContains
import br.com.mesamar.support.header
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@IntegrationTest
class AdminConvitesTest(
    @Value("\${local.server.port}") private val port: Int,
    private val convites: ConviteRepository,
    private val jdbc: JdbcTemplate,
) {
    private fun admin(): HttpTestClient {
        val http = HttpTestClient(port, TestIps.next(), cookies = true)
        val csrf = csrfFrom(http.get("/admin/login").body())
        http.postForm("/admin/login", mapOf("username" to "admin", "password" to "senha-de-teste", "_csrf" to csrf))
        return http
    }

    private fun gerar(
        http: HttpTestClient,
        campos: Map<String, String>,
    ): String {
        val response = http.postForm("/admin/convites", campos + ("_csrf" to csrfFrom(http.get("/admin").body())))
        assertEquals(302, response.statusCode())
        assertContains(response.header("Location"), "/admin#convites")
        return http.get("/admin").body()
    }

    private fun linkDe(html: String): String =
        assertNotNull(Regex("""id="link-convite"[^>]*value="([^"]+)"""").find(html), "link do convite não apareceu").groupValues[1]

    @Test
    fun `gerar convite exige login de admin`() {
        val http = HttpTestClient(port, TestIps.next(), cookies = true)
        val csrf = csrfFrom(http.get("/admin/login").body())
        val response = http.postForm("/admin/convites", mapOf("observacao" to "x", "_csrf" to csrf))
        assertEquals(302, response.statusCode())
        assertContains(response.header("Location"), "/admin/login")
    }

    @Test
    fun `convite para um lead mostra o link uma vez, com botao de WhatsApp, e guarda so o hash`() {
        HttpTestClient(port, TestIps.next()).postForm(
            "/lista-de-espera",
            mapOf(
                "nome" to "Rita Convite",
                "nomeBarraca" to "Quiosque da Rita",
                "praia" to "ENSEADA",
                "whatsapp" to "13 99111-4401",
                "consentimento" to "true",
                "website" to "",
            ),
        )
        val leadId = jdbc.queryForObject("SELECT id FROM lead WHERE whatsapp = '+5513991114401'", UUID::class.java)!!
        val http = admin()

        val html = gerar(http, mapOf("leadId" to leadId.toString()))

        val link = linkDe(html)
        assertTrue(link.startsWith("http://localhost:8080/painel/cadastro?convite="), link)
        assertHtmlContains(html, "Rita Convite · Quiosque da Rita", anchor = "id=\"convites\"")
        assertHtmlContains(html, "https://wa.me/5513991114401?text=", anchor = "id=\"convites\"")
        assertHtmlContains(html, "Aguardando cadastro", anchor = "id=\"convites\"")

        val token = link.substringAfter("convite=")
        val salvo = assertNotNull(convites.findByTokenHash(SecureTokens.hash(token)))
        assertEquals(leadId, salvo.leadId)
        assertTrue(
            !jdbc.queryForList("SELECT token_hash FROM convite", String::class.java).contains(token),
            "o token puro não vai para o banco",
        )

        // Recarregou a página: o link não aparece de novo.
        assertTrue(!http.get("/admin").body().contains(token))
    }

    @Test
    fun `convite avulso abre o cadastro e depois aparece como conta criada`() {
        val http = admin()
        val link = linkDe(gerar(http, mapOf("observacao" to "Dona Ana, quiosque 12")))
        val path = link.removePrefix("http://localhost:8080")

        val dono = HttpTestClient(port, TestIps.next(), cookies = true)
        val cadastro = dono.get(path)
        assertEquals(200, cadastro.statusCode())
        dono.postForm(
            "/painel/cadastro",
            mapOf(
                "convite" to path.substringAfter("convite="),
                "nome" to "Ana",
                "email" to "ana.avulsa@exemplo.com",
                "senha" to "areia quente demais",
                "_csrf" to csrfFrom(cadastro.body()),
            ),
        )

        val html = http.get("/admin").body()
        assertHtmlContains(html, "Dona Ana, quiosque 12", anchor = "id=\"convites\"")
        assertHtmlContains(html, "Conta criada", anchor = "id=\"convites\"")
        assertHtmlContains(html, "ana.avulsa@exemplo.com", anchor = "id=\"convites\"")
    }
}
