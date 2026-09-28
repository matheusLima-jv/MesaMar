package br.com.mesamar.sinal

import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.TestIps
import br.com.mesamar.support.header
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@IntegrationTest
class SinalControllerTest(
    @Value("\${local.server.port}") private val port: Int,
    private val jdbc: JdbcTemplate,
) {
    private fun client() = HttpTestClient(port, TestIps.next())

    @Test
    fun `pagina de teste de sinal abre, nao e indexada e carrega o script com hash`() {
        val response = client().get("/teste-sinal")
        val html = response.body()

        assertEquals(200, response.statusCode())
        assertContains(html, "Teste de sinal na praia")
        assertContains(html, """<meta name="robots" content="noindex">""")
        val script = Regex("""src="(/js/teste-sinal-[0-9a-f]{32}\.js)"""").find(html)?.groupValues?.get(1)
        assertNotNull(script, "o script deveria sair com hash no nome")
        assertEquals(200, client().get(script).statusCode())
    }

    @Test
    fun `ping responde 204 e nunca e cacheado`() {
        val response = client().get("/api/teste-sinal/ping")

        assertEquals(204, response.statusCode())
        assertContains(response.header("Cache-Control"), "no-store")
    }

    @Test
    fun `medicao valida e salva sem nenhum dado pessoal`() {
        val response =
            client().postJson(
                "/api/teste-sinal/medicoes",
                """{"praia":"PITANGUEIRAS","carregamentoMs":2300,"pingMs":180,"tipoConexao":"4g"}""",
            )

        assertEquals(201, response.statusCode())
        val id = Regex(""""id":"([0-9a-f-]{36})"""").find(response.body())?.groupValues?.get(1)
        assertNotNull(id)
        val row = jdbc.queryForMap("SELECT * FROM medicao_sinal WHERE id = ?::uuid", id)
        assertEquals("PITANGUEIRAS", row["praia"])
        assertEquals(2300, row["carregamento_ms"])
        assertEquals(180, row["ping_ms"])
        assertEquals("4g", row["tipo_conexao"])
        assertEquals(setOf("id", "praia", "carregamento_ms", "ping_ms", "tipo_conexao", "criada_em"), row.keys)
    }

    @Test
    fun `praia desconhecida ou tempo absurdo sao recusados`() {
        val http = client()
        val praiaInvalida = http.postJson("/api/teste-sinal/medicoes", """{"praia":"COPACABANA","carregamentoMs":1,"pingMs":1}""")
        val tempoNegativo = http.postJson("/api/teste-sinal/medicoes", """{"praia":"TOMBO","carregamentoMs":-5,"pingMs":1}""")
        val tempoGigante = http.postJson("/api/teste-sinal/medicoes", """{"praia":"TOMBO","carregamentoMs":999999,"pingMs":1}""")

        assertEquals(400, praiaInvalida.statusCode())
        assertEquals(400, tempoNegativo.statusCode())
        assertEquals(400, tempoGigante.statusCode())
    }

    @Test
    fun `mais de 30 medicoes em 10 minutos do mesmo IP recebem 429`() {
        val http = client()
        val body = """{"praia":"GUAIUBA","carregamentoMs":1000,"pingMs":100}"""
        repeat(30) { assertEquals(201, http.postJson("/api/teste-sinal/medicoes", body).statusCode()) }

        val blocked = http.postJson("/api/teste-sinal/medicoes", body)
        assertEquals(429, blocked.statusCode())
        assertTrue(blocked.body().contains("Muitas medições"))
    }
}
