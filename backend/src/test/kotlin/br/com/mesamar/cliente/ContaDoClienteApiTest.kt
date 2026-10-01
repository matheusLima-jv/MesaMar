package br.com.mesamar.cliente

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
import br.com.mesamar.support.Quiosque
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Meus pedidos", saldo, limite de saldo e o modo "pagar a cada pedido" (Fase 3 · checkpoint 3.3). */
@IntegrationTest
class ContaDoClienteApiTest(
    @Value("\${local.server.port}") port: Int,
    convites: ConviteService,
    usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    checkin: CheckinService,
    private val barracas: BarracaRepository,
    private val pedidos: PedidoRepository,
    private val itens: ItemPedidoRepository,
) {
    private val apoio = ClienteTestSupport(port, PainelTestSupport(port, convites), usuarios, categorias, produtos, checkin)

    private fun regras(
        q: Quiosque,
        limiteCentavos: Long? = null,
        modo: ModoPagamento = ModoPagamento.FINAL,
    ) {
        val barraca = barracas.findById(q.barracaId).orElseThrow()
        barraca.limiteSaldoAbertoCentavos = limiteCentavos
        barraca.modoPagamento = modo
        barracas.save(barraca)
    }

    // ---------- Meus pedidos e saldo ----------

    @Test
    fun `meus pedidos mostra so os da propria comanda, do mais novo para o mais antigo, com o saldo`() {
        val q = apoio.quiosque("Barraca Meus Pedidos")
        val outra = apoio.quiosque("Barraca De Outro")
        val ana = apoio.novaComanda(q, "Ana")
        val beto = apoio.novaComanda(q, "Beto")
        val carol = apoio.novaComanda(outra, "Carol")
        val celular = apoio.celular()

        assertEquals(1, apoio.numero(apoio.pedir(celular, ana, UUID.randomUUID(), Item(q.coco.id, 2, "sem gelo")).body()))
        assertEquals(2, apoio.numero(apoio.pedir(celular, beto, UUID.randomUUID(), Item(q.pastel.id, 1)).body()))
        assertEquals(3, apoio.numero(apoio.pedir(celular, ana, UUID.randomUUID(), Item(q.pastel.id, 1)).body()))
        apoio.pedir(celular, carol, UUID.randomUUID(), Item(outra.coco.id, 7))

        val r = celular.get("/api/c/$ana/pedidos")

        assertEquals(200, r.statusCode(), r.body())
        assertContains(r.body(), "\"saldoCentavos\":4900")
        assertTrue(r.body().indexOf("\"numero\":3") < r.body().indexOf("\"numero\":1"), "o mais novo vem primeiro: ${r.body()}")
        assertFalse(r.body().contains("\"numero\":2"), "o pedido do Beto não aparece para a Ana")
        assertContains(r.body(), "\"observacao\":\"sem gelo\"")
        assertContains(r.body(), "\"status\":\"RECEBIDO\"")
        assertContains(r.headers().firstValue("Cache-Control").orElse(""), "no-store")
        assertTrue(r.headers().firstValue("Set-Cookie").isEmpty)
        assertContains(celular.get("/api/c/$beto/pedidos").body(), "\"saldoCentavos\":2500")
        assertContains(celular.get("/api/c/$carol/pedidos").body(), "\"saldoCentavos\":8400")
        assertEquals(404, celular.get("/api/c/${"Z".repeat(22)}/pedidos").statusCode())
    }

    @Test
    fun `uma comanda sem pedidos tem lista vazia e saldo zero`() {
        val q = apoio.quiosque("Barraca Sem Pedidos")
        val token = apoio.novaComanda(q)

        assertEquals("""{"saldoCentavos":0,"pedidos":[]}""", apoio.celular().get("/api/c/$token/pedidos").body())
    }

    @Test
    fun `o saldo ignora item cancelado e pedido cancelado, e a pagina de abertura mostra o mesmo valor`() {
        val q = apoio.quiosque("Barraca Saldo")
        val token = apoio.novaComanda(q)
        val celular = apoio.celular()
        val p1 = UUID.randomUUID()
        val p2 = UUID.randomUUID()
        apoio.pedir(celular, token, p1, Item(q.coco.id, 1), Item(q.pastel.id, 1))
        apoio.pedir(celular, token, p2, Item(q.pastel.id, 1))
        assertContains(celular.get("/api/c/$token").body(), "\"saldoCentavos\":6200")

        itens.findAllByPedidoIdOrderByLinha(p1).first { it.nomeSnapshot == "Pastel" }.let {
            it.cancelado = true
            itens.save(it)
        }
        assertContains(celular.get("/api/c/$token/pedidos").body(), "\"saldoCentavos\":3700")

        pedidos.save(pedidos.findById(p2).orElseThrow().also { it.status = StatusPedido.CANCELADO })
        assertContains(celular.get("/api/c/$token/pedidos").body(), "\"saldoCentavos\":1200")
        assertContains(celular.get("/api/c/$token").body(), "\"saldoCentavos\":1200")
    }

    // ---------- Limite de saldo ----------

    @Test
    fun `limite de saldo atingido recusa pedido novo ate a conta baixar`() {
        val q = apoio.quiosque("Barraca Limite Saldo")
        regras(q, limiteCentavos = 3000)
        val token = apoio.novaComanda(q)
        val celular = apoio.celular()
        val primeiro = UUID.randomUUID()
        val segundo = UUID.randomUUID()

        assertEquals(201, apoio.pedir(celular, token, primeiro, Item(q.coco.id, 2)).statusCode(), "saldo 0: pode")
        assertEquals(201, apoio.pedir(celular, token, segundo, Item(q.coco.id, 1)).statusCode(), "saldo 24,00 ainda está abaixo de 30,00")

        val recusado = apoio.pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1))
        assertEquals(409, recusado.statusCode())
        assertContains(recusado.body(), "\"erro\":\"limite-saldo\"")
        assertContains(recusado.body(), "R$ 30,00")
        assertContains(celular.get("/api/c/$token").body(), "\"bloqueio\":{\"codigo\":\"limite-saldo\"")
        assertEquals(2, pedidos.countByBarracaId(q.barracaId))

        assertEquals(
            200,
            apoio.pedir(celular, token, segundo, Item(q.coco.id, 1)).statusCode(),
            "reenviar um pedido que já existe continua valendo",
        )

        // O dono cancela o item do primeiro pedido: a conta baixa e dá para pedir de novo
        itens.findAllByPedidoIdOrderByLinha(primeiro).single().let {
            it.cancelado = true
            itens.save(it)
        }
        assertEquals(201, apoio.pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1)).statusCode())

        regras(q, limiteCentavos = null)
        assertEquals(201, apoio.pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 20)).statusCode(), "sem limite, sem bloqueio")
    }

    @Test
    fun `o limite vale por comanda`() {
        val q = apoio.quiosque("Barraca Limite Por Comanda")
        regras(q, limiteCentavos = 1000)
        val ana = apoio.novaComanda(q, "Ana")
        val beto = apoio.novaComanda(q, "Beto")
        val celular = apoio.celular()

        assertEquals(201, apoio.pedir(celular, ana, UUID.randomUUID(), Item(q.coco.id, 1)).statusCode())
        assertEquals(409, apoio.pedir(celular, ana, UUID.randomUUID(), Item(q.coco.id, 1)).statusCode())
        assertEquals(201, apoio.pedir(celular, beto, UUID.randomUUID(), Item(q.coco.id, 1)).statusCode(), "a conta do Beto está vazia")
    }

    @Test
    fun `pedidos ao mesmo tempo nao furam o limite de saldo`() {
        val q = apoio.quiosque("Barraca Limite Corrida")
        regras(q, limiteCentavos = 2500)
        val token = apoio.novaComanda(q)
        val pool = Executors.newFixedThreadPool(6)

        val respostas =
            try {
                pool
                    .invokeAll(
                        (1..6).map { Callable { apoio.pedir(apoio.celular(), token, UUID.randomUUID(), Item(q.pastel.id, 1)) } },
                    ).map { it.get() }
            } finally {
                pool.shutdown()
            }

        assertEquals(1, respostas.count { it.statusCode() == 201 }, respostas.map { it.statusCode() }.toString())
        assertEquals(5, respostas.count { it.statusCode() == 409 && it.body().contains("limite-saldo") })
        assertEquals(1, pedidos.countByBarracaId(q.barracaId))
    }

    // ---------- Pagar a cada pedido ----------

    @Test
    fun `no modo pagar a cada pedido o pedido espera o pagamento antes de entrar na fila`() {
        val q = apoio.quiosque("Barraca Por Pedido")
        regras(q, modo = ModoPagamento.POR_PEDIDO)
        val token = apoio.novaComanda(q)
        val celular = apoio.celular()
        val id = UUID.randomUUID()

        val r = apoio.pedir(celular, token, id, Item(q.coco.id, 2))

        assertEquals(201, r.statusCode(), r.body())
        assertContains(r.body(), "\"status\":\"AGUARDANDO_PAGAMENTO\"")
        assertEquals(StatusPedido.AGUARDANDO_PAGAMENTO, pedidos.findById(id).orElseThrow().status)
        assertContains(celular.get("/api/c/$token").body(), "\"modoPagamento\":\"POR_PEDIDO\"")
        val lista = celular.get("/api/c/$token/pedidos").body()
        assertContains(lista, "\"status\":\"AGUARDANDO_PAGAMENTO\"")
        assertContains(lista, "\"saldoCentavos\":2400")
        assertEquals(200, apoio.pedir(celular, token, id, Item(q.coco.id, 2)).statusCode(), "o reenvio devolve o mesmo pedido")
        assertEquals(1, pedidos.countByBarracaId(q.barracaId))

        regras(q, modo = ModoPagamento.FINAL)
        val normal = apoio.pedir(celular, token, UUID.randomUUID(), Item(q.pastel.id, 1))
        assertContains(normal.body(), "\"status\":\"RECEBIDO\"")
        assertEquals(2, apoio.numero(normal.body()), "o pedido que espera pagamento também ganha número")
    }

    @Test
    fun `por padrao o pedido entra direto como recebido`() {
        val q = apoio.quiosque("Barraca Pagamento Final")
        val token = apoio.novaComanda(q)

        val r = apoio.pedir(apoio.celular(), token, UUID.randomUUID(), Item(q.coco.id, 1))

        assertContains(r.body(), "\"status\":\"RECEBIDO\"")
        assertContains(apoio.celular().get("/api/c/$token").body(), "\"modoPagamento\":\"FINAL\"")
    }
}
