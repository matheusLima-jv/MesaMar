package br.com.mesamar.staff

import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.comanda.ComandaRepository
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
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Correções da revisão de código, lote 2: o que mudou no servidor (itens 33 e 38). */
@IntegrationTest
class CorrecoesRevisao2Test(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    checkin: CheckinService,
    private val comandas: ComandaRepository,
    private val pedidos: PedidoRepository,
) {
    private val apoio = ClienteTestSupport(port, PainelTestSupport(port, convites), usuarios, categorias, produtos, checkin)

    private fun app(q: Quiosque): StaffApp =
        StaffApp(port).also {
            assertEquals(200, it.post("/api/staff/entrar/dono", """{"email":"${q.email}","senha":"$SENHA"}""").statusCode())
        }

    private fun idDe(token: String): UUID = comandas.findByTokenAcesso(token)!!.id

    private fun idDoPedido(body: String): String = assertNotNull(Regex(""""id":"([0-9a-f-]{36})"""").find(body)).groupValues[1]

    // ---------- Item 33: o cliente chamou um atendente enquanto a página reenviava ----------

    @Test
    fun `reenvio do cliente igual ao pedido que a equipe lancou devolve o pedido da equipe, sem duplicar`() {
        val q = apoio.quiosque("Barraca Revisao Reenvio")
        val token = apoio.novaComanda(q)
        val equipe =
            app(
                q,
            ).post("/api/staff/comandas/${idDe(token)}/pedidos", apoio.corpo(UUID.randomUUID(), Item(q.coco.id, 2), Item(q.pastel.id, 1)))
        assertEquals(201, equipe.statusCode(), equipe.body())

        // A página do cliente, que tentava desde antes, finalmente chega (mesmos itens, em outra ordem e com observação)
        val corpo =
            """{"id":"${UUID.randomUUID()}","reenvio":true,"itens":[{"produtoId":"${q.pastel.id}","quantidade":1},""" +
                """{"produtoId":"${q.coco.id}","quantidade":1,"observacao":"gelada"},{"produtoId":"${q.coco.id}","quantidade":1}]}"""
        val r = apoio.celular().postJson("/api/c/$token/pedidos", corpo)

        assertEquals(200, r.statusCode(), r.body())
        assertEquals(idDoPedido(equipe.body()), idDoPedido(r.body()))
        assertEquals(1, pedidos.findAllByComandaIdOrderByCriadoEmAscNumeroPedidoAsc(idDe(token)).size)
    }

    @Test
    fun `pedido igual que chega na primeira tentativa ou com itens diferentes vira pedido novo`() {
        val q = apoio.quiosque("Barraca Revisao Reenvio 2")
        val token = apoio.novaComanda(q)
        app(q).post("/api/staff/comandas/${idDe(token)}/pedidos", apoio.corpo(UUID.randomUUID(), Item(q.coco.id, 2)))
        val celular = apoio.celular()

        // O cliente quer mais duas águas, de propósito: a primeira tentativa nunca é confundida
        assertEquals(
            201,
            celular
                .postJson(
                    "/api/c/$token/pedidos",
                    """{"id":"${UUID.randomUUID()}","itens":[{"produtoId":"${q.coco.id}","quantidade":2}]}""",
                ).statusCode(),
        )
        // Reenvio, mas com outra quantidade: não é o mesmo pedido
        assertEquals(
            201,
            celular
                .postJson(
                    "/api/c/$token/pedidos",
                    """{"id":"${UUID.randomUUID()}","reenvio":true,"itens":[{"produtoId":"${q.coco.id}","quantidade":3}]}""",
                ).statusCode(),
        )
        assertEquals(3, pedidos.findAllByComandaIdOrderByCriadoEmAscNumeroPedidoAsc(idDe(token)).size)
    }

    @Test
    fun `reenvio nao reconhece pedido da equipe cancelado nem de outra comanda`() {
        val q = apoio.quiosque("Barraca Revisao Reenvio 3")
        val tokenAna = apoio.novaComanda(q, "Ana")
        val tokenBia = apoio.novaComanda(q, "Bia")
        val app = app(q)
        val daBia = app.post("/api/staff/comandas/${idDe(tokenBia)}/pedidos", apoio.corpo(UUID.randomUUID(), Item(q.coco.id, 1)))
        val cancelado = app.post("/api/staff/comandas/${idDe(tokenAna)}/pedidos", apoio.corpo(UUID.randomUUID(), Item(q.coco.id, 1)))
        app.post("/api/staff/pedidos/${idDoPedido(cancelado.body())}/cancelar", """{"motivo":"ESGOTOU"}""")

        val r =
            apoio.celular().postJson(
                "/api/c/$tokenAna/pedidos",
                """{"id":"${UUID.randomUUID()}","reenvio":true,"itens":[{"produtoId":"${q.coco.id}","quantidade":1}]}""",
            )

        assertEquals(201, r.statusCode(), r.body())
        assertTrue(idDoPedido(r.body()) != idDoPedido(daBia.body()))
    }

    // ---------- Item 38: mudança no cardápio chega aos aparelhos abertos ----------

    @Test
    fun `marcar esgotado avisa o app e as paginas abertas da barraca, e so dela`() {
        val a = apoio.quiosque("Barraca Revisao Cardapio A")
        val b = apoio.quiosque("Barraca Revisao Cardapio B")
        val tokenA = apoio.novaComanda(a)
        val tokenB = apoio.novaComanda(b)
        val appA = app(a)
        SseTestClient(appA.http.stream("/api/staff/eventos")).use { quiosqueA ->
            SseTestClient(app(b).http.stream("/api/staff/eventos")).use { quiosqueB ->
                SseTestClient(apoio.celular().stream("/api/c/$tokenA/eventos")).use { paginaA ->
                    SseTestClient(apoio.celular().stream("/api/c/$tokenB/eventos")).use { paginaB ->
                        listOf(quiosqueA, quiosqueB, paginaA, paginaB).forEach { assertEquals(200, it.status) }

                        assertEquals(200, appA.post("/api/staff/produtos/${a.coco.id}/esgotado", """{"esgotado":true}""").statusCode())

                        assertNotNull(quiosqueA.esperar("event:cardapio"), "o app da barraca A recarrega o cardápio")
                        assertNotNull(paginaA.esperar("event:cardapio"), "a página aberta do cliente da A também")
                        assertTrue(quiosqueB.nada("event:cardapio"))
                        assertTrue(paginaB.nada("event:cardapio"))
                    }
                }
            }
        }
        // E a página que buscar o cardápio de novo já vê o esgotado
        assertContains(apoio.celular().get("/api/c/$tokenA").body(), "\"esgotado\":true")
    }
}
