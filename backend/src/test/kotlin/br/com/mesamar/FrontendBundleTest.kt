package br.com.mesamar

import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Value
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Garante que o build dos dois fronts foi gerado, empacotado no JAR
 * e é servido pelo Spring nas rotas combinadas.
 */
@IntegrationTest
class FrontendBundleTest(
    @Value("\${local.server.port}") port: Int,
) {
    private val http = HttpTestClient(port)

    @ParameterizedTest(name = "{0} serve o index.html do {1}")
    @CsvSource("/staff/, web-staff", "/cliente/, web-customer")
    fun `rota do front serve o index gerado pelo build`(
        route: String,
        appName: String,
    ) {
        val response = http.get(route)

        assertEquals(200, response.statusCode(), "GET $route")
        assertContains(response.body(), "data-app=\"$appName\"")
    }

    @ParameterizedTest(name = "{0} referencia um JS que existe")
    @CsvSource("/staff/", "/cliente/")
    fun `index referencia um bundle JS que tambem e servido`(route: String) {
        val html = http.get(route).body()
        val scripts =
            Regex("""<script[^>]+src="([^"]+\.js)"""")
                .findAll(html)
                .map { it.groupValues[1] }
                .toList()

        assertTrue(scripts.isNotEmpty(), "Nenhum <script src> encontrado em $route")
        scripts.forEach { src ->
            assertTrue(src.startsWith(route), "Script $src deveria estar sob $route (base do Vite)")
            assertEquals(200, http.get(src).statusCode(), "GET $src")
        }
    }

    @ParameterizedTest(name = "{0} sem barra redireciona")
    @CsvSource("/staff", "/cliente")
    fun `rota sem barra final redireciona para a versao com barra`(route: String) {
        val response = http.get(route)

        assertEquals(302, response.statusCode())
        val location = response.headers().firstValue("Location").orElse("")
        assertTrue(location.endsWith("$route/"), "Location inesperado: $location")
    }
}
