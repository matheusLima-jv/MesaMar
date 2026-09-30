package br.com.mesamar.admin

import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.TestIps
import br.com.mesamar.support.header
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sem MESAMAR_ADMIN_PASSWORD, o painel fica trancado: nenhuma senha entra. */
@IntegrationTest
@TestPropertySource(properties = ["mesamar.admin.password="])
class AdminLockedTest(
    @Value("\${local.server.port}") private val port: Int,
) {
    @Test
    fun `sem senha configurada nenhum login funciona`() {
        val http = HttpTestClient(port, TestIps.next(), cookies = true)
        val csrf = Regex("""name="_csrf" value="([^"]+)"""").find(http.get("/admin/login").body())!!.groupValues[1]

        listOf("", "admin", "senha-de-teste").forEach { password ->
            val response = http.postForm("/admin/login", mapOf("username" to "admin", "password" to password, "_csrf" to csrf))
            assertTrue(response.header("Location").endsWith("/admin/login?erro"), "senha '$password' não deveria entrar")
        }
        assertEquals(302, http.get("/admin").statusCode())
    }
}
