package br.com.mesamar.equipe

import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.assertHtmlContains
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@IntegrationTest
class EquipeTest(
    @Value("\${local.server.port}") port: Int,
    convites: ConviteService,
    private val usuarios: UsuarioRepository,
    private val atendentes: AtendenteRepository,
    private val service: AtendenteService,
) {
    private val painel = PainelTestSupport(port, convites)

    private fun barracaId(email: String): UUID = assertNotNull(usuarios.findByEmailIgnoreCase(email)?.barracaId)

    private fun adicionar(
        http: HttpTestClient,
        nome: String,
        pin: String = "2580",
    ) = painel.post(http, "/painel/equipe", "/painel/equipe", mapOf("nome" to nome, "pin" to pin))

    private fun atendente(
        email: String,
        nome: String,
    ): Atendente = atendentes.findAllByBarracaIdOrderByNome(barracaId(email)).first { it.nome == nome }

    @Test
    fun `adiciona atendente com PIN guardado em hash`() {
        val email = painel.novoEmail()
        val http = painel.donoComBarraca(email = email)

        val response = adicionar(http, "  Carla   Souza ")

        assertEquals(302, response.statusCode(), response.body())
        val carla = atendente(email, "Carla Souza")
        assertNotEquals("2580", carla.pinHash)
        assertTrue(carla.pinHash.startsWith("{bcrypt}"))
        val pagina = http.get("/painel/equipe").body()
        assertHtmlContains(pagina, "Carla Souza foi adicionado.", anchor = "<main")
        assertHtmlContains(pagina, "Ativo", anchor = "team-list")
    }

    @Test
    fun `nome repetido na mesma barraca e PIN fraco sao recusados`() {
        val http = painel.donoComBarraca()
        adicionar(http, "Carla")

        val repetido = adicionar(http, "CARLA")
        assertEquals(422, repetido.statusCode())
        assertHtmlContains(repetido.body(), "Já existe um atendente com esse nome.")

        val fraco = adicionar(http, "Beto", pin = "1234")
        assertEquals(422, fraco.statusCode())
        assertHtmlContains(fraco.body(), "PIN em sequência")
        assertTrue(!fraco.body().contains("value=\"1234\""), "o PIN não volta no HTML")
    }

    @Test
    fun `mesmo nome em barracas diferentes pode`() {
        adicionar(painel.donoComBarraca(), "Duda").also { assertEquals(302, it.statusCode()) }
        adicionar(painel.donoComBarraca(), "Duda").also { assertEquals(302, it.statusCode()) }
    }

    @Test
    fun `5 PINs errados bloqueiam por 15 minutos e o dono desbloqueia`() {
        val email = painel.novoEmail()
        val http = painel.donoComBarraca(email = email)
        adicionar(http, "Edu", pin = "2580")
        val edu = atendente(email, "Edu")
        val barraca = barracaId(email)

        repeat(4) { assertIs<ResultadoPin.PinErrado>(service.autenticar(barraca, edu.id, "9999")) }
        val bloqueio = service.autenticar(barraca, edu.id, "9999")
        assertIs<ResultadoPin.Bloqueado>(bloqueio)
        // Bloqueado, nem o PIN certo entra.
        assertIs<ResultadoPin.Bloqueado>(service.autenticar(barraca, edu.id, "2580"))
        assertHtmlContains(http.get("/painel/equipe").body(), "Bloqueado até", anchor = "team-list")

        painel.post(http, "/painel/equipe", "/painel/equipe/${edu.id}/desbloquear", emptyMap())
        assertIs<ResultadoPin.Ok>(service.autenticar(barraca, edu.id, "2580"))
    }

    @Test
    fun `acertar o PIN zera as tentativas`() {
        val email = painel.novoEmail()
        adicionar(painel.donoComBarraca(email = email), "Fabi", pin = "2580")
        val fabi = atendente(email, "Fabi")
        val barraca = barracaId(email)

        repeat(4) { service.autenticar(barraca, fabi.id, "0000") }
        assertIs<ResultadoPin.Ok>(service.autenticar(barraca, fabi.id, "2580"))
        val depois = service.autenticar(barraca, fabi.id, "0000")
        assertEquals(ResultadoPin.PinErrado(4), depois)
    }

    @Test
    fun `tentativas em paralelo nao furam o bloqueio`() {
        val email = painel.novoEmail()
        adicionar(painel.donoComBarraca(email = email), "Gil", pin = "2580")
        val gil = atendente(email, "Gil")
        val barraca = barracaId(email)

        val pool = Executors.newFixedThreadPool(8)
        val resultados = pool.invokeAll(List(12) { Callable { service.autenticar(barraca, gil.id, "7777") } }).map { it.get() }
        pool.shutdown()

        assertEquals(4, resultados.count { it is ResultadoPin.PinErrado }, resultados.toString())
        assertEquals(8, resultados.count { it is ResultadoPin.Bloqueado })
    }

    @Test
    fun `trocar o PIN desbloqueia e o antigo deixa de valer`() {
        val email = painel.novoEmail()
        val http = painel.donoComBarraca(email = email)
        adicionar(http, "Hugo", pin = "2580")
        val hugo = atendente(email, "Hugo")
        val barraca = barracaId(email)
        repeat(5) { service.autenticar(barraca, hugo.id, "0000") }

        val fraco = painel.post(http, "/painel/equipe", "/painel/equipe/${hugo.id}/pin", mapOf("pin" to "1111"))
        assertEquals(302, fraco.statusCode())
        assertHtmlContains(http.get("/painel/equipe").body(), "todos os números iguais", anchor = "<main")

        painel.post(http, "/painel/equipe", "/painel/equipe/${hugo.id}/pin", mapOf("pin" to "8642"))
        assertIs<ResultadoPin.PinErrado>(service.autenticar(barraca, hugo.id, "2580"))
        assertIs<ResultadoPin.Ok>(service.autenticar(barraca, hugo.id, "8642"))
    }

    @Test
    fun `atendente desativado nao entra`() {
        val email = painel.novoEmail()
        val http = painel.donoComBarraca(email = email)
        adicionar(http, "Ivo", pin = "2580")
        val ivo = atendente(email, "Ivo")

        painel.post(http, "/painel/equipe", "/painel/equipe/${ivo.id}/ativo", mapOf("ativo" to "false"))

        assertEquals(ResultadoPin.Indisponivel, service.autenticar(barracaId(email), ivo.id, "2580"))
        assertHtmlContains(http.get("/painel/equipe").body(), "Desativado", anchor = "team-list")
    }

    @Test
    fun `PIN de uma barraca nao vale com o id de outra barraca`() {
        val a = painel.novoEmail()
        adicionar(painel.donoComBarraca(email = a), "Juca", pin = "2580")
        val juca = atendente(a, "Juca")
        val b = painel.novoEmail()
        painel.donoComBarraca(email = b)

        assertEquals(ResultadoPin.Indisponivel, service.autenticar(barracaId(b), juca.id, "2580"))
    }

    @Test
    fun `dono de outra barraca nao altera a equipe alheia`() {
        val a = painel.novoEmail()
        adicionar(painel.donoComBarraca(email = a), "Lia", pin = "2580")
        val lia = atendente(a, "Lia")
        val barracaA = barracaId(a)
        repeat(5) { service.autenticar(barracaA, lia.id, "0000") }
        val hashAntes = lia.pinHash

        val intruso = painel.donoComBarraca()
        listOf(
            "/painel/equipe/${lia.id}/pin" to mapOf("pin" to "8642"),
            "/painel/equipe/${lia.id}/ativo" to mapOf("ativo" to "false"),
            "/painel/equipe/${lia.id}/desbloquear" to emptyMap(),
        ).forEach { (acao, campos) ->
            val response = painel.post(intruso, "/painel/equipe", acao, campos)
            assertEquals(302, response.statusCode(), acao)
        }
        assertHtmlContains(intruso.get("/painel/equipe").body(), "Atendente não encontrado.", anchor = "<main")
        assertTrue(!intruso.get("/painel/equipe").body().contains("Lia"))

        val depois = atendentes.findById(lia.id).orElseThrow()
        assertEquals(hashAntes, depois.pinHash)
        assertTrue(depois.ativo)
        assertNotNull(depois.bloqueadoAte, "continua bloqueado")
    }
}
