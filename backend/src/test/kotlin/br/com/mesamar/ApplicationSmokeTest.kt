package br.com.mesamar

import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import kotlin.test.assertContains
import kotlin.test.assertEquals

@IntegrationTest
class ApplicationSmokeTest(
    @Value("\${local.server.port}") port: Int,
) {
    private val http = HttpTestClient(port)

    @Test
    fun `contexto sobe e o health check responde UP`() {
        val response = http.get("/actuator/health")

        assertEquals(200, response.statusCode())
        assertContains(response.body(), "\"status\":\"UP\"")
    }

    @Test
    fun `probes de liveness e readiness estao disponiveis`() {
        assertEquals(200, http.get("/actuator/health/liveness").statusCode())
        assertEquals(200, http.get("/actuator/health/readiness").statusCode())
    }
}
