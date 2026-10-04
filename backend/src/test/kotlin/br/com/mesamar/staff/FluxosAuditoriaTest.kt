package br.com.mesamar.staff

import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.barraca.ModoPagamento
import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.cliente.ConsultaCliente
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.common.AcaoNoAparelho
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.pagamento.FechamentoService
import br.com.mesamar.pagamento.MetodoPagamento
import br.com.mesamar.pagamento.ResultadoConta
import br.com.mesamar.pedido.ItemPedidoRepository
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.Item
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.Quiosque
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@IntegrationTest
class FluxosAuditoriaTest(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    checkin: CheckinService,
    private val comandas: ComandaRepository,
    private val consultas: ConsultaCliente,
    private val fechamento: FechamentoService,
    private val barracas: BarracaRepository,
    private val itens: ItemPedidoRepository,
    private val jdbc: JdbcTemplate,
) {
    private val apoio = ClienteTestSupport(port, PainelTestSupport(port, convites), usuarios, categorias, produtos, checkin)

    private fun dono(q: Quiosque) =
        StaffApp(port).also {
            assertEquals(
                200,
                it.post("/api/staff/entrar/dono", """{"email":"${q.email}","senha":"${PainelTestSupport.SENHA}"}""").statusCode(),
            )
        }

    @Test
    fun `novo checkin antigo e novo pedido antigo sao recusados mas repeticoes confirmadas convergem`() {
        val q = apoio.quiosque("Offline outro dia")
        val app = dono(q)
        val id = UUID.randomUUID()
        val ontem = Instant.now().minusSeconds(172800)
        val base = """"id":"$id","nome":"Ana","consentimento":true"""
        val antigo = """{$base,"criadoNoAparelhoEm":"$ontem"}"""
        val caminho = "/api/staff/clientes"
        assertEquals(400, app.post(caminho, antigo).statusCode())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM cliente WHERE barraca_id=?", Int::class.java, q.barracaId))
        assertEquals(201, app.post(caminho, """{$base,"criadoNoAparelhoEm":"${Instant.now()}"}""").statusCode())
        assertEquals(200, app.post(caminho, antigo).statusCode(), "UUID já gravado confirma a mesma conta")
        val pedidoId = UUID.randomUUID()
        val pedido = apoio.corpo(pedidoId, Item(q.coco.id, 1))
        val velho = pedido.dropLast(1) + """, "criadoNoAparelhoEm":"$ontem"}"""
        assertEquals(400, app.post("/api/staff/comandas/$id/pedidos", velho).statusCode())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pedido WHERE comanda_id=?", Int::class.java, id))
        assertEquals(201, app.post("/api/staff/comandas/$id/pedidos", pedido).statusCode())
        assertEquals(200, app.post("/api/staff/comandas/$id/pedidos", velho).statusCode())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pedido WHERE comanda_id=?", Int::class.java, id))
    }

    @Test
    fun `dia da acao usa Sao Paulo e rejeita relogio muito adiantado`() {
        val clock = Clock.fixed(Instant.parse("2026-10-04T02:59:59Z"), ZoneOffset.UTC)
        assertTrue(AcaoNoAparelho.noDiaAtual(Instant.parse("2026-10-03T03:00:00Z"), clock))
        assertFalse(AcaoNoAparelho.noDiaAtual(Instant.parse("2026-10-03T02:59:59Z"), clock))
        assertFalse(AcaoNoAparelho.noDiaAtual(Instant.parse("2026-10-04T03:00:00Z"), clock))
        assertFalse(
            AcaoNoAparelho.noDiaAtual(
                Instant.parse("2026-10-03T23:00:00Z"),
                Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC),
            ),
        )
    }

    @Test
    fun `link revogado nao fecha nao avisa nem reabre conta com objeto lido antes`() {
        val q = apoio.quiosque("QR financeiro")
        val token = apoio.novaComanda(q)
        assertEquals(201, apoio.pedir(apoio.celular(), token, UUID.randomUUID(), Item(q.coco.id, 1)).statusCode())
        val antiga = assertNotNull(consultas.vigente(token))
        val novo = UUID.randomUUID().toString().replace("-", "")
        jdbc.update("UPDATE comanda SET token_acesso=? WHERE id=?", novo, antiga.id)
        val a = fechamento.fechar(antiga, MetodoPagamento.DINHEIRO, null)
        val b = fechamento.jaPaguei(antiga)
        val c = fechamento.reabrir(antiga)
        listOf(a, b, c).forEach { assertTrue(it is ResultadoConta.Recusado && it.codigo == "link-revogado") }
        assertEquals("ABERTA", jdbc.queryForObject("SELECT status FROM comanda WHERE id=?", String::class.java, antiga.id))
    }

    @Test
    fun `revisao de pedidos nao pagos isola barraca cancela item com motivo e recalcula saldo`() {
        val q = apoio.quiosque("Revisar antes de pagar")
        val outra = apoio.quiosque("Outra barraca")
        barracas.findById(q.barracaId).orElseThrow().also {
            it.modoPagamento = ModoPagamento.POR_PEDIDO
            barracas.save(it)
        }
        val token = apoio.novaComanda(q)
        val id = assertNotNull(comandas.findByTokenAcesso(token)).id
        val pedido = UUID.randomUUID()
        assertEquals(201, apoio.pedir(apoio.celular(), token, pedido, Item(q.coco.id, 1), Item(q.pastel.id, 1)).statusCode())
        val app = dono(q)
        val path = "/api/staff/comandas/$id/pedidos-aguardando"
        assertEquals(401, StaffApp(port).get(path).statusCode())
        assertEquals(404, dono(outra).get(path).statusCode())
        assertContains(app.get(path).body(), pedido.toString())
        val pastel = itens.findAllByPedidoIdOrderByLinha(pedido).first { it.produtoId == q.pastel.id }
        assertEquals(400, app.post("/api/staff/pedidos/$pedido/itens/${pastel.id}/cancelar", "{}").statusCode())
        assertEquals(200, app.post("/api/staff/pedidos/$pedido/itens/${pastel.id}/cancelar", """{"motivo":"ESGOTOU"}""").statusCode())
        assertContains(apoio.celular().get("/api/c/$token/pedidos").body(), "\"saldoCentavos\":1200")
        assertContains(app.get(path).body(), "\"cancelado\":true")
        assertEquals(200, app.post("/api/staff/pedidos/$pedido/cancelar", """{"motivo":"CLIENTE_DESISTIU"}""").statusCode())
        assertEquals("[]", app.get(path).body())
        assertContains(apoio.celular().get("/api/c/$token/pedidos").body(), "\"saldoCentavos\":0")
        assertEquals(q.usuarioId, jdbc.queryForObject("SELECT atendido_por_usuario_id FROM pedido WHERE id=?", UUID::class.java, pedido))
    }
}
