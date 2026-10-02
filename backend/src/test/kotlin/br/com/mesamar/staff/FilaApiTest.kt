package br.com.mesamar.staff

import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.barraca.ModoPagamento
import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.pedido.ItemPedidoRepository
import br.com.mesamar.pedido.PedidoRepository
import br.com.mesamar.pedido.StatusPedido
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.Item
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.PainelTestSupport.Companion.SENHA
import br.com.mesamar.support.Quiosque
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A fila do quiosque: ordem de chegada, status só para frente, cancelamento com motivo e isolamento (Fase 4 · 4.1). */
@IntegrationTest
class FilaApiTest(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    checkin: CheckinService,
    private val barracas: BarracaRepository,
    private val pedidos: PedidoRepository,
    private val itens: ItemPedidoRepository,
    private val jdbc: JdbcTemplate,
) {
    private val apoio = ClienteTestSupport(port, PainelTestSupport(port, convites), usuarios, categorias, produtos, checkin)

    private fun app(q: Quiosque): StaffApp =
        StaffApp(port).also {
            assertEquals(200, it.post("/api/staff/entrar/dono", """{"email":"${q.email}","senha":"$SENHA"}""").statusCode())
        }

    /** Faz o pedido pela página do cliente e devolve o id dele. */
    private fun pedido(
        token: String,
        vararg linhas: Item,
    ): UUID {
        val r = apoio.pedir(apoio.celular(), token, UUID.randomUUID(), *linhas)
        assertEquals(201, r.statusCode(), r.body())
        return UUID.fromString(assertNotNull(Regex(""""id":"([0-9a-f-]{36})"""").find(r.body())).groupValues[1])
    }

    private fun mudar(
        app: StaffApp,
        id: UUID,
        status: String,
    ): HttpResponse<String> = app.post("/api/staff/pedidos/$id/status", """{"status":"$status"}""")

    private fun cancelar(
        app: StaffApp,
        id: UUID,
        motivo: String?,
        detalhe: String? = null,
    ): HttpResponse<String> = app.post("/api/staff/pedidos/$id/cancelar", corpoCancelamento(motivo, detalhe))

    private fun cancelarItem(
        app: StaffApp,
        id: UUID,
        itemId: UUID,
        motivo: String?,
        detalhe: String? = null,
    ): HttpResponse<String> = app.post("/api/staff/pedidos/$id/itens/$itemId/cancelar", corpoCancelamento(motivo, detalhe))

    private fun corpoCancelamento(
        motivo: String?,
        detalhe: String?,
    ) = "{" + listOfNotNull(motivo?.let { "\"motivo\":\"$it\"" }, detalhe?.let { "\"detalhe\":\"$it\"" }).joinToString(",") + "}"

    private fun status(id: UUID) = pedidos.findById(id).orElseThrow().status

    // ---------- A fila ----------

    @Test
    fun `a fila mostra so o pendente, por ordem de chegada do servidor`() {
        val q = apoio.quiosque("Barraca Fila Ordem")
        val app = app(q)
        val ana = apoio.novaComanda(q, "Ana")
        val beto = apoio.novaComanda(q, "Beto")
        val p1 = pedido(ana, Item(q.coco.id, 1))
        val p2 = pedido(beto, Item(q.pastel.id, 2, "bem passado"))
        val p3 = pedido(ana, Item(q.pastel.id, 1))

        val fila = app.get("/api/staff/fila").body()

        assertTrue(fila.indexOf("$p1") < fila.indexOf("$p2") && fila.indexOf("$p2") < fila.indexOf("$p3"), fila)
        assertContains(fila, "\"tempoAlertaMin\":10")
        assertContains(fila, "\"cliente\":\"Beto\"")
        assertContains(fila, "\"observacao\":\"bem passado\"")
        assertContains(fila, "\"status\":\"RECEBIDO\"")
        assertNotNull(Regex(""""agora":"\d{4}-\d{2}-\d{2}T""").find(fila), "o horário do servidor vai junto, para o app calcular a espera")

        // O que manda é o horário gravado pelo servidor: um pedido que ficou "preso" e chegou por último não fura a fila
        val cedo = Timestamp.from(Instant.now().minusSeconds(3_600))
        jdbc.update("update pedido set criado_em = ? where id = ?", cedo, p3)
        val reordenada = app.get("/api/staff/fila").body()
        assertTrue(reordenada.indexOf("$p3") < reordenada.indexOf("$p1"), "o mais antigo pelo relógio do servidor vem primeiro")

        // No empate de horário, o número do pedido desempata
        jdbc.update("update pedido set criado_em = ? where id in (?, ?, ?)", cedo, p1, p2, p3)
        val empate = app.get("/api/staff/fila").body()
        assertTrue(empate.indexOf("$p1") < empate.indexOf("$p2") && empate.indexOf("$p2") < empate.indexOf("$p3"), empate)
    }

    @Test
    fun `entregue, cancelado e aguardando pagamento ficam fora da fila`() {
        val q = apoio.quiosque("Barraca Fila Fora")
        val app = app(q)
        val token = apoio.novaComanda(q)
        val entregue = pedido(token, Item(q.coco.id, 1))
        val cancelado = pedido(token, Item(q.coco.id, 1))
        val ativo = pedido(token, Item(q.pastel.id, 1))
        mudar(app, entregue, "ENTREGUE")
        cancelar(app, cancelado, "ESGOTOU")
        val barraca = barracas.findById(q.barracaId).orElseThrow()
        barraca.modoPagamento = ModoPagamento.POR_PEDIDO
        barracas.save(barraca)
        val aguardando = pedido(token, Item(q.coco.id, 1))
        assertEquals(StatusPedido.AGUARDANDO_PAGAMENTO, status(aguardando))

        val fila = app.get("/api/staff/fila").body()

        assertContains(fila, "$ativo")
        assertFalse(fila.contains("$entregue") || fila.contains("$cancelado") || fila.contains("$aguardando"), fila)
    }

    // ---------- Status só para frente ----------

    @Test
    fun `o pedido anda para frente e voltar de entregue nao pode`() {
        val q = apoio.quiosque("Barraca Transicoes")
        val app = app(q)
        val token = apoio.novaComanda(q)
        val id = pedido(token, Item(q.coco.id, 1))

        val preparo = mudar(app, id, "EM_PREPARO")
        assertEquals(200, preparo.statusCode(), preparo.body())
        assertContains(preparo.body(), "\"status\":\"EM_PREPARO\"")
        assertEquals(200, mudar(app, id, "EM_PREPARO").statusCode(), "repetir a mesma ação é inofensivo")
        assertEquals(200, mudar(app, id, "ENTREGUE").statusCode())

        val pedido = pedidos.findById(id).orElseThrow()
        assertNotNull(pedido.emPreparoEm)
        assertNotNull(pedido.entregueEm)
        assertEquals(usuarioDe(q), pedido.atendidoPorUsuarioId)
        assertNull(pedido.atendidoPorAtendenteId)

        val volta = mudar(app, id, "EM_PREPARO")
        assertEquals(409, volta.statusCode())
        assertContains(volta.body(), "\"erro\":\"transicao\"")
        assertContains(volta.body(), "Esse pedido já foi entregue.")
        assertEquals(409, mudar(app, id, "ENTREGUE").let { cancelar(app, id, "ESGOTOU") }.statusCode(), "entregue não se cancela")
        assertEquals(StatusPedido.ENTREGUE, status(id))
        assertFalse(app.get("/api/staff/fila").body().contains("$id"))
    }

    @Test
    fun `do recebido da para pular direto para entregue, e so em preparo ou entregue valem`() {
        val q = apoio.quiosque("Barraca Pular")
        val app = app(q)
        val id = pedido(apoio.novaComanda(q), Item(q.coco.id, 1))

        assertEquals(400, mudar(app, id, "RECEBIDO").statusCode(), "para desfazer, cancele")
        assertEquals(400, mudar(app, id, "CANCELADO").statusCode(), "cancelar tem o seu próprio botão, com motivo")
        assertEquals(400, mudar(app, id, "QUALQUER").statusCode())
        assertEquals(400, app.post("/api/staff/pedidos/$id/status", """{ isto não é json""").statusCode())

        assertEquals(200, mudar(app, id, "ENTREGUE").statusCode())
        assertNull(pedidos.findById(id).orElseThrow().emPreparoEm)
    }

    @Test
    fun `cancelado e final`() {
        val q = apoio.quiosque("Barraca Cancelado Final")
        val app = app(q)
        val id = pedido(apoio.novaComanda(q), Item(q.coco.id, 1))

        assertEquals(200, cancelar(app, id, "CLIENTE_DESISTIU").statusCode())

        val preparo = mudar(app, id, "EM_PREPARO")
        assertEquals(409, preparo.statusCode())
        assertContains(preparo.body(), "Esse pedido foi cancelado.")
        assertEquals(409, mudar(app, id, "ENTREGUE").statusCode())
        assertEquals(200, cancelar(app, id, "CLIENTE_DESISTIU").statusCode(), "cancelar de novo é inofensivo")
        assertEquals(StatusPedido.CANCELADO, status(id))
    }

    @Test
    fun `dois atendentes ao mesmo tempo nunca deixam o pedido entregue e cancelado`() {
        val q = apoio.quiosque("Barraca Corrida Fila")
        val token = apoio.novaComanda(q)
        val pool = Executors.newFixedThreadPool(6)

        repeat(5) {
            val id = pedido(token, Item(q.coco.id, 1))
            val app = app(q)
            val acoes: List<Callable<Pair<String, Int>>> =
                (1..3).flatMap {
                    listOf(
                        Callable { "entregue" to mudar(app, id, "ENTREGUE").statusCode() },
                        Callable { "cancelado" to cancelar(app, id, "ESGOTOU").statusCode() },
                    )
                }
            val resultados = pool.invokeAll(acoes).map { it.get() }

            val final = status(id)
            assertTrue(final == StatusPedido.ENTREGUE || final == StatusPedido.CANCELADO, "$final")
            val perdedor = if (final == StatusPedido.ENTREGUE) "cancelado" else "entregue"
            assertTrue(resultados.filter { it.first == perdedor }.all { it.second == 409 }, "quem perdeu a corrida leva 409: $resultados")
        }
        pool.shutdown()
    }

    // ---------- Cancelar com motivo ----------

    @Test
    fun `cancelar um item exige motivo, tira do saldo e guarda o texto para o cliente`() {
        val q = apoio.quiosque("Barraca Cancela Item")
        val app = app(q)
        val token = apoio.novaComanda(q)
        val id = pedido(token, Item(q.coco.id, 2), Item(q.pastel.id, 1))
        val (coco, pastel) = itens.findAllByPedidoIdOrderByLinha(id)
        assertContains(apoio.celular().get("/api/c/$token/pedidos").body(), "\"saldoCentavos\":4900")

        assertEquals(400, cancelarItem(app, id, pastel.id, null).statusCode(), "o motivo é obrigatório")
        assertEquals(400, cancelarItem(app, id, pastel.id, "NAO_EXISTE").statusCode())
        assertFalse(itens.findById(pastel.id).orElseThrow().cancelado)

        val r = cancelarItem(app, id, pastel.id, "ESGOTOU")

        assertEquals(200, r.statusCode(), r.body())
        assertContains(r.body(), "\"motivoCancelamento\":\"Esgotou\"")
        assertEquals(StatusPedido.RECEBIDO, status(id), "ainda sobrou a água de coco")
        val cliente = apoio.celular().get("/api/c/$token/pedidos").body()
        assertContains(cliente, "\"saldoCentavos\":2400")
        assertContains(cliente, "\"cancelado\":true,\"motivoCancelamento\":\"Esgotou\"")
        assertEquals(200, cancelarItem(app, id, pastel.id, "ESGOTOU").statusCode(), "cancelar de novo é inofensivo")

        // Cancelando a última linha, o pedido todo fica cancelado e sai da fila
        val ultimo = cancelarItem(app, id, coco.id, "OUTRO", "cliente foi embora")
        assertEquals(200, ultimo.statusCode())
        assertContains(ultimo.body(), "\"motivoCancelamento\":\"Outro: cliente foi embora\"")
        val pedido = pedidos.findById(id).orElseThrow()
        assertEquals(StatusPedido.CANCELADO, pedido.status)
        assertEquals("Outro: cliente foi embora", pedido.motivoCancelamento)
        assertNotNull(pedido.canceladoEm)
        assertFalse(app.get("/api/staff/fila").body().contains("$id"))
        assertContains(apoio.celular().get("/api/c/$token/pedidos").body(), "\"saldoCentavos\":0")
    }

    @Test
    fun `cancelar o pedido inteiro cancela os itens, com o motivo, e o texto cabe em 80 letras`() {
        val q = apoio.quiosque("Barraca Cancela Pedido")
        val app = app(q)
        val token = apoio.novaComanda(q)
        val id = pedido(token, Item(q.coco.id, 1), Item(q.pastel.id, 1))

        assertEquals(400, cancelar(app, id, null).statusCode())
        assertEquals(StatusPedido.RECEBIDO, status(id))

        val r = cancelar(app, id, "OUTRO", "x".repeat(200))

        assertEquals(200, r.statusCode())
        val pedido = pedidos.findById(id).orElseThrow()
        assertEquals(StatusPedido.CANCELADO, pedido.status)
        assertEquals(80, assertNotNull(pedido.motivoCancelamento).length)
        assertTrue(itens.findAllByPedidoIdOrderByLinha(id).all { it.cancelado && it.motivoCancelamento == pedido.motivoCancelamento })
        assertContains(apoio.celular().get("/api/c/$token/pedidos").body(), "\"saldoCentavos\":0")
    }

    // ---------- Isolamento ----------

    @Test
    fun `uma barraca nao ve nem mexe nos pedidos de outra`() {
        val a = apoio.quiosque("Barraca Fila Iso A")
        val b = apoio.quiosque("Barraca Fila Iso B")
        val idA = pedido(apoio.novaComanda(a), Item(a.coco.id, 1), Item(a.pastel.id, 1))
        val idA2 = pedido(apoio.novaComanda(a, "Outro"), Item(a.coco.id, 1))
        val appB = app(b)
        val itemA = itens.findAllByPedidoIdOrderByLinha(idA).first().id

        assertFalse(appB.get("/api/staff/fila").body().contains("$idA"), "a fila de B não mostra o pedido de A")
        assertEquals(404, mudar(appB, idA, "ENTREGUE").statusCode())
        assertEquals(404, cancelar(appB, idA, "ESGOTOU").statusCode())
        assertEquals(404, cancelarItem(appB, idA, itemA, "ESGOTOU").statusCode())
        assertEquals(StatusPedido.RECEBIDO, status(idA))
        assertFalse(itens.findById(itemA).orElseThrow().cancelado)

        // Nem com um id de item de outro pedido da mesma barraca
        val appA = app(a)
        assertEquals(404, cancelarItem(appA, idA2, itemA, "ESGOTOU").statusCode())
        assertFalse(itens.findById(itemA).orElseThrow().cancelado)
        assertEquals(200, mudar(appA, idA, "ENTREGUE").statusCode())
    }

    @Test
    fun `sem login nada funciona e as acoes sem CSRF sao recusadas`() {
        val q = apoio.quiosque("Barraca Fila Sem Login")
        val id = pedido(apoio.novaComanda(q), Item(q.coco.id, 1))
        val anonimo = StaffApp(port)

        assertEquals(401, anonimo.get("/api/staff/fila").statusCode())
        assertEquals(401, anonimo.get("/api/staff/eventos").statusCode())
        assertEquals(401, anonimo.post("/api/staff/pedidos/$id/status", """{"status":"ENTREGUE"}""").statusCode())

        val app = app(q)
        assertEquals(403, app.post("/api/staff/pedidos/$id/status", """{"status":"ENTREGUE"}""", comCsrf = false).statusCode())
        assertEquals(403, app.post("/api/staff/pedidos/$id/cancelar", """{"motivo":"ESGOTOU"}""", comCsrf = false).statusCode())
        assertEquals(StatusPedido.RECEBIDO, status(id))
    }

    private fun usuarioDe(q: Quiosque): UUID = q.usuarioId
}
