package br.com.mesamar.relatorio

import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.common.Fuso
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.staff.StaffApp
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.Item
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.PainelTestSupport.Companion.SENHA
import br.com.mesamar.support.Quiosque
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** O relatório do dia: confere contra os pedidos e pagamentos gerados no teste (Fase 5 · checkpoint 5.3). */
@IntegrationTest
class RelatorioTest(
    @Value("\${local.server.port}") private val port: Int,
    private val convites: ConviteService,
    usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    checkin: CheckinService,
    private val comandas: ComandaRepository,
    private val relatorios: RelatorioService,
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    private val painel = PainelTestSupport(port, convites)
    private val apoio = ClienteTestSupport(port, painel, usuarios, categorias, produtos, checkin)

    private fun hoje() = Fuso.hoje(clock)

    private fun app(q: Quiosque): StaffApp =
        StaffApp(port).also {
            assertEquals(200, it.post("/api/staff/entrar/dono", """{"email":"${q.email}","senha":"$SENHA"}""").statusCode())
        }

    private fun donoNoPainel(q: Quiosque): HttpTestClient = painel.session().also { painel.login(it, q.email) }

    private fun pedido(
        token: String,
        vararg linhas: Item,
    ): UUID {
        val r = apoio.pedir(apoio.celular(), token, UUID.randomUUID(), *linhas)
        assertEquals(201, r.statusCode(), r.body())
        return UUID.fromString(assertNotNull(Regex(""""id":"([0-9a-f-]{36})"""").find(r.body())).groupValues[1])
    }

    private fun idDe(token: String) = comandas.findByTokenAcesso(token)!!.id

    private fun receber(
        app: StaffApp,
        token: String,
        metodo: String,
        valor: Long,
    ) = app
        .post(
            "/api/staff/comandas/${idDe(token)}/pagamentos",
            """{"id":"${UUID.randomUUID()}","metodo":"$metodo","valorCentavos":$valor}""",
        ).also {
            assertEquals(201, it.statusCode(), it.body())
        }

    /** O cenário do dia (tudo hoje). Devolve o quiosque; os números esperados estão nos testes. */
    private fun cenario(nome: String): Quiosque {
        val q = apoio.quiosque(nome)
        val app = app(q)
        val ana = apoio.novaComanda(q, "Ana")
        val beto = apoio.novaComanda(q, "Beto")
        val caio = apoio.novaComanda(q, "Caio")
        val dani = apoio.novaComanda(q, "Dani")

        // Ana: R$ 49,00 pagos em duas formas, pedido entregue em 5 min
        val pedidoAna = pedido(ana, Item(q.coco.id, 2), Item(q.pastel.id, 1))
        receber(app, ana, "PIX", 2000)
        receber(app, ana, "DINHEIRO", 2900)
        // Beto: R$ 50,00 em aberto
        pedido(beto, Item(q.pastel.id, 2))
        // Caio: pediu R$ 37,00, a água de coco acabou (cancelada) e ele foi embora sem pagar os R$ 25,00
        val pedidoCaio = pedido(caio, Item(q.pastel.id, 1), Item(q.coco.id, 1))
        val agua =
            jdbc.queryForObject(
                "SELECT id FROM item_pedido WHERE pedido_id = ? AND nome_snapshot = 'Água de coco'",
                UUID::class.java,
                pedidoCaio,
            )
        assertEquals(200, app.post("/api/staff/pedidos/$pedidoCaio/itens/$agua/cancelar", """{"motivo":"ESGOTOU"}""").statusCode())
        assertEquals(200, app.post("/api/staff/comandas/${idDe(caio)}/fechamento-forcado", """{"motivo":"foi embora"}""").statusCode())
        // Dani: lançada pelo atendente (R$ 25,00), entregue em 10 min, ainda não pagou
        val pedidoDani = pedido(dani, Item(q.pastel.id, 1))
        jdbc.update("UPDATE pedido SET origem = 'ATENDENTE' WHERE id = ?", pedidoDani)
        entregue(pedidoAna, 300)
        entregue(pedidoDani, 600)
        return q
    }

    private fun entregue(
        pedidoId: UUID,
        segundos: Int,
    ) {
        jdbc.update(
            "UPDATE pedido SET status = 'ENTREGUE', entregue_em = criado_em + make_interval(secs => ?) WHERE id = ?",
            segundos,
            pedidoId,
        )
    }

    // ---------- Os números ----------

    @Test
    fun `o relatorio do dia bate com os pedidos e pagamentos gerados`() {
        val q = cenario("Barraca Relatorio")

        val r = relatorios.dia(q.barracaId, hoje())

        assertEquals(4900, r.recebidoCentavos)
        assertEquals(2000, r.porForma.single { it.metodo.name == "PIX" }.centavos)
        assertEquals(2900, r.porForma.single { it.metodo.name == "DINHEIRO" }.centavos)
        assertEquals(0, r.porForma.single { it.metodo.name == "CARTAO" }.centavos)
        assertEquals(
            2,
            r.porForma.single { it.metodo.name == "PIX" }.pagamentos + r.porForma.single { it.metodo.name == "DINHEIRO" }.pagamentos,
        )
        assertEquals(4, r.clientes)
        assertEquals(3, r.pedidosDoCliente)
        assertEquals(1, r.pedidosDoAtendente)
        assertEquals(0, r.pedidosCancelados, "só um item foi cancelado, não um pedido")
        assertEquals(14900, r.vendidoCentavos)
        assertEquals(2, r.entregues)
        assertEquals(450, r.tempoMedioEntregaSegundos, "(5 min + 10 min) / 2")
        assertEquals(listOf("Pastel" to 5, "Água de coco" to 2), r.maisVendidos.map { it.nome to it.quantidade })
        assertEquals(12500, r.maisVendidos.first().centavos)
        assertEquals(listOf(CancelamentoPorMotivo("Esgotou", 1, 1200)), r.cancelamentos)
        assertEquals(listOf("Beto" to 5000L, "Dani" to 2500L), r.emAberto.map { it.cliente to it.saldoCentavos })
        assertEquals(listOf(NaoRecebido("Caio", null, "foi embora", 2500)), r.naoRecebidos)
        // A conta fecha: tudo o que foi vendido foi recebido, está em aberto ou ficou sem receber
        assertEquals(r.vendidoCentavos, r.recebidoCentavos + r.emAbertoCentavos + r.naoRecebidoCentavos)
    }

    @Test
    fun `a pagina mostra os numeros formatados e o CSV traz as mesmas linhas`() {
        val q = cenario("Barraca Relatorio Pagina")
        val dono = donoNoPainel(q)

        val pagina = dono.get("/painel/relatorio")

        assertEquals(200, pagina.statusCode())
        listOf(
            "Relatório do dia",
            "R$ 49,00",
            "R$ 20,00",
            "R$ 29,00",
            "Pastel",
            "Água de coco",
            "Esgotou",
            "Beto",
            "Dani",
            "foi embora",
            "7 min 30 s",
            "R$ 75,00",
        ).forEach { assertContains(pagina.body(), it, message = it) }
        assertContains(pagina.body(), "Baixar em CSV")

        val csv = dono.get("/painel/relatorio.csv?dia=${hoje()}")

        assertEquals(200, csv.statusCode())
        assertContains(csv.headers().firstValue("Content-Disposition").orElse(""), "mesamar-relatorio-${hoje()}.csv")
        assertContains(csv.headers().firstValue("Content-Type").orElse(""), "text/csv")
        val linhas =
            csv
                .body()
                .removePrefix(Char(0xFEFF).toString())
                .lines()
                .filter { it.isNotBlank() }
        assertEquals("Seção;Item;Quantidade;Valor (R\$)", linhas.first())
        assertContains(linhas, "Resumo;Recebido no dia;;49,00")
        assertContains(linhas, "Resumo;Vendido no dia;;149,00")
        assertContains(linhas, "Resumo;Clientes (comandas abertas);4;")
        assertContains(linhas, "Resumo;Pedidos lançados pelo atendente;1;")
        assertContains(linhas, "Resumo;Tempo médio do pedido à entrega (min);7,5;")
        assertContains(linhas, "Recebido por forma;Pix;1;20,00")
        assertContains(linhas, "Recebido por forma;Dinheiro;1;29,00")
        assertContains(linhas, "Mais vendidos;Pastel;5;125,00")
        assertContains(linhas, "Cancelamentos;Esgotou;1;12,00")
        assertContains(linhas, "Em aberto agora;Balcão · Beto (aberta);;50,00")
        assertContains(linhas, "Não recebido;Balcão · Caio: foi embora;;25,00")
    }

    @Test
    fun `outro dia nao mistura os numeros, mas as contas em aberto sao as de agora`() {
        val q = cenario("Barraca Relatorio Outro Dia")
        val ontem = hoje().minusDays(1)

        val r = relatorios.dia(q.barracaId, ontem)

        assertEquals(0, r.recebidoCentavos)
        assertEquals(0, r.clientes)
        assertEquals(0, r.pedidos)
        assertEquals(0, r.vendidoCentavos)
        assertTrue(r.maisVendidos.isEmpty() && r.cancelamentos.isEmpty() && r.naoRecebidos.isEmpty())
        assertEquals(null, r.tempoMedioEntregaSegundos)
        assertEquals(2, r.emAberto.size, "quem deve agora aparece em qualquer dia")
        val pagina = donoNoPainel(q).get("/painel/relatorio?dia=$ontem")
        assertContains(pagina.body(), "Nenhum item vendido neste dia.")
        assertContains(pagina.body(), "sem entregas")
    }

    @Test
    fun `o dia do pagamento e o do fuso da barraca, nao o do UTC`() {
        val q = apoio.quiosque("Barraca Relatorio Fuso")
        val app = app(q)
        val token = apoio.novaComanda(q)
        pedido(token, Item(q.pastel.id, 2))
        receber(app, token, "CARTAO", 2500)
        receber(app, token, "PIX", 2500)
        // Ambos são 01/10 em UTC (02h30 e 03h30), mas em Brasília um é 30/09 às 23h30 e o outro é 01/10 às 00h30
        jdbc.update("UPDATE pagamento SET confirmado_em = '2026-09-30 23:30:00-03' WHERE metodo = 'CARTAO' AND barraca_id = ?", q.barracaId)
        jdbc.update("UPDATE pagamento SET confirmado_em = '2026-10-01 00:30:00-03' WHERE metodo = 'PIX' AND barraca_id = ?", q.barracaId)

        val dia30 = relatorios.dia(q.barracaId, java.time.LocalDate.of(2026, 9, 30))
        val dia1 = relatorios.dia(q.barracaId, java.time.LocalDate.of(2026, 10, 1))

        assertEquals(2500, dia30.recebidoCentavos)
        assertEquals(
            "CARTAO",
            dia30.porForma
                .single { it.centavos > 0 }
                .metodo.name,
        )
        assertEquals(2500, dia1.recebidoCentavos)
        assertEquals(
            "PIX",
            dia1.porForma
                .single { it.centavos > 0 }
                .metodo.name,
        )
    }

    // ---------- A página ----------

    @Test
    fun `data invalida mostra hoje com um aviso, e dia no futuro tambem volta para hoje`() {
        val q = apoio.quiosque("Barraca Relatorio Data")
        val dono = donoNoPainel(q)

        val invalida = dono.get("/painel/relatorio?dia=ontem")
        val futuro = dono.get("/painel/relatorio?dia=2099-01-01")
        val csvFuturo = dono.get("/painel/relatorio.csv?dia=2099-01-01")

        assertContains(invalida.body(), "Não entendemos essa data")
        assertContains(invalida.body(), "Hoje, ")
        assertFalse(futuro.body().contains("Não entendemos"))
        assertContains(futuro.body(), "Hoje, ")
        assertContains(csvFuturo.headers().firstValue("Content-Disposition").orElse(""), "mesamar-relatorio-${hoje()}.csv")
    }

    @Test
    fun `sem entrar no painel nao ha relatorio nem CSV`() {
        val anonimo = painel.anonymous()

        assertEquals(302, anonimo.get("/painel/relatorio").statusCode())
        assertEquals(302, anonimo.get("/painel/relatorio.csv").statusCode())
    }

    @Test
    fun `cada dono ve so a propria barraca`() {
        val a = cenario("Barraca Relatorio Isola A")
        val b = apoio.quiosque("Barraca Relatorio Isola B")
        val tokenB = apoio.novaComanda(b, "Bia")
        pedido(tokenB, Item(b.coco.id, 1))
        val donoB = donoNoPainel(b)

        val pagina = donoB.get("/painel/relatorio").body()
        val csv = donoB.get("/painel/relatorio.csv").body()

        listOf("Ana", "Beto", "Caio", "Dani", "foi embora", "R$ 49,00").forEach {
            assertFalse(pagina.contains(it), "vazou $it: a barraca B não tem isso")
        }
        assertFalse(csv.contains("Ana") || csv.contains("Beto") || csv.contains("foi embora"))
        assertContains(pagina, "R$ 12,00")
        assertContains(csv, "Em aberto agora;Balcão · Bia (aberta);;12,00")
        assertEquals(1, relatorios.dia(b.barracaId, hoje()).emAberto.size)
        assertEquals(2, relatorios.dia(a.barracaId, hoje()).emAberto.size)
    }
}
