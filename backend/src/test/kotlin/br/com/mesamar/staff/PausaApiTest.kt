package br.com.mesamar.staff

import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.pedido.PedidoRepository
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.Item
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.PainelTestSupport.Companion.SENHA
import br.com.mesamar.support.Quiosque
import br.com.mesamar.support.SseTestClient
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Pausar pedidos": o quiosque para de receber pelo celular dos clientes, que são avisados na hora (Fase 4 · 4.2). */
@IntegrationTest
class PausaApiTest(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    checkin: CheckinService,
    private val barracas: BarracaRepository,
    private val pedidos: PedidoRepository,
) {
    private val apoio = ClienteTestSupport(port, PainelTestSupport(port, convites), usuarios, categorias, produtos, checkin)

    private fun app(q: Quiosque): StaffApp =
        StaffApp(port).also {
            assertEquals(200, it.post("/api/staff/entrar/dono", """{"email":"${q.email}","senha":"$SENHA"}""").statusCode())
        }

    private fun pausar(
        app: StaffApp,
        pausado: Boolean?,
        mensagem: String? = null,
    ): HttpResponse<String> =
        app.post(
            "/api/staff/pausa",
            "{" + listOfNotNull(pausado?.let { "\"pausado\":$it" }, mensagem?.let { "\"mensagem\":\"$it\"" }).joinToString(",") + "}",
        )

    @Test
    fun `pausar para os pedidos dos clientes com o recado, e voltar a receber libera`() {
        val q = apoio.quiosque("Barraca Pausa")
        val app = app(q)
        val token = apoio.novaComanda(q)
        val celular = apoio.celular()
        assertEquals(201, apoio.pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1)).statusCode())

        val r = pausar(app, true, "  Voltamos   em 10 minutos ")

        assertEquals(200, r.statusCode(), r.body())
        assertContains(r.body(), "\"pedidosPausados\":true")
        assertContains(r.body(), "\"mensagemPausa\":\"Voltamos em 10 minutos\"")
        val pagina = celular.get("/api/c/$token").body()
        assertContains(pagina, "\"codigo\":\"pedidos-pausados\"")
        assertContains(pagina, "O quiosque pausou os pedidos: Voltamos em 10 minutos")
        val recusado = apoio.pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1))
        assertEquals(409, recusado.statusCode())
        assertContains(recusado.body(), "\"erro\":\"pedidos-pausados\"")
        assertEquals(1, pedidos.countByBarracaId(q.barracaId), "nenhum pedido novo entrou")
        assertContains(app.get("/api/staff/fila").body(), "\"pedidosPausados\":true,\"mensagemPausa\":\"Voltamos em 10 minutos\"")

        val volta = pausar(app, false)

        assertContains(volta.body(), "\"pedidosPausados\":false")
        assertContains(volta.body(), "\"mensagemPausa\":null")
        assertContains(celular.get("/api/c/$token").body(), "\"bloqueio\":null")
        assertEquals(201, apoio.pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1)).statusCode())
    }

    @Test
    fun `sem recado o cliente le a mensagem padrao, e o recado e limitado a 80 letras`() {
        val q = apoio.quiosque("Barraca Pausa Padrao")
        val app = app(q)
        val token = apoio.novaComanda(q)

        pausar(app, true)
        assertContains(apoio.celular().get("/api/c/$token").body(), "O quiosque pausou os pedidos por agora.")

        pausar(app, true, "   ")
        assertContains(apoio.celular().get("/api/c/$token").body(), "O quiosque pausou os pedidos por agora.")

        pausar(app, true, "x".repeat(300))
        assertEquals(
            80,
            barracas
                .findById(q.barracaId)
                .orElseThrow()
                .mensagemPausa
                ?.length,
        )
    }

    @Test
    fun `pausar avisa na hora o app e so as comandas da propria barraca`() {
        val a = apoio.quiosque("Barraca Pausa Ao Vivo A")
        val b = apoio.quiosque("Barraca Pausa Ao Vivo B")
        val tokenA1 = apoio.novaComanda(a, "Ana")
        val tokenA2 = apoio.novaComanda(a, "Beto")
        val tokenB = apoio.novaComanda(b, "Carol")
        val appA = app(a)

        SseTestClient(appA.http.stream("/api/staff/eventos")).use { quiosqueA ->
            SseTestClient(apoio.celular().stream("/api/c/$tokenA1/eventos")).use { pagina1 ->
                SseTestClient(apoio.celular().stream("/api/c/$tokenA2/eventos")).use { pagina2 ->
                    SseTestClient(apoio.celular().stream("/api/c/$tokenB/eventos")).use { paginaB ->
                        assertEquals(200, pagina1.status)

                        pausar(appA, true, "acabou o gelo")

                        assertNotNull(quiosqueA.esperar("event:fila"), "o app (de outro atendente) vê a pausa")
                        assertNotNull(pagina1.esperar("event:pedido"), "todas as comandas da barraca são avisadas")
                        assertNotNull(pagina2.esperar("event:pedido"))
                        assertTrue(paginaB.nada("event:pedido"), "a barraca B não é afetada")

                        // Repetir a mesma pausa não avisa ninguém de novo
                        pausar(appA, true, "acabou o gelo")
                        assertTrue(pagina1.nada("event:pedido", 800), "nada mudou, nada de aviso")

                        pausar(appA, false)
                        assertNotNull(pagina1.esperar("event:pedido"), "voltar a receber também avisa")
                        assertNotNull(quiosqueA.esperar("event:fila"))
                    }
                }
            }
        }
    }

    @Test
    fun `uma barraca nao pausa a outra`() {
        val a = apoio.quiosque("Barraca Pausa Iso A")
        val b = apoio.quiosque("Barraca Pausa Iso B")
        val tokenA = apoio.novaComanda(a)

        pausar(app(b), true, "só a B")

        assertFalse(barracas.findById(a.barracaId).orElseThrow().pedidosPausados)
        assertTrue(barracas.findById(b.barracaId).orElseThrow().pedidosPausados)
        assertContains(apoio.celular().get("/api/c/$tokenA").body(), "\"bloqueio\":null")
        assertEquals(201, apoio.pedir(apoio.celular(), tokenA, UUID.randomUUID(), Item(a.coco.id, 1)).statusCode())
    }

    @Test
    fun `pausar exige dizer se e para pausar, login e CSRF`() {
        val q = apoio.quiosque("Barraca Pausa Validacao")
        val app = app(q)

        val semCampo = pausar(app, null)
        assertEquals(400, semCampo.statusCode())
        assertContains(semCampo.body(), "\"erro\":\"validacao\"")
        assertEquals(400, app.post("/api/staff/pausa", """{ isto não é json""").statusCode())
        assertFalse(barracas.findById(q.barracaId).orElseThrow().pedidosPausados)

        assertEquals(401, pausar(StaffApp(port), true).statusCode())
        assertEquals(403, app.post("/api/staff/pausa", """{"pausado":true}""", comCsrf = false).statusCode())
        assertNull(barracas.findById(q.barracaId).orElseThrow().mensagemPausa)
    }
}
