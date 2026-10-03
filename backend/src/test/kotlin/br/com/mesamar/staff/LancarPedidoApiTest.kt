package br.com.mesamar.staff

import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.comanda.StatusComanda
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.equipe.AtendenteRepository
import br.com.mesamar.pedido.OrigemPedido
import br.com.mesamar.pedido.PedidoRepository
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.Item
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.PainelTestSupport.Companion.SENHA
import br.com.mesamar.support.Quiosque
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Lançar pedido para cliente" e check-in com o token gerado no aparelho (Fase 6 · 6.1). */
@IntegrationTest
class LancarPedidoApiTest(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    checkin: CheckinService,
    private val barracas: BarracaRepository,
    private val comandas: ComandaRepository,
    private val pedidos: PedidoRepository,
    private val atendentes: AtendenteRepository,
) {
    private val painel = PainelTestSupport(port, convites)
    private val apoio = ClienteTestSupport(port, painel, usuarios, categorias, produtos, checkin)

    private fun donoNoApp(q: Quiosque): StaffApp =
        StaffApp(port).also {
            assertEquals(200, it.post("/api/staff/entrar/dono", """{"email":"${q.email}","senha":"$SENHA"}""").statusCode())
        }

    private fun comandaId(token: String): UUID = assertNotNull(comandas.findByTokenAcesso(token)).id

    /** Token de 128 bits, como o aparelho gera com `crypto.getRandomValues`. */
    private fun tokenDoAparelho(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(16).also {
                SecureRandom().nextBytes(it)
            },
        )

    @Test
    fun `dono lanca pedido pela comanda e ele entra na fila com origem ATENDENTE`() {
        val q = apoio.quiosque("Barraca Lançar 1")
        val comanda = comandaId(apoio.novaComanda(q, "Sem Celular"))
        val app = donoNoApp(q)
        val id = UUID.randomUUID()

        val r = app.post("/api/staff/comandas/$comanda/pedidos", apoio.corpo(id, Item(q.coco.id, 2, "gelada"), Item(q.pastel.id, 1)))

        assertEquals(201, r.statusCode(), r.body())
        assertContains(r.body(), "\"status\":\"RECEBIDO\"")
        assertContains(r.body(), "\"totalCentavos\":4900")
        val pedido = assertNotNull(pedidos.findById(id).orElse(null))
        assertEquals(OrigemPedido.ATENDENTE, pedido.origem)
        assertEquals(q.usuarioId, pedido.criadoPorUsuarioId)
        assertNull(pedido.criadoPorAtendenteId)
        assertContains(app.get("/api/staff/fila").body(), id.toString())
    }

    @Test
    fun `reenviar o mesmo pedido nao duplica`() {
        val q = apoio.quiosque("Barraca Lançar 2")
        val comanda = comandaId(apoio.novaComanda(q))
        val app = donoNoApp(q)
        val id = UUID.randomUUID()
        val corpo = apoio.corpo(id, Item(q.coco.id, 1))

        assertEquals(201, app.post("/api/staff/comandas/$comanda/pedidos", corpo).statusCode())
        assertEquals(200, app.post("/api/staff/comandas/$comanda/pedidos", corpo).statusCode())
        assertEquals(1, pedidos.findAllByComandaIdOrderByCriadoEmAscNumeroPedidoAsc(comanda).size)
    }

    @Test
    fun `atendente lanca e o pedido guarda quem lancou`() {
        val q = apoio.quiosque("Barraca Lançar 3")
        val comanda = comandaId(apoio.novaComanda(q))
        val dono = painel.session().also { painel.login(it, q.email) }
        painel.post(dono, "/painel/equipe", "/painel/equipe", mapOf("nome" to "Carla", "pin" to "258014"))
        dono.get("/painel/equipe")
        val codigo =
            assertNotNull(
                Regex("""<span class="mono">([a-z0-9-]+)</span>""").find(dono.get("/painel/barraca").body()),
            ).groupValues[1]
        val carla = atendentes.findAllByBarracaIdOrderByNome(q.barracaId).first { it.nome == "Carla" }
        val app = StaffApp(port)
        val entrou = app.post("/api/staff/entrar/atendente", """{"codigo":"$codigo","atendenteId":"${carla.id}","pin":"258014"}""")
        assertEquals(200, entrou.statusCode(), entrou.body())
        val id = UUID.randomUUID()

        val r = app.post("/api/staff/comandas/$comanda/pedidos", apoio.corpo(id, Item(q.coco.id, 1)))

        assertEquals(201, r.statusCode(), r.body())
        val pedido = assertNotNull(pedidos.findById(id).orElse(null))
        assertEquals(OrigemPedido.ATENDENTE, pedido.origem)
        assertEquals(carla.id, pedido.criadoPorAtendenteId)
        assertNull(pedido.criadoPorUsuarioId)
    }

    @Test
    fun `pausa e limite de saldo nao barram a equipe, mas barram o cliente`() {
        val q = apoio.quiosque("Barraca Lançar 4")
        val token = apoio.novaComanda(q)
        val comanda = comandaId(token)
        val barraca = barracas.findById(q.barracaId).orElseThrow()
        barraca.pedidosPausados = true
        barraca.limiteSaldoAbertoCentavos = 1000
        barracas.save(barraca)

        val cliente = apoio.pedir(apoio.celular(), token, UUID.randomUUID(), Item(q.coco.id, 1))
        assertEquals(409, cliente.statusCode())
        assertContains(cliente.body(), "pedidos-pausados")

        val equipe = donoNoApp(q).post("/api/staff/comandas/$comanda/pedidos", apoio.corpo(UUID.randomUUID(), Item(q.pastel.id, 1)))
        assertEquals(201, equipe.statusCode(), equipe.body())
    }

    @Test
    fun `comanda fechada ou bloqueada continua recusando o pedido da equipe`() {
        val q = apoio.quiosque("Barraca Lançar 5")
        val token = apoio.novaComanda(q)
        val comanda = assertNotNull(comandas.findByTokenAcesso(token))
        comanda.status = StatusComanda.BLOQUEADA
        comandas.save(comanda)

        val r = donoNoApp(q).post("/api/staff/comandas/${comanda.id}/pedidos", apoio.corpo(UUID.randomUUID(), Item(q.coco.id, 1)))

        assertEquals(409, r.statusCode())
        assertContains(r.body(), "comanda-bloqueada")
    }

    @Test
    fun `produto de outra barraca e item invalido sao recusados`() {
        val a = apoio.quiosque("Barraca Lançar 6a")
        val b = apoio.quiosque("Barraca Lançar 6b")
        val comandaA = comandaId(apoio.novaComanda(a))
        val app = donoNoApp(a)

        val deOutra = app.post("/api/staff/comandas/$comandaA/pedidos", apoio.corpo(UUID.randomUUID(), Item(b.coco.id, 1)))
        assertEquals(409, deOutra.statusCode())
        assertContains(deOutra.body(), "indisponivel")
        assertTrue(!deOutra.body().contains("Água de coco"), "não vaza o nome do produto da outra barraca")

        assertEquals(400, app.post("/api/staff/comandas/$comandaA/pedidos", apoio.corpo(UUID.randomUUID())).statusCode())
        assertEquals(400, app.post("/api/staff/comandas/$comandaA/pedidos", """{"itens":"lixo"}""").statusCode())
    }

    @Test
    fun `isolamento - dono de outra barraca nao lanca na comanda alheia`() {
        val a = apoio.quiosque("Barraca Lançar 7a")
        val b = apoio.quiosque("Barraca Lançar 7b")
        val comandaA = comandaId(apoio.novaComanda(a))

        val r = donoNoApp(b).post("/api/staff/comandas/$comandaA/pedidos", apoio.corpo(UUID.randomUUID(), Item(b.coco.id, 1)))

        assertEquals(404, r.statusCode())
        assertEquals(0, pedidos.findAllByComandaIdOrderByCriadoEmAscNumeroPedidoAsc(comandaA).size)
    }

    @Test
    fun `sem login nao lanca pedido`() {
        val q = apoio.quiosque("Barraca Lançar 8")
        val comanda = comandaId(apoio.novaComanda(q))

        val r = StaffApp(port).post("/api/staff/comandas/$comanda/pedidos", apoio.corpo(UUID.randomUUID(), Item(q.coco.id, 1)))

        assertEquals(401, r.statusCode())
    }

    // ---- check-in com o token gerado no aparelho ----

    private fun checkinComToken(
        app: StaffApp,
        id: UUID,
        token: String,
    ) = app.post("/api/staff/clientes", """{"id":"$id","nome":"Offline","consentimento":true,"token":"$token"}""")

    @Test
    fun `check-in adota o token gerado no aparelho e o QR ja vale`() {
        val q = apoio.quiosque("Barraca Token 1")
        val app = donoNoApp(q)
        val token = tokenDoAparelho()

        val r = checkinComToken(app, UUID.randomUUID(), token)

        assertEquals(201, r.statusCode(), r.body())
        assertContains(r.body(), "/c/$token")
        assertEquals(200, apoio.celular().get("/api/c/$token").statusCode())
    }

    @Test
    fun `repetir o check-in com o mesmo id nao muda nada, e token repetido de outra comanda e recusado`() {
        val q = apoio.quiosque("Barraca Token 2")
        val app = donoNoApp(q)
        val id = UUID.randomUUID()
        val token = tokenDoAparelho()

        assertEquals(201, checkinComToken(app, id, token).statusCode())
        assertEquals(200, checkinComToken(app, id, token).statusCode())
        val outro = checkinComToken(app, UUID.randomUUID(), token)
        assertEquals(409, outro.statusCode())
    }

    @Test
    fun `token do aparelho fraco ou mal formado e recusado`() {
        val q = apoio.quiosque("Barraca Token 3")
        val app = donoNoApp(q)

        listOf("curto", "AAAAAAAAAAAAAAAAAAAAAA", "abcabcabcabcabcabcabcA", "!!!!!!!!!!!!!!!!!!!!!!", "x".repeat(43)).forEach { ruim ->
            val r = checkinComToken(app, UUID.randomUUID(), ruim)
            assertEquals(400, r.statusCode(), "token $ruim")
            assertContains(r.body(), "\"token\"")
        }
    }
}
