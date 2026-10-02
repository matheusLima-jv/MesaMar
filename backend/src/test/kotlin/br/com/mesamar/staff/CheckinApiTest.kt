package br.com.mesamar.staff

import br.com.mesamar.comanda.ClienteRepository
import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.comanda.ContadorDiario
import br.com.mesamar.comanda.StatusComanda
import br.com.mesamar.comanda.TipoContador
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.equipe.AtendenteRepository
import br.com.mesamar.guardasol.GuardaSolRepository
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.PainelTestSupport.Companion.SENHA
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import java.net.http.HttpResponse
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@IntegrationTest
class CheckinApiTest(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    private val usuarios: UsuarioRepository,
    private val atendentes: AtendenteRepository,
    private val guardaSois: GuardaSolRepository,
    private val comandas: ComandaRepository,
    private val clientes: ClienteRepository,
    private val contador: ContadorDiario,
) {
    private val painel = PainelTestSupport(port, convites)

    /** Barraca com os guarda-sóis 1 a 15 e a atendente Carla (PIN 258014). Devolve o e-mail do dono. */
    private fun barracaComGuardaSois(nome: String = "Barraca Check-in"): String {
        val email = painel.novoEmail("checkin")
        val http = painel.donoComBarraca(nomeBarraca = nome, email = email)
        painel.post(http, "/painel/guarda-sois", "/painel/guarda-sois", mapOf("de" to "1", "ate" to "15"))
        http.get("/painel/guarda-sois")
        painel.post(http, "/painel/equipe", "/painel/equipe", mapOf("nome" to "Carla", "pin" to "258014"))
        http.get("/painel/equipe")
        return email
    }

    private fun barracaId(email: String): UUID = assertNotNull(usuarios.findByEmailIgnoreCase(email)?.barracaId)

    private fun guardaSol(
        email: String,
        numero: Int,
    ) = guardaSois.findAllByBarracaIdOrderByNumero(barracaId(email)).first { it.numero == numero }

    private fun donoNoApp(email: String): StaffApp =
        StaffApp(port).also { assertEquals(200, it.post("/api/staff/entrar/dono", """{"email":"$email","senha":"$SENHA"}""").statusCode()) }

    private fun carlaNoApp(email: String): StaffApp {
        val codigo = assertNotNull(Regex("""<span class="mono">([a-z0-9-]+)</span>""").find(donoPainel(email))).groupValues[1]
        val carla = atendentes.findAllByBarracaIdOrderByNome(barracaId(email)).first { it.nome == "Carla" }
        return StaffApp(port).also {
            val r = it.post("/api/staff/entrar/atendente", """{"codigo":"$codigo","atendenteId":"${carla.id}","pin":"258014"}""")
            assertEquals(200, r.statusCode(), r.body())
        }
    }

    private fun donoPainel(email: String): String =
        painel
            .session()
            .also { painel.login(it, email) }
            .get("/painel/barraca")
            .body()

    private fun checkin(
        app: StaffApp,
        nome: String,
        guardaSolId: UUID? = null,
        whatsapp: String = "",
        consentimento: Boolean = true,
        id: UUID? = null,
    ): HttpResponse<String> {
        val campos =
            listOfNotNull(
                id?.let { "\"id\":\"$it\"" },
                "\"nome\":\"$nome\"",
                guardaSolId?.let { "\"guardaSolId\":\"$it\"" },
                "\"whatsapp\":\"$whatsapp\"",
                "\"consentimento\":$consentimento",
            )
        return app.post("/api/staff/clientes", campos.joinToString(",", "{", "}"))
    }

    private fun token(body: String): String = assertNotNull(Regex(""""link":"[^"]*/c/([A-Za-z0-9_-]+)"""").find(body)).groupValues[1]

    private fun numero(body: String): Int = assertNotNull(Regex(""""numeroChegada":(\d+)""").find(body)).groupValues[1].toInt()

    @Test
    fun `check-in cria cliente e comanda com token de 128 bits e o link do QR`() {
        val email = barracaComGuardaSois()
        val app = donoNoApp(email)

        val r = checkin(app, "  Ana   Maria ", guardaSol(email, 12).id, "(13) 99999-8888")

        assertEquals(201, r.statusCode(), r.body())
        val body = r.body()
        assertContains(body, "\"nome\":\"Ana Maria\"")
        assertContains(body, "\"guardaSol\":12")
        assertContains(body, "\"numeroChegada\":1")
        assertContains(body, "\"whatsapp\":\"5513999998888\"")
        assertContains(body, "\"whatsappExibicao\":\"(13) 99999-8888\"")
        assertContains(body, "\"link\":\"http://localhost:8080/c/")
        val token = token(body)
        assertEquals(22, token.length)
        assertEquals(16, Base64.getUrlDecoder().decode(token).size, "128 bits de entropia")

        val comanda = assertNotNull(comandas.findByTokenAcesso(token))
        assertEquals(StatusComanda.ABERTA, comanda.status)
        assertEquals(barracaId(email), comanda.barracaId)
        assertEquals(usuarios.findByEmailIgnoreCase(email)?.id, comanda.abertaPorUsuarioId)
        assertNull(comanda.abertaPorAtendenteId)
        val cliente = clientes.findById(comanda.clienteId).orElseThrow()
        assertEquals("+5513999998888", cliente.whatsappE164)
        assertNotNull(cliente.consentimentoEm)
    }

    @Test
    fun `atendente faz o check-in e a lista sai por ordem de chegada`() {
        val email = barracaComGuardaSois()
        val app = carlaNoApp(email)
        val tres = guardaSol(email, 3).id

        assertEquals(1, numero(checkin(app, "Bia").body()), "sem guarda-sol (no balcão) também vale")
        assertEquals(2, numero(checkin(app, "Caio", tres).body()))
        assertEquals(3, numero(checkin(app, "Duda", tres).body()), "dois clientes no mesmo guarda-sol, cada um com a sua comanda")

        val lista = app.get("/api/staff/clientes").body()
        assertTrue(lista.indexOf("Bia") < lista.indexOf("Caio") && lista.indexOf("Caio") < lista.indexOf("Duda"), lista)
        val carla = atendentes.findAllByBarracaIdOrderByNome(barracaId(email)).first { it.nome == "Carla" }
        assertTrue(comandas.findAll().filter { it.barracaId == barracaId(email) }.all { it.abertaPorAtendenteId == carla.id })

        val gs = app.get("/api/staff/guarda-sois").body()
        assertContains(gs, """{"id":"$tres","numero":3,"clientes":2}""")
        assertContains(gs, "\"numero\":15")
    }

    @Test
    fun `campos invalidos sao recusados com a mensagem de cada um e nada e criado`() {
        val email = barracaComGuardaSois()
        val app = donoNoApp(email)

        val r = checkin(app, "   ", whatsapp = "(13) 1234", consentimento = false)

        assertEquals(400, r.statusCode())
        assertContains(r.body(), "\"nome\":\"Digite o nome do cliente.\"")
        assertContains(r.body(), "\"whatsapp\":\"WhatsApp inválido. Exemplo: (13) 99999-8888.\"")
        assertContains(r.body(), "\"consentimento\":\"Confirme que o cliente concordou.\"")
        assertEquals(400, checkin(app, "A".repeat(41)).statusCode())
        assertEquals(0, comandas.countByBarracaId(barracaId(email)))
    }

    @Test
    fun `guarda-sol desativado ou de outra barraca e recusado`() {
        val a = barracaComGuardaSois("Barraca GS A")
        val b = barracaComGuardaSois("Barraca GS B")
        val appB = donoNoApp(b)

        val deOutra = checkin(appB, "Eva", guardaSol(a, 1).id)
        assertEquals(400, deOutra.statusCode())
        assertContains(deOutra.body(), "\"guardaSolId\":\"Esse guarda-sol não está disponível. Escolha outro.\"")

        val cinco = guardaSol(b, 5)
        cinco.ativo = false
        guardaSois.save(cinco)
        assertEquals(400, checkin(appB, "Eva", cinco.id).statusCode())
        assertFalse(appB.get("/api/staff/guarda-sois").body().contains("\"numero\":5,"))
        assertEquals(0, comandas.countByBarracaId(barracaId(b)))
    }

    @Test
    fun `o mesmo check-in enviado 3 vezes cria uma comanda so`() {
        val email = barracaComGuardaSois()
        val app = donoNoApp(email)
        val id = UUID.randomUUID()

        val primeira = checkin(app, "Fábio", id = id)
        val segunda = checkin(app, "Fábio", id = id)
        val terceira = checkin(app, "Fábio", id = id)

        assertEquals(201, primeira.statusCode(), primeira.body())
        assertEquals(200, segunda.statusCode())
        assertEquals(200, terceira.statusCode())
        assertEquals(token(primeira.body()), token(terceira.body()))
        assertEquals(1, comandas.countByBarracaId(barracaId(email)))
        assertEquals(2, numero(checkin(app, "Gabi").body()), "o reenvio não gasta número de chegada")
    }

    @Test
    fun `check-ins ao mesmo tempo nunca repetem numero nem token`() {
        val email = barracaComGuardaSois()
        val app = donoNoApp(email)
        val pool = Executors.newFixedThreadPool(8)

        val respostas =
            try {
                pool.invokeAll((1..20).map { n -> Callable { checkin(app, "Cliente $n") } }).map { it.get() }
            } finally {
                pool.shutdown()
            }

        assertTrue(respostas.all { it.statusCode() == 201 }, respostas.map { it.statusCode() }.toString())
        assertEquals((1..20).toList(), respostas.map { numero(it.body()) }.sorted())
        assertEquals(20, respostas.map { token(it.body()) }.toSet().size)
    }

    @Test
    fun `numero de chegada recomeca a cada dia`() {
        val barraca = barracaId(barracaComGuardaSois())
        val dia = LocalDate.of(2027, 1, 10)

        assertEquals(1, contador.proximo(barraca, dia, TipoContador.CHEGADA))
        assertEquals(2, contador.proximo(barraca, dia, TipoContador.CHEGADA))
        assertEquals(1, contador.proximo(barraca, dia.plusDays(1), TipoContador.CHEGADA))
        assertEquals(1, contador.proximo(barraca, dia, TipoContador.PEDIDO), "pedido tem contador próprio")
    }

    @Test
    fun `uma barraca nao ve nem reaproveita a comanda de outra`() {
        val a = barracaComGuardaSois("Barraca Iso A")
        val appA = donoNoApp(a)
        val idA = UUID.randomUUID()
        val tokenA = token(checkin(appA, "Helena", id = idA).body())
        val appB = donoNoApp(barracaComGuardaSois("Barraca Iso B"))

        val lista = appB.get("/api/staff/clientes").body()
        assertEquals("[]", lista)

        val mesmoId = checkin(appB, "Intruso", id = idA)
        assertEquals(409, mesmoId.statusCode())
        assertFalse(mesmoId.body().contains(tokenA), "não vaza o token de A")
        assertEquals("Helena", clientes.findById(comandas.findById(idA).orElseThrow().clienteId).orElseThrow().nome)
    }

    @Test
    fun `sem login nao lista nem cria e POST sem CSRF e recusado`() {
        val email = barracaComGuardaSois()
        val anonimo = StaffApp(port)
        assertEquals(401, anonimo.get("/api/staff/clientes").statusCode())
        assertEquals(401, anonimo.get("/api/staff/guarda-sois").statusCode())

        val app = donoNoApp(email)
        val semCsrf = app.post("/api/staff/clientes", """{"nome":"Ivo","consentimento":true}""", comCsrf = false)
        assertEquals(403, semCsrf.statusCode())
        assertEquals(0, comandas.countByBarracaId(barracaId(email)))
    }
}
