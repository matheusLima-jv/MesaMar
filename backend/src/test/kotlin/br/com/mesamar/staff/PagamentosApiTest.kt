package br.com.mesamar.staff

import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.barraca.ModoPagamento
import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.comanda.StatusComanda
import br.com.mesamar.common.TipoChavePix
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.equipe.AtendenteRepository
import br.com.mesamar.pagamento.PagamentoRepository
import br.com.mesamar.pedido.ItemPedidoRepository
import br.com.mesamar.pedido.PedidoRepository
import br.com.mesamar.pedido.StatusPedido
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.Item
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.PainelTestSupport.Companion.SENHA
import br.com.mesamar.support.Quiosque
import br.com.mesamar.support.SseTestClient
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A aba Pagamentos do quiosque: receber (total, parcial, dividido), bloquear, encerrar e fechar sem receber (Fase 5 · 5.2). */
@IntegrationTest
class PagamentosApiTest(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    private val usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    checkin: CheckinService,
    private val barracas: BarracaRepository,
    private val comandas: ComandaRepository,
    private val pagamentos: PagamentoRepository,
    private val pedidos: PedidoRepository,
    private val itens: ItemPedidoRepository,
    private val atendentes: AtendenteRepository,
    private val jdbc: JdbcTemplate,
) {
    private val painel = PainelTestSupport(port, convites)
    private val apoio = ClienteTestSupport(port, painel, usuarios, categorias, produtos, checkin)

    private fun app(q: Quiosque): StaffApp =
        StaffApp(port).also {
            assertEquals(200, it.post("/api/staff/entrar/dono", """{"email":"${q.email}","senha":"$SENHA"}""").statusCode())
        }

    /** A atendente Carla (PIN 258014) entra no app da barraca. */
    private fun carla(q: Quiosque): StaffApp {
        val http = painel.session().also { painel.login(it, q.email) }
        painel.post(http, "/painel/equipe", "/painel/equipe", mapOf("nome" to "Carla", "pin" to "258014"))
        http.get("/painel/equipe")
        val codigo =
            assertNotNull(
                Regex("""<span class="mono">([a-z0-9-]+)</span>""").find(http.get("/painel/barraca").body()),
            ).groupValues[1]
        val atendente = atendentes.findAllByBarracaIdOrderByNome(q.barracaId).first { it.nome == "Carla" }
        return StaffApp(port).also {
            val r = it.post("/api/staff/entrar/atendente", """{"codigo":"$codigo","atendenteId":"${atendente.id}","pin":"258014"}""")
            assertEquals(200, r.statusCode(), r.body())
        }
    }

    private fun idDe(token: String): UUID = comandas.findByTokenAcesso(token)!!.id

    private fun statusDe(token: String) = comandas.findByTokenAcesso(token)!!.status

    private fun pedido(
        token: String,
        vararg linhas: Item,
    ): UUID {
        val r = apoio.pedir(apoio.celular(), token, UUID.randomUUID(), *linhas)
        assertEquals(201, r.statusCode(), r.body())
        return UUID.fromString(assertNotNull(Regex(""""id":"([0-9a-f-]{36})"""").find(r.body())).groupValues[1])
    }

    private fun fechar(
        token: String,
        corpo: String,
    ) = apoio.celular().postJson("/api/c/$token/conta/fechar", corpo).also { assertEquals(200, it.statusCode(), it.body()) }

    private fun receber(
        app: StaffApp,
        token: String,
        metodo: String?,
        valor: Long?,
        recebido: Long? = null,
        id: UUID? = UUID.randomUUID(),
    ): HttpResponse<String> = receberPorId(app, idDe(token), metodo, valor, recebido, id)

    private fun receberPorId(
        app: StaffApp,
        comandaId: UUID,
        metodo: String?,
        valor: Long?,
        recebido: Long? = null,
        id: UUID? = UUID.randomUUID(),
    ): HttpResponse<String> {
        val campos =
            listOfNotNull(
                id?.let { "\"id\":\"$it\"" },
                metodo?.let { "\"metodo\":\"$it\"" },
                valor?.let { "\"valorCentavos\":$it" },
                recebido?.let { "\"valorRecebidoCentavos\":$it" },
            )
        return app.post("/api/staff/comandas/$comandaId/pagamentos", campos.joinToString(",", "{", "}"))
    }

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

    private fun comPix(q: Quiosque) {
        val barraca = barracas.findById(q.barracaId).orElseThrow()
        barraca.tipoChavePix = TipoChavePix.ALEATORIA
        barraca.chavePix = "123e4567-e12b-12d1-a456-426655440000"
        barraca.nomeRecebedorPix = "FULANO DE TAL"
        barraca.cidadePix = "BRASILIA"
        barracas.save(barraca)
    }

    @Test
    fun `equipe continua conta parcial preservando pagamento saldo isolamento e historico`() {
        val q = apoio.quiosque("Barraca Continuar Parcial")
        val outra = apoio.quiosque("Barraca Continuar Outra")
        val token = apoio.novaComanda(q)
        pedido(token, Item(q.coco.id, 2))
        fechar(token, """{"metodo":"CARTAO"}""")
        val equipe = carla(q)
        receber(equipe, token, "CARTAO", 1000).also { assertEquals(201, it.statusCode(), it.body()) }
        val caminho = "/api/staff/comandas/${idDe(token)}/continuar"
        assertEquals(404, app(outra).post(caminho, "{}").statusCode())
        val resposta = equipe.post(caminho, "{}")
        assertEquals(200, resposta.statusCode(), resposta.body())
        assertContains(resposta.body(), "\"status\":\"ABERTA\"")
        assertContains(resposta.body(), "\"pagoCentavos\":1000")
        assertContains(resposta.body(), "\"saldoCentavos\":1400")
        assertNull(comandas.findByTokenAcesso(token)!!.metodoPagamentoPretendido)
        assertEquals(200, equipe.post(caminho, "{}").statusCode())
        assertEquals(1000, pagamentos.totalCentavos(idDe(token)))
        assertTrue(
            jdbc.queryForObject(
                """SELECT count(*) FROM historico_acao WHERE entidade_id = ? AND entidade = 'comanda'
                    AND antes->>'status' = 'AGUARDANDO_PAGAMENTO' AND depois->>'status' = 'ABERTA'
                    AND atendente_id IS NOT NULL""",
                Long::class.java,
                idDe(token),
            )!! > 0,
        )
        regras(q, limiteCentavos = 1000)
        assertEquals(409, apoio.pedir(apoio.celular(), token, UUID.randomUUID(), Item(q.coco.id, 1)).statusCode())
    }

    @Test
    fun `continuar recusa conta sem pagamento bloqueada fechada e de outro dia`() {
        val q = apoio.quiosque("Barraca Continuar Estados")
        val token = apoio.novaComanda(q)
        pedido(token, Item(q.coco.id, 2))
        fechar(token, """{"metodo":"CARTAO"}""")
        val equipe = app(q)
        val caminho = "/api/staff/comandas/${idDe(token)}/continuar"
        assertEquals(409, equipe.post(caminho, "{}").statusCode())
        receber(equipe, token, "CARTAO", 1000)
        listOf(StatusComanda.BLOQUEADA, StatusComanda.FECHADA, StatusComanda.CANCELADA).forEach { status ->
            comandas.save(comandas.findByTokenAcesso(token)!!.also { it.status = status })
            assertEquals(409, equipe.post(caminho, "{}").statusCode())
        }
        jdbc.update("UPDATE comanda SET status = 'AGUARDANDO_PAGAMENTO', dia = dia - 1 WHERE id = ?", idDe(token))
        assertEquals(409, equipe.post(caminho, "{}").statusCode())
    }

    // ---------- A lista ----------

    @Test
    fun `a aba lista quem esta para pagar por ordem de aviso e as contas abertas com saldo, so da propria barraca`() {
        val q = apoio.quiosque("Barraca Lista Pagamentos")
        val outra = apoio.quiosque("Barraca Lista Outra")
        val ana = apoio.novaComanda(q, "Ana")
        val beto = apoio.novaComanda(q, "Beto")
        val caio = apoio.novaComanda(q, "Caio")
        apoio.novaComanda(q, "Dani")
        val eva = apoio.novaComanda(outra, "Eva")
        pedido(ana, Item(q.coco.id, 1))
        pedido(beto, Item(q.pastel.id, 2))
        pedido(caio, Item(q.pastel.id, 1))
        pedido(eva, Item(outra.pastel.id, 4))
        // Beto avisa primeiro e Ana depois, e o Caio fica só com a conta aberta
        fechar(beto, """{"metodo":"DINHEIRO","trocoParaCentavos":10000}""")
        Thread.sleep(20)
        fechar(ana, """{"metodo":"CARTAO"}""")

        val r = app(q).get("/api/staff/pagamentos").body()

        val aguardando = r.substringAfter("\"aguardando\":[").substringBefore("],\"abertas\"")
        assertTrue(
            aguardando.indexOf("\"cliente\":\"Beto\"") in 0 until aguardando.indexOf("\"cliente\":\"Ana\""),
            "por ordem de aviso: $r",
        )
        assertContains(aguardando, "\"saldoCentavos\":5000")
        assertContains(aguardando, "\"trocoParaCentavos\":10000")
        assertContains(aguardando, "\"metodo\":\"CARTAO\"")
        val abertas = r.substringAfter("\"abertas\":[")
        assertContains(abertas, "\"cliente\":\"Caio\"")
        assertFalse(abertas.contains("\"cliente\":\"Dani\""), "conta sem saldo não aparece entre as abertas")
        assertFalse(r.contains("Eva"), "a comanda de outra barraca não aparece")
        assertContains(r, "\"agora\":\"")
    }

    // ---------- Receber ----------

    @Test
    fun `receber o total em dinheiro devolve o troco e encerra a conta que estava fechando`() {
        val q = apoio.quiosque("Barraca Recebe Total")
        val app = app(q)
        val token = apoio.novaComanda(q, "Ana")
        pedido(token, Item(q.coco.id, 1), Item(q.pastel.id, 1))
        fechar(token, """{"metodo":"DINHEIRO","trocoParaCentavos":5000}""")

        val r = receber(app, token, "DINHEIRO", 3700, recebido = 5000)

        assertEquals(201, r.statusCode(), r.body())
        assertContains(r.body(), "\"trocoCentavos\":1300")
        assertContains(r.body(), "\"fechada\":true")
        assertContains(r.body(), "\"saldoCentavos\":0")
        val comanda = comandas.findByTokenAcesso(token)!!
        assertEquals(StatusComanda.FECHADA, comanda.status)
        assertNotNull(comanda.fechadaEm)
        assertNull(comanda.metodoPagamentoPretendido)
        assertNull(comanda.avisoPagamentoEm)
        assertEquals(1, pagamentos.findAllByComandaIdOrderByConfirmadoEmAsc(comanda.id).size)
        assertEquals(q.usuarioId, pagamentos.findAllByComandaIdOrderByConfirmadoEmAsc(comanda.id).single().confirmadoPorUsuarioId)

        // O cliente vê a conta paga e não pede mais
        val celular = apoio.celular()
        val extrato = celular.get("/api/c/$token/conta").body()
        assertContains(extrato, "\"status\":\"FECHADA\"")
        assertContains(extrato, "\"rotulo\":\"Dinheiro\"")
        assertContains(extrato, "\"valorRecebidoCentavos\":5000")
        assertContains(extrato, "\"saldoCentavos\":0")
        assertContains(apoio.pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1)).body(), "comanda-fechada")
        assertFalse(app.get("/api/staff/pagamentos").body().contains("Ana"), "sai da aba Pagamentos")
    }

    @Test
    fun `sem informar quanto o cliente entregou, o dinheiro e exato e nao ha troco`() {
        val q = apoio.quiosque("Barraca Recebe Exato")
        val token = apoio.novaComanda(q)
        pedido(token, Item(q.coco.id, 1))

        val r = receber(app(q), token, "DINHEIRO", 1200)

        assertEquals(201, r.statusCode(), r.body())
        assertContains(r.body(), "\"trocoCentavos\":0")
    }

    @Test
    fun `receber uma parte mantem a conta, abate o saldo e libera o limite de saldo`() {
        val q = apoio.quiosque("Barraca Recebe Parcial")
        regras(q, limiteCentavos = 3000)
        val app = app(q)
        val token = apoio.novaComanda(q)
        val celular = apoio.celular()
        pedido(token, Item(q.pastel.id, 2))
        assertEquals(409, apoio.pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1)).statusCode(), "saldo R$ 50,00 passa do limite")

        val r = receber(app, token, "PIX", 2500)

        assertEquals(201, r.statusCode(), r.body())
        assertContains(r.body(), "\"fechada\":false")
        assertContains(r.body(), "\"saldoCentavos\":2500")
        assertContains(r.body(), "\"status\":\"ABERTA\"")
        assertEquals(StatusComanda.ABERTA, statusDe(token))
        assertContains(celular.get("/api/c/$token/conta").body(), "\"pagoCentavos\":2500")
        assertEquals(201, apoio.pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1)).statusCode(), "o saldo baixou do limite")
    }

    @Test
    fun `pagamento dividido em varias formas so encerra a conta quando o saldo zera, e o Pix acompanha o saldo`() {
        val q = apoio.quiosque("Barraca Recebe Dividido")
        comPix(q)
        val app = app(q)
        val token = apoio.novaComanda(q)
        pedido(token, Item(q.coco.id, 1), Item(q.pastel.id, 2))
        val celular = apoio.celular()
        fechar(token, """{"metodo":"PIX"}""")

        assertContains(receber(app, token, "PIX", 2000).body(), "\"fechada\":false")
        assertEquals(StatusComanda.AGUARDANDO_PAGAMENTO, statusDe(token))
        assertContains(celular.get("/api/c/$token/conta").body(), "540542.00", message = "o Pix passa a cobrar só o que falta, R$ 42,00")

        assertContains(receber(app, token, "CARTAO", 2000).body(), "\"fechada\":false")
        val ultimo = receber(app, token, "DINHEIRO", 2200, recebido = 2500)

        assertContains(ultimo.body(), "\"fechada\":true", message = ultimo.body())
        assertContains(ultimo.body(), "\"trocoCentavos\":300")
        assertEquals(StatusComanda.FECHADA, statusDe(token))
        assertEquals(3, pagamentos.findAllByComandaIdOrderByConfirmadoEmAsc(idDe(token)).size)
        assertEquals(6200, pagamentos.totalCentavos(idDe(token)))
    }

    @Test
    fun `recusa valor, forma e dinheiro recebido que nao fazem sentido`() {
        val q = apoio.quiosque("Barraca Recebe Valida")
        val app = app(q)
        val token = apoio.novaComanda(q)
        assertEquals(409, receber(app, token, "DINHEIRO", 1000).statusCode(), "conta zerada")
        pedido(token, Item(q.coco.id, 1))

        listOf(
            receber(app, token, null, 1200) to "metodo",
            receber(app, token, "BOLETO", 1200) to "metodo",
            receber(app, token, "PIX", null) to "valorCentavos",
            receber(app, token, "PIX", 0) to "valorCentavos",
            receber(app, token, "PIX", -100) to "valorCentavos",
            receber(app, token, "PIX", 1201) to "valorCentavos",
            receber(app, token, "DINHEIRO", 1200, recebido = 1000) to "valorRecebidoCentavos",
            receber(app, token, "DINHEIRO", 1200, recebido = 2_000_000) to "valorRecebidoCentavos",
            receber(app, token, "PIX", 1200, id = null) to "id",
        ).forEach { (r, campo) ->
            assertEquals(400, r.statusCode(), r.body())
            assertContains(r.body(), "\"$campo\"")
        }
        assertEquals(400, app.post("/api/staff/comandas/${idDe(token)}/pagamentos", """{"valorCentavos":"abc"}""").statusCode())
        assertEquals(0, pagamentos.totalCentavos(idDe(token)), "nada foi registrado")
        assertEquals(StatusComanda.ABERTA, statusDe(token))
    }

    @Test
    fun `receber de uma comanda encerrada ou cancelada e recusado`() {
        val q = apoio.quiosque("Barraca Recebe Estados")
        val app = app(q)
        val token = apoio.novaComanda(q)
        pedido(token, Item(q.coco.id, 1))
        listOf(StatusComanda.FECHADA to "comanda-fechada", StatusComanda.CANCELADA to "comanda-cancelada").forEach { (estado, codigo) ->
            comandas.save(comandas.findByTokenAcesso(token)!!.also { it.status = estado })
            val r = receber(app, token, "PIX", 1200)
            assertEquals(409, r.statusCode(), r.body())
            assertContains(r.body(), codigo)
        }
        assertEquals(0, pagamentos.totalCentavos(idDe(token)))
    }

    @Test
    fun `reenviar a mesma confirmacao nao cobra duas vezes`() {
        val q = apoio.quiosque("Barraca Recebe Idempotente")
        val app = app(q)
        val ana = apoio.novaComanda(q, "Ana")
        val beto = apoio.novaComanda(q, "Beto")
        pedido(ana, Item(q.pastel.id, 2))
        pedido(beto, Item(q.pastel.id, 1))
        val id = UUID.randomUUID()

        val primeira = receber(app, ana, "DINHEIRO", 2000, recebido = 2000, id = id)
        val repetida = receber(app, ana, "DINHEIRO", 2000, recebido = 2000, id = id)

        assertEquals(201, primeira.statusCode(), primeira.body())
        assertEquals(200, repetida.statusCode(), repetida.body())
        assertContains(repetida.body(), "\"id\":\"$id\"")
        assertEquals(2000, pagamentos.totalCentavos(idDe(ana)), "um pagamento só")
        // O id de um pagamento de outra comanda não serve: o Beto não ganha nem perde nada
        val roubado = receber(app, beto, "PIX", 500, id = id)
        assertEquals(409, roubado.statusCode(), roubado.body())
        assertEquals(0, pagamentos.totalCentavos(idDe(beto)))
    }

    @Test
    fun `confirmacoes ao mesmo tempo nunca passam do saldo`() {
        val q = apoio.quiosque("Barraca Recebe Corrida")
        val token = apoio.novaComanda(q)
        pedido(token, Item(q.pastel.id, 2))
        val logins = (1..5).map { app(q) }
        val pool = Executors.newFixedThreadPool(5)

        val respostas =
            try {
                pool.invokeAll(logins.map { app -> Callable { receber(app, token, "PIX", 2000) } }).map { it.get() }
            } finally {
                pool.shutdown()
            }

        assertEquals(2, respostas.count { it.statusCode() == 201 }, respostas.map { it.statusCode() }.toString())
        assertEquals(3, respostas.count { it.statusCode() == 400 && it.body().contains("passa do que falta pagar") })
        assertEquals(4000, pagamentos.totalCentavos(idDe(token)))
    }

    // ---------- "Pagar a cada pedido" ----------

    @Test
    fun `no modo pagar a cada pedido o pagamento coloca o pedido na fila, na ordem em que foram feitos`() {
        val q = apoio.quiosque("Barraca Libera Pedido")
        regras(q, modo = ModoPagamento.POR_PEDIDO)
        val app = app(q)
        val token = apoio.novaComanda(q)
        val primeiro = pedido(token, Item(q.coco.id, 2))
        val segundo = pedido(token, Item(q.pastel.id, 1))
        assertEquals(StatusPedido.AGUARDANDO_PAGAMENTO, pedidos.findById(primeiro).orElseThrow().status)
        assertEquals(StatusPedido.AGUARDANDO_PAGAMENTO, pedidos.findById(segundo).orElseThrow().status)
        assertContains(app.get("/api/staff/pagamentos").body(), "\"pedidosAguardando\":2")
        assertFalse(app.get("/api/staff/fila").body().contains("$primeiro"), "nenhum dos dois está na fila")

        receber(app, token, "DINHEIRO", 1000)
        assertEquals(
            StatusPedido.AGUARDANDO_PAGAMENTO,
            pedidos.findById(primeiro).orElseThrow().status,
            "R$ 10,00 não cobre o primeiro (R$ 24,00)",
        )

        receber(app, token, "DINHEIRO", 1400)
        val liberado = pedidos.findById(primeiro).orElseThrow()
        assertEquals(StatusPedido.RECEBIDO, liberado.status)
        assertNotNull(liberado.naFilaEm)
        assertEquals(StatusPedido.AGUARDANDO_PAGAMENTO, pedidos.findById(segundo).orElseThrow().status, "o segundo ainda espera")
        val fila = app.get("/api/staff/fila").body()
        assertContains(fila, "$primeiro")
        assertFalse(fila.contains("$segundo"))
        assertContains(fila, "\"criadoEm\":\"${liberado.naFilaEm}\"", message = "o tempo de espera conta desde que entrou na fila")

        receber(app, token, "PIX", 2500)
        assertEquals(StatusPedido.RECEBIDO, pedidos.findById(segundo).orElseThrow().status)
        assertEquals(StatusComanda.ABERTA, statusDe(token), "conta aberta continua aberta, mesmo com saldo zero")
    }

    // ---------- Bloquear ----------

    @Test
    fun `bloquear impede pedidos, desbloquear libera, e a atendente tambem pode`() {
        val q = apoio.quiosque("Barraca Bloqueia")
        val dono = app(q)
        val carla = carla(q)
        val token = apoio.novaComanda(q)
        val id = idDe(token)
        val celular = apoio.celular()
        pedido(token, Item(q.coco.id, 1))
        fechar(token, """{"metodo":"CARTAO"}""")

        val bloqueada = carla.post("/api/staff/comandas/$id/bloquear")

        assertEquals(200, bloqueada.statusCode(), bloqueada.body())
        assertContains(bloqueada.body(), "\"status\":\"BLOQUEADA\"")
        assertEquals(StatusComanda.BLOQUEADA, statusDe(token))
        assertNull(comandas.findByTokenAcesso(token)!!.avisoPagamentoEm, "o aviso de pagamento some")
        assertContains(apoio.pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1)).body(), "comanda-bloqueada")
        assertContains(celular.get("/api/c/$token").body(), "\"bloqueio\":{\"codigo\":\"comanda-bloqueada\"")
        assertEquals(200, dono.post("/api/staff/comandas/$id/bloquear").statusCode(), "repetir é inofensivo")
        assertContains(dono.get("/api/staff/pagamentos").body(), "\"status\":\"BLOQUEADA\"")
        assertEquals(201, receber(dono, token, "PIX", 500).statusCode(), "dá para receber de uma comanda bloqueada")
        assertEquals(StatusComanda.BLOQUEADA, statusDe(token))

        assertEquals(200, carla.post("/api/staff/comandas/$id/desbloquear").statusCode())
        assertEquals(StatusComanda.ABERTA, statusDe(token))
        assertEquals(201, apoio.pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1)).statusCode())
        assertEquals(200, dono.post("/api/staff/comandas/$id/desbloquear").statusCode(), "desbloquear uma comanda aberta não faz nada")
    }

    @Test
    fun `nao da para bloquear nem desbloquear uma conta encerrada`() {
        val q = apoio.quiosque("Barraca Bloqueia Estados")
        val app = app(q)
        val token = apoio.novaComanda(q)
        comandas.save(comandas.findByTokenAcesso(token)!!.also { it.status = StatusComanda.FECHADA })

        val r = app.post("/api/staff/comandas/${idDe(token)}/bloquear")

        assertEquals(409, r.statusCode())
        assertContains(r.body(), "comanda-fechada")
        assertEquals(409, app.post("/api/staff/comandas/${idDe(token)}/desbloquear").statusCode())
        assertEquals(StatusComanda.FECHADA, statusDe(token))
    }

    // ---------- Encerrar e fechar sem receber ----------

    @Test
    fun `encerrar so vale para conta sem saldo`() {
        val q = apoio.quiosque("Barraca Encerra")
        val app = app(q)
        val devendo = apoio.novaComanda(q, "Ana")
        val vazia = apoio.novaComanda(q, "Beto")
        pedido(devendo, Item(q.coco.id, 1))

        val recusada = app.post("/api/staff/comandas/${idDe(devendo)}/encerrar")
        val encerrada = app.post("/api/staff/comandas/${idDe(vazia)}/encerrar")

        assertEquals(409, recusada.statusCode(), recusada.body())
        assertContains(recusada.body(), "saldo-pendente")
        assertContains(recusada.body(), "R$ 12,00")
        assertEquals(StatusComanda.ABERTA, statusDe(devendo))
        assertEquals(200, encerrada.statusCode(), encerrada.body())
        assertEquals(StatusComanda.FECHADA, statusDe(vazia))
        assertNotNull(comandas.findByTokenAcesso(vazia)!!.fechadaEm)
        assertEquals(200, app.post("/api/staff/comandas/${idDe(vazia)}/encerrar").statusCode(), "repetir é inofensivo")
    }

    @Test
    fun `o dono fecha sem receber com motivo, e fica gravado o valor que ficou sem pagar`() {
        val q = apoio.quiosque("Barraca Forcado")
        val app = app(q)
        val token = apoio.novaComanda(q, "Ana")
        pedido(token, Item(q.pastel.id, 2))
        receber(app, token, "DINHEIRO", 1000)
        fechar(token, """{"metodo":"CARTAO"}""")

        val curto = app.post("/api/staff/comandas/${idDe(token)}/fechamento-forcado", """{"motivo":"  a "}""")
        val ausente = app.post("/api/staff/comandas/${idDe(token)}/fechamento-forcado", "{}")
        val ok = app.post("/api/staff/comandas/${idDe(token)}/fechamento-forcado", """{"motivo":"  Foi embora   sem pagar "}""")

        assertEquals(400, curto.statusCode())
        assertEquals(400, ausente.statusCode())
        assertContains(ausente.body(), "motivo")
        assertEquals(200, ok.statusCode(), ok.body())
        val comanda = comandas.findByTokenAcesso(token)!!
        assertEquals(StatusComanda.FECHADA, comanda.status)
        assertEquals("Foi embora sem pagar", comanda.fechamentoForcadoMotivo)
        assertEquals(4000, comanda.saldoPerdoadoCentavos)
        assertEquals(q.usuarioId, comanda.fechadaPorUsuarioId)
        assertNotNull(comanda.fechadaEm)
        assertContains(apoio.celular().get("/api/c/$token/conta").body(), "\"status\":\"FECHADA\"")
        assertEquals(409, app.post("/api/staff/comandas/${idDe(token)}/fechamento-forcado", """{"motivo":"de novo"}""").statusCode())
    }

    @Test
    fun `so o dono fecha sem receber, a atendente recebe 403`() {
        val q = apoio.quiosque("Barraca Forcado Atendente")
        val carla = carla(q)
        val token = apoio.novaComanda(q)
        pedido(token, Item(q.coco.id, 1))

        val r = carla.post("/api/staff/comandas/${idDe(token)}/fechamento-forcado", """{"motivo":"cliente foi embora"}""")

        assertEquals(403, r.statusCode(), r.body())
        assertContains(r.body(), "\"erro\":\"papel\"")
        assertEquals(StatusComanda.ABERTA, statusDe(token))
        assertEquals(201, receber(carla, token, "PIX", 1200).statusCode(), "mas a atendente recebe pagamentos")
    }

    @Test
    fun `fechar sem receber cancela os pedidos que nunca foram para a fila e nao os conta como calote`() {
        val q = apoio.quiosque("Barraca Forcado Por Pedido")
        regras(q, modo = ModoPagamento.POR_PEDIDO)
        val app = app(q)
        val token = apoio.novaComanda(q)
        val pago = pedido(token, Item(q.coco.id, 1))
        receber(app, token, "PIX", 1200)
        val parado = pedido(token, Item(q.pastel.id, 1))
        assertEquals(StatusPedido.RECEBIDO, pedidos.findById(pago).orElseThrow().status)
        app.post("/api/staff/pedidos/$pago/status", """{"status":"ENTREGUE"}""")
        val aberto = pedido(token, Item(q.pastel.id, 3))
        assertEquals(StatusPedido.AGUARDANDO_PAGAMENTO, pedidos.findById(aberto).orElseThrow().status)
        // O cliente pagou o do meio e não o último
        receber(app, token, "PIX", 2500)
        assertEquals(StatusPedido.RECEBIDO, pedidos.findById(parado).orElseThrow().status)

        val r = app.post("/api/staff/comandas/${idDe(token)}/fechamento-forcado", """{"motivo":"Foi embora"}""")

        assertEquals(200, r.statusCode(), r.body())
        assertEquals(StatusPedido.CANCELADO, pedidos.findById(aberto).orElseThrow().status)
        assertTrue(itens.findAllByPedidoIdOrderByLinha(aberto).all { it.cancelado })
        val comanda = comandas.findByTokenAcesso(token)!!
        assertEquals(StatusComanda.FECHADA, comanda.status)
        assertNull(comanda.saldoPerdoadoCentavos, "o que nunca foi para a fila não é calote")
        assertNull(comanda.fechamentoForcadoMotivo)
    }

    // ---------- Isolamento ----------

    @Test
    fun `o dono de outra barraca nao mexe nas comandas e nao ve os pagamentos alheios`() {
        val a = apoio.quiosque("Barraca Isola Pagamento A")
        val b = apoio.quiosque("Barraca Isola Pagamento B")
        val appB = app(b)
        val ana = apoio.novaComanda(a, "Ana")
        pedido(ana, Item(a.pastel.id, 2))
        fechar(ana, """{"metodo":"CARTAO"}""")
        val id = idDe(ana)
        val tentativas =
            listOf(
                receber(appB, ana, "PIX", 1000),
                appB.post("/api/staff/comandas/$id/bloquear"),
                appB.post("/api/staff/comandas/$id/desbloquear"),
                appB.post("/api/staff/comandas/$id/encerrar"),
                appB.post("/api/staff/comandas/$id/fechamento-forcado", """{"motivo":"quero fechar a conta"}"""),
                appB.post("/api/staff/comandas/${UUID.randomUUID()}/bloquear"),
            )

        tentativas.forEach { assertEquals(404, it.statusCode(), it.body()) }
        assertContains(tentativas.first().body(), "\"erro\":\"comanda\"")
        assertEquals(StatusComanda.AGUARDANDO_PAGAMENTO, statusDe(ana))
        assertEquals(0, pagamentos.totalCentavos(id))
        assertNull(comandas.findByTokenAcesso(ana)!!.fechamentoForcadoMotivo)
        assertFalse(appB.get("/api/staff/pagamentos").body().contains("Ana"))
        assertContains(app(a).get("/api/staff/pagamentos").body(), "Ana")
    }

    @Test
    fun `sem entrar no app nada funciona, e sem o token de seguranca tambem nao`() {
        val q = apoio.quiosque("Barraca Pagamento Sem Login")
        val token = apoio.novaComanda(q)
        val id = idDe(token)
        val anonimo = StaffApp(port)

        assertEquals(401, anonimo.get("/api/staff/pagamentos").statusCode())
        assertEquals(401, receberPorId(anonimo, id, "PIX", 100).statusCode())
        assertEquals(401, anonimo.post("/api/staff/comandas/$id/bloquear").statusCode())

        val semCsrf = app(q)
        assertEquals(403, semCsrf.post("/api/staff/comandas/$id/bloquear", "{}", comCsrf = false).statusCode())
        assertEquals(403, semCsrf.post("/api/staff/comandas/$id/pagamentos", "{}", comCsrf = false).statusCode())
        assertEquals(StatusComanda.ABERTA, statusDe(token))
    }

    // ---------- O extrato expira ----------

    @Test
    fun `o extrato da conta paga abre por 7 dias e depois o link da 404 como um token que nunca existiu`() {
        val q = apoio.quiosque("Barraca Extrato Expira")
        val token = apoio.novaComanda(q)
        val celular = apoio.celular()
        pedido(token, Item(q.coco.id, 1))
        comandas.save(comandas.findByTokenAcesso(token)!!.also { it.status = StatusComanda.FECHADA })

        jdbc.update("UPDATE comanda SET fechada_em = now() - interval '6 days 23 hours' WHERE token_acesso = ?", token)
        assertEquals(200, celular.get("/api/c/$token").statusCode())
        assertEquals(200, celular.get("/api/c/$token/conta").statusCode())

        jdbc.update("UPDATE comanda SET fechada_em = now() - interval '7 days 1 hour' WHERE token_acesso = ?", token)
        assertEquals(404, celular.get("/api/c/$token").statusCode())
        assertEquals(404, celular.get("/api/c/$token/conta").statusCode())
        assertEquals(404, celular.get("/api/c/$token/pedidos").statusCode())
        assertEquals(404, celular.postJson("/api/c/$token/pedidos", apoio.corpo(UUID.randomUUID(), Item(q.coco.id, 1))).statusCode())
        assertEquals(404, celular.stream("/api/c/$token/eventos").statusCode())
        assertEquals(
            celular.get("/api/c/${"Z".repeat(22)}").body(),
            celular.get("/api/c/$token").body(),
            "a resposta é a mesma de um token que não existe",
        )
    }

    @Test
    fun `uma conta aberta ou aguardando nao expira, mesmo velha`() {
        val q = apoio.quiosque("Barraca Conta Velha")
        val token = apoio.novaComanda(q)
        jdbc.update("UPDATE comanda SET aberta_em = now() - interval '30 days' WHERE token_acesso = ?", token)

        assertEquals(200, apoio.celular().get("/api/c/$token").statusCode())
    }

    // ---------- Tempo real ----------

    @Test
    fun `fechar a conta e receber avisam so o app da barraca e a comanda certa`() {
        val a = apoio.quiosque("Barraca Tempo Real Conta A")
        val b = apoio.quiosque("Barraca Tempo Real Conta B")
        val ana = apoio.novaComanda(a, "Ana")
        val beto = apoio.novaComanda(a, "Beto")
        pedido(ana, Item(a.pastel.id, 1))
        val appA = app(a)
        val appB = app(b)

        SseTestClient(appA.http.stream("/api/staff/eventos")).use { quiosqueA ->
            SseTestClient(appB.http.stream("/api/staff/eventos")).use { quiosqueB ->
                SseTestClient(apoio.celular().stream("/api/c/$ana/eventos")).use { paginaAna ->
                    SseTestClient(apoio.celular().stream("/api/c/$beto/eventos")).use { paginaBeto ->
                        fechar(ana, """{"metodo":"DINHEIRO"}""")

                        assertNotNull(quiosqueA.esperar("event:conta"), "o app da barraca A sabe que a Ana vai pagar")
                        assertNotNull(paginaAna.esperar("event:conta"))
                        assertTrue(quiosqueB.nada("event:conta"), "o app da barraca B não recebe nada da A")
                        assertTrue(paginaBeto.nada("event:conta"), "a página do Beto não recebe a conta da Ana")

                        assertEquals(201, receber(appA, ana, "DINHEIRO", 2500).statusCode())
                        assertNotNull(quiosqueA.esperar("event:conta"))
                        assertNotNull(paginaAna.esperar("event:conta"), "a Ana vê o pagamento na hora")
                        assertTrue(paginaBeto.nada("event:conta"))

                        // Repetir o mesmo toque de "fechar" não faz barulho de novo
                        assertEquals(200, apoio.celular().postJson("/api/c/$beto/conta/reabrir", "{}").statusCode())
                        assertTrue(paginaBeto.nada("event:conta"), "a conta do Beto não mudou")
                    }
                }
            }
        }
    }
}
