package br.com.mesamar.guardasol

import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.assertHtmlContains
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@IntegrationTest
class GuardaSolTest(
    @Value("\${local.server.port}") port: Int,
    convites: ConviteService,
    private val usuarios: UsuarioRepository,
    private val repository: GuardaSolRepository,
) {
    private val painel = PainelTestSupport(port, convites)

    private fun barracaId(email: String): UUID = assertNotNull(usuarios.findByEmailIgnoreCase(email)?.barracaId)

    private fun lote(
        http: HttpTestClient,
        de: Int,
        ate: Int,
    ) = painel.post(http, "/painel/guarda-sois", "/painel/guarda-sois", mapOf("de" to "$de", "ate" to "$ate"))

    private fun numerosAtivos(email: String) =
        repository
            .findAllByBarracaIdOrderByNumero(barracaId(email))
            .filter {
                it.ativo
            }.map { it.numero }

    private fun regras(
        http: HttpTestClient,
        limite: Int,
    ) = painel.post(
        http,
        "/painel/regras",
        "/painel/regras",
        mapOf("limiteGuardaSois" to "$limite", "modoPagamento" to "FINAL", "limiteSaldo" to "", "tempoAlertaMin" to "10"),
    )

    @Test
    fun `cria do 1 ao 20 e sugere continuar de onde parou`() {
        val email = painel.novoEmail()
        val http = painel.donoComBarraca(email = email)
        assertTrue(http.get("/painel/guarda-sois").body().contains("value=\"30\""), "sugere 1 a 30 com o limite padrão")

        val response = lote(http, 1, 20)

        assertEquals(302, response.statusCode(), response.body())
        assertEquals((1..20).toList(), numerosAtivos(email))
        val pagina = http.get("/painel/guarda-sois").body()
        assertHtmlContains(pagina, "Pronto: 20 criados.", anchor = "<main")
        assertTrue(pagina.contains("value=\"21\""), "a próxima faixa começa no 21")
        assertHtmlContains(http.get("/painel").body(), "20 ativos, de até 30", anchor = "<main")
    }

    @Test
    fun `nao passa do limite e explica quanto ainda cabe`() {
        val email = painel.novoEmail()
        val http = painel.donoComBarraca(email = email)
        lote(http, 1, 25)

        val response = lote(http, 26, 40)

        assertEquals(422, response.statusCode())
        assertHtmlContains(response.body(), "O seu limite é de 30 guarda-sóis ativos e cabem só mais 5.")
        assertEquals(25, numerosAtivos(email).size)
    }

    @Test
    fun `faixa repetida nao duplica e reativa os desativados`() {
        val email = painel.novoEmail()
        val http = painel.donoComBarraca(email = email)
        lote(http, 1, 10)
        val cinco = repository.findAllByBarracaIdOrderByNumero(barracaId(email)).first { it.numero == 5 }
        painel.post(http, "/painel/guarda-sois", "/painel/guarda-sois/${cinco.id}/alternar", emptyMap())
        assertTrue(5 !in numerosAtivos(email))

        lote(http, 1, 12)

        assertEquals((1..12).toList(), numerosAtivos(email))
        assertEquals(12, repository.findAllByBarracaIdOrderByNumero(barracaId(email)).size)
        assertHtmlContains(http.get("/painel/guarda-sois").body(), "Pronto: 2 criados, 1 reativado, 9 já existiam.", anchor = "<main")
    }

    @Test
    fun `faixa invalida`() {
        val http = painel.donoComBarraca()
        val invertida = lote(http, 20, 10)
        assertEquals(422, invertida.statusCode())
        assertHtmlContains(invertida.body(), "O último número precisa ser maior ou igual ao primeiro.")

        val foraDaFaixa = lote(http, 0, 1000)
        assertHtmlContains(foraDaFaixa.body(), "Número de 1 a 999.")
    }

    @Test
    fun `religar respeita o limite e o limite nao desce abaixo dos ativos`() {
        val email = painel.novoEmail()
        val http = painel.donoComBarraca(email = email)
        assertEquals(302, regras(http, 3).statusCode())
        lote(http, 1, 3)
        val um = repository.findAllByBarracaIdOrderByNumero(barracaId(email)).first { it.numero == 1 }
        painel.post(http, "/painel/guarda-sois", "/painel/guarda-sois/${um.id}/alternar", emptyMap())
        lote(http, 4, 4)

        painel.post(http, "/painel/guarda-sois", "/painel/guarda-sois/${um.id}/alternar", emptyMap())
        assertHtmlContains(http.get("/painel/guarda-sois").body(), "Limite de 3 guarda-sóis ativos atingido.", anchor = "<main")
        assertEquals(listOf(2, 3, 4), numerosAtivos(email))

        val baixar = regras(http, 2)
        assertEquals(422, baixar.statusCode())
        assertHtmlContains(baixar.body(), "Você tem 3 guarda-sóis ativos.")
    }

    @Test
    fun `dono de outra barraca nao mexe nos guarda-sois alheios`() {
        val a = painel.novoEmail()
        lote(painel.donoComBarraca(email = a), 1, 3)
        val alvo = repository.findAllByBarracaIdOrderByNumero(barracaId(a)).first()

        val intruso = painel.donoComBarraca()
        painel.post(intruso, "/painel/guarda-sois", "/painel/guarda-sois/${alvo.id}/alternar", emptyMap())

        assertTrue(repository.findById(alvo.id).orElseThrow().ativo)
        assertTrue(!intruso.get("/painel/guarda-sois").body().contains("umbrella-grid"), "o intruso não vê a lista de A")
    }
}
