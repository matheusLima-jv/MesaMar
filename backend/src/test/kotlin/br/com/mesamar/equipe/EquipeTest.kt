package br.com.mesamar.equipe

import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.assertHtmlContains
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@IntegrationTest
class EquipeTest(
    @Value("\${local.server.port}") port: Int,
    convites: ConviteService,
    private val usuarios: UsuarioRepository,
    private val atendentes: AtendenteRepository,
    private val barracas: br.com.mesamar.barraca.BarracaRepository,
    private val service: AtendenteService,
    private val pushSubs: br.com.mesamar.push.PushSubscriptionRepository,
    private val hub: br.com.mesamar.eventos.HubEventos,
    private val encoder: PasswordEncoder,
    private val transacoes: PlatformTransactionManager,
) {
    private val painel = PainelTestSupport(port, convites)

    private fun barracaId(email: String): UUID = assertNotNull(usuarios.findByEmailIgnoreCase(email)?.barracaId)

    private fun adicionar(
        http: HttpTestClient,
        nome: String,
        pin: String = "258014",
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
        assertNotEquals("258014", carla.pinHash)
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

        val fraco = adicionar(http, "Beto", pin = "123456")
        assertEquals(422, fraco.statusCode())
        assertHtmlContains(fraco.body(), "PIN em sequência")
        assertTrue(!fraco.body().contains("value=\"123456\""), "o PIN não volta no HTML")
    }

    @Test
    fun `mesmo nome em barracas diferentes pode`() {
        adicionar(painel.donoComBarraca(), "Duda").also { assertEquals(302, it.statusCode()) }
        adicionar(painel.donoComBarraca(), "Duda").also { assertEquals(302, it.statusCode()) }
    }

    @Test
    fun `5 PINs errados bloqueiam so a origem por 15 minutos e o dono desbloqueia`() {
        val email = painel.novoEmail()
        val http = painel.donoComBarraca(email = email)
        adicionar(http, "Edu", pin = "258014")
        val edu = atendente(email, "Edu")
        val barraca = barracaId(email)

        repeat(4) { assertIs<ResultadoPin.PinErrado>(service.autenticar(barraca, edu.id, "9999", "10.0.0.1")) }
        assertIs<ResultadoPin.Bloqueado>(service.autenticar(barraca, edu.id, "9999", "10.0.0.1"))
        // Bloqueada a origem, nem o PIN certo entra por ela.
        assertIs<ResultadoPin.Bloqueado>(service.autenticar(barraca, edu.id, "258014", "10.0.0.1"))
        // Mas a atendente entra pelo celular do quiosque, e o painel não mostra a conta como bloqueada.
        assertTrue(!http.get("/painel/equipe").body().contains("Bloqueado até"))
        assertIs<ResultadoPin.Ok>(service.autenticar(barraca, edu.id, "258014", "10.0.0.2"))

        // O dono desbloqueia: a origem que estava bloqueada volta a poder entrar.
        painel.post(http, "/painel/equipe", "/painel/equipe/${edu.id}/desbloquear", emptyMap())
        assertIs<ResultadoPin.Ok>(service.autenticar(barraca, edu.id, "258014", "10.0.0.1"))
    }

    @Test
    fun `20 PINs errados de varias origens bloqueiam a atendente para todos e o dono desbloqueia`() {
        val email = painel.novoEmail()
        val http = painel.donoComBarraca(email = email)
        adicionar(http, "Edna", pin = "258014")
        val edna = atendente(email, "Edna")
        val barraca = barracaId(email)

        repeat(19) { assertIs<ResultadoPin.PinErrado>(service.autenticar(barraca, edna.id, "9999", "10.1.${it / 4}.${it % 4}")) }
        assertIs<ResultadoPin.Bloqueado>(service.autenticar(barraca, edna.id, "9999", "10.2.0.1"))
        // Bloqueada para todos: nem o PIN certo, nem de uma origem nova.
        assertIs<ResultadoPin.Bloqueado>(service.autenticar(barraca, edna.id, "258014", "10.3.0.1"))
        assertHtmlContains(http.get("/painel/equipe").body(), "Bloqueado até", anchor = "team-list")

        painel.post(http, "/painel/equipe", "/painel/equipe/${edna.id}/desbloquear", emptyMap())
        assertIs<ResultadoPin.Ok>(service.autenticar(barraca, edna.id, "258014", "10.3.0.1"))
    }

    /** Um relógio que o teste adianta, para o bloqueio de 15 minutos vencer sem esperar. */
    private class Relogio(
        var agora: Instant = Instant.now(),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = agora
    }

    @Test
    fun `uma origem sozinha insistindo por horas nunca bloqueia a atendente para todos`() {
        val email = painel.novoEmail()
        adicionar(painel.donoComBarraca(email = email), "Gabi", pin = "258014")
        val gabi = atendente(email, "Gabi")
        val barraca = barracaId(email)
        val relogio = Relogio()
        val comRelogio =
            AtendenteService(
                org.mockito.Mockito.mock(br.com.mesamar.common.AutorHistorico::class.java),
                atendentes,
                encoder,
                barracas,
                pushSubs,
                hub,
                relogio,
            )
        val tx = TransactionTemplate(transacoes)

        fun tentar(
            pin: String,
            origem: String,
        ) = tx.execute { comRelogio.autenticar(barraca, gabi.id, pin, origem) }

        // 8 rodadas de 5 erros (40 erros, mais de 2 horas) do mesmo lugar
        repeat(8) {
            repeat(5) { tentar("9999", "10.6.0.1") }
            assertIs<ResultadoPin.Bloqueado>(tentar("258014", "10.6.0.1"), "a origem fica bloqueada")
            assertNull(atendentes.findById(gabi.id).orElseThrow().bloqueadoAte, "a conta nunca é bloqueada (rodada ${it + 1})")
            relogio.agora = relogio.agora.plus(AtendenteService.BLOQUEIO).plus(Duration.ofSeconds(1))
        }

        // A atendente continua entrando pelo celular do quiosque, e o painel não a mostra bloqueada
        assertIs<ResultadoPin.Ok>(tentar("258014", "10.6.0.2"))
        assertEquals(null, atendentes.findById(gabi.id).orElseThrow().bloqueadoAte)
    }

    @Test
    fun `acertar o PIN zera o contador das outras origens`() {
        val email = painel.novoEmail()
        adicionar(painel.donoComBarraca(email = email), "Fred", pin = "258014")
        val fred = atendente(email, "Fred")
        val barraca = barracaId(email)

        repeat(4) { service.autenticar(barraca, fred.id, "0000", "10.4.0.1") }
        assertIs<ResultadoPin.Ok>(service.autenticar(barraca, fred.id, "258014", "10.4.0.2"))
        assertEquals(ResultadoPin.PinErrado(4), service.autenticar(barraca, fred.id, "0000", "10.4.0.1"))
    }

    @Test
    fun `acertar o PIN zera as tentativas`() {
        val email = painel.novoEmail()
        adicionar(painel.donoComBarraca(email = email), "Fabi", pin = "258014")
        val fabi = atendente(email, "Fabi")
        val barraca = barracaId(email)

        repeat(4) { service.autenticar(barraca, fabi.id, "0000") }
        assertIs<ResultadoPin.Ok>(service.autenticar(barraca, fabi.id, "258014"))
        val depois = service.autenticar(barraca, fabi.id, "0000")
        assertEquals(ResultadoPin.PinErrado(4), depois)
    }

    @Test
    fun `tentativas em paralelo nao furam o bloqueio`() {
        val email = painel.novoEmail()
        adicionar(painel.donoComBarraca(email = email), "Gil", pin = "258014")
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
        adicionar(http, "Hugo", pin = "258014")
        val hugo = atendente(email, "Hugo")
        val barraca = barracaId(email)
        repeat(5) { service.autenticar(barraca, hugo.id, "0000") }

        val fraco = painel.post(http, "/painel/equipe", "/painel/equipe/${hugo.id}/pin", mapOf("pin" to "111111"))
        assertEquals(302, fraco.statusCode())
        assertHtmlContains(http.get("/painel/equipe").body(), "todos os números iguais", anchor = "<main")

        painel.post(http, "/painel/equipe", "/painel/equipe/${hugo.id}/pin", mapOf("pin" to "864213"))
        assertIs<ResultadoPin.PinErrado>(service.autenticar(barraca, hugo.id, "258014"))
        assertIs<ResultadoPin.Ok>(service.autenticar(barraca, hugo.id, "864213"))
    }

    @Test
    fun `atendente desativado nao entra`() {
        val email = painel.novoEmail()
        val http = painel.donoComBarraca(email = email)
        adicionar(http, "Ivo", pin = "258014")
        val ivo = atendente(email, "Ivo")

        painel.post(http, "/painel/equipe", "/painel/equipe/${ivo.id}/ativo", mapOf("ativo" to "false"))

        assertEquals(ResultadoPin.Indisponivel, service.autenticar(barracaId(email), ivo.id, "258014"))
        assertHtmlContains(http.get("/painel/equipe").body(), "Desativado", anchor = "team-list")
    }

    @Test
    fun `PIN de uma barraca nao vale com o id de outra barraca`() {
        val a = painel.novoEmail()
        adicionar(painel.donoComBarraca(email = a), "Juca", pin = "258014")
        val juca = atendente(a, "Juca")
        val b = painel.novoEmail()
        painel.donoComBarraca(email = b)

        assertEquals(ResultadoPin.Indisponivel, service.autenticar(barracaId(b), juca.id, "258014"))
    }

    @Test
    fun `dono de outra barraca nao altera a equipe alheia`() {
        val a = painel.novoEmail()
        adicionar(painel.donoComBarraca(email = a), "Lia", pin = "258014")
        val lia = atendente(a, "Lia")
        val barracaA = barracaId(a)
        repeat(AtendenteService.MAX_TENTATIVAS_CONTA) { service.autenticar(barracaA, lia.id, "0000", "10.5.0.$it") }
        val hashAntes = lia.pinHash

        val intruso = painel.donoComBarraca()
        listOf(
            "/painel/equipe/${lia.id}/pin" to mapOf("pin" to "864213"),
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
