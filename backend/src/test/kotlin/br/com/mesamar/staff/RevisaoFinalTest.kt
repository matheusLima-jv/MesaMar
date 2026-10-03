package br.com.mesamar.staff

import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.barraca.ModoPagamento
import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.comanda.StatusComanda
import br.com.mesamar.common.Fuso
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.pagamento.EncerramentoDiario
import br.com.mesamar.pedido.PedidoRepository
import br.com.mesamar.pedido.StatusPedido
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.Item
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.PainelTestSupport.Companion.SENHA
import br.com.mesamar.support.Quiosque
import br.com.mesamar.support.RecordingEmailGateway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Fase 7 · 7.4 (revisão final): cancelar depois de pagar, liberar pedido ao cancelar, encerramento diário e sessões do dono. */
@IntegrationTest
class RevisaoFinalTest(
    private val entregador: br.com.mesamar.common.Entregador,
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    private val usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    checkin: CheckinService,
    private val barracas: BarracaRepository,
    private val comandas: ComandaRepository,
    private val pedidos: PedidoRepository,
    private val encerramento: EncerramentoDiario,
    private val emails: RecordingEmailGateway,
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    private val painel = PainelTestSupport(port, convites)
    private val apoio = ClienteTestSupport(port, painel, usuarios, categorias, produtos, checkin)

    private fun app(q: Quiosque): StaffApp =
        StaffApp(port).also {
            assertEquals(200, it.post("/api/staff/entrar/dono", """{"email":"${q.email}","senha":"$SENHA"}""").statusCode())
        }

    private fun pedido(
        token: String,
        vararg linhas: Item,
    ): UUID {
        val r = apoio.pedir(apoio.celular(), token, UUID.randomUUID(), *linhas)
        assertEquals(201, r.statusCode(), r.body())
        return UUID.fromString(assertNotNull(Regex(""""id":"([0-9a-f-]{36})"""").find(r.body())).groupValues[1])
    }

    private fun receber(
        app: StaffApp,
        token: String,
        valor: Long,
    ) = app
        .post(
            "/api/staff/comandas/${comandas.findByTokenAcesso(token)!!.id}/pagamentos",
            """{"id":"${UUID.randomUUID()}","metodo":"DINHEIRO","valorCentavos":$valor}""",
        ).also { assertEquals(201, it.statusCode(), it.body()) }

    private fun cancelar(
        app: StaffApp,
        pedidoId: UUID,
    ) = app.post("/api/staff/pedidos/$pedidoId/cancelar", """{"motivo":"ESGOTOU"}""")

    private fun modoPorPedido(q: Quiosque) {
        val barraca = barracas.findById(q.barracaId).orElseThrow()
        barraca.modoPagamento = ModoPagamento.POR_PEDIDO
        barracas.save(barraca)
    }

    // ---------- Cancelar depois de pagar ----------

    @Test
    fun `cancelar o que o cliente ja pagou e recusado e nada muda`() {
        val q = apoio.quiosque("Barraca Cancela Pago")
        val app = app(q)
        val token = apoio.novaComanda(q)
        val id = pedido(token, Item(q.coco.id, 1))
        receber(app, token, 1200)

        val r = cancelar(app, id)

        assertEquals(409, r.statusCode(), r.body())
        assertContains(r.body(), "O cliente já pagou por isso")
        assertEquals(StatusPedido.RECEBIDO, pedidos.findById(id).orElseThrow().status)
        assertContains(apoio.celular().get("/api/c/$token/pedidos").body(), "\"saldoCentavos\":0")
    }

    @Test
    fun `cancelar um item que sobra pago e permitido, e o que deixaria a conta negativa nao`() {
        val q = apoio.quiosque("Barraca Cancela Item Pago")
        val app = app(q)
        val token = apoio.novaComanda(q)
        val id = pedido(token, Item(q.coco.id, 1), Item(q.pastel.id, 1))
        receber(app, token, 1200)
        val itens = jdbc.queryForList("SELECT id, nome_snapshot FROM item_pedido WHERE pedido_id = ? ORDER BY linha", id)
        val pastel = itens.first { it["nome_snapshot"] == "Pastel" }["id"]
        val coco = itens.first { it["nome_snapshot"] == "Água de coco" }["id"]

        // Total R$ 37,00, pago R$ 12,00: tirar o pastel (R$ 25,00) deixa exatamente zero
        assertEquals(200, app.post("/api/staff/pedidos/$id/itens/$pastel/cancelar", """{"motivo":"ESGOTOU"}""").statusCode())
        // Agora só resta o coco, já pago: cancelar também deixaria a conta negativa
        assertEquals(409, app.post("/api/staff/pedidos/$id/itens/$coco/cancelar", """{"motivo":"ESGOTOU"}""").statusCode())
    }

    @Test
    fun `cancelar um pedido de outra barraca continua dando 404`() {
        val q = apoio.quiosque("Barraca Cancela A")
        val outra = apoio.quiosque("Barraca Cancela B")
        val id = pedido(apoio.novaComanda(q), Item(q.coco.id, 1))

        assertEquals(404, cancelar(app(outra), id).statusCode())
        assertEquals(StatusPedido.RECEBIDO, pedidos.findById(id).orElseThrow().status)
    }

    // ---------- Liberar pedido ao cancelar ----------

    @Test
    fun `cancelar o pedido da frente libera o seguinte, que ja estava coberto pelo que foi pago`() {
        val q = apoio.quiosque("Barraca Libera Cancelando")
        modoPorPedido(q)
        val app = app(q)
        val token = apoio.novaComanda(q)
        val primeiro = pedido(token, Item(q.pastel.id, 1))
        val segundo = pedido(token, Item(q.coco.id, 1))
        receber(app, token, 1200)
        assertEquals(StatusPedido.AGUARDANDO_PAGAMENTO, pedidos.findById(primeiro).orElseThrow().status, "R$ 12,00 não cobre o pastel")
        assertEquals(StatusPedido.AGUARDANDO_PAGAMENTO, pedidos.findById(segundo).orElseThrow().status, "o segundo espera o primeiro")

        assertEquals(200, cancelar(app, primeiro).statusCode())

        val liberado = pedidos.findById(segundo).orElseThrow()
        assertEquals(StatusPedido.RECEBIDO, liberado.status)
        assertNotNull(liberado.naFilaEm)
        assertContains(app.get("/api/staff/fila").body(), "$segundo")
    }

    // ---------- Encerramento diário ----------

    @Test
    fun `de madrugada encerra as comandas de dias anteriores sem saldo e deixa as que ainda devem`() {
        val q = apoio.quiosque("Barraca Encerra Antigas")
        val app = app(q)
        val quitada = apoio.novaComanda(q, "Quita")
        val devendo = apoio.novaComanda(q, "Deve")
        val semPedido = apoio.novaComanda(q, "Vazia")
        val hoje = apoio.novaComanda(q, "Hoje")
        pedido(quitada, Item(q.coco.id, 1)).also { receber(app, quitada, 1200) }
        pedido(devendo, Item(q.coco.id, 1))
        pedido(hoje, Item(q.coco.id, 1)).also { receber(app, hoje, 1200) }
        listOf(quitada, devendo, semPedido).forEach {
            jdbc.update("UPDATE comanda SET dia = ? WHERE token_acesso = ?", Fuso.hoje(clock).minusDays(1), it)
        }

        encerramento.rodar()

        assertEquals(StatusComanda.FECHADA, comandas.findByTokenAcesso(quitada)!!.status)
        assertEquals(StatusComanda.FECHADA, comandas.findByTokenAcesso(semPedido)!!.status)
        assertEquals(StatusComanda.ABERTA, comandas.findByTokenAcesso(devendo)!!.status, "quem ainda deve continua aberto")
        assertEquals(StatusComanda.ABERTA, comandas.findByTokenAcesso(hoje)!!.status, "comanda de hoje não é encerrada")
        // Depois de encerrada, o link não aceita pedido novo
        assertTrue(apoio.pedir(apoio.celular(), quitada, UUID.randomUUID(), Item(q.coco.id, 1)).statusCode() in 400..499)
    }

    // ---------- Comanda de outro dia ----------

    private fun deOntem(token: String) {
        jdbc.update("UPDATE comanda SET dia = ? WHERE token_acesso = ?", Fuso.hoje(clock).minusDays(1), token)
    }

    @Test
    fun `comanda de um dia anterior nao aceita pedido novo, mas o quiosque ainda recebe o que ela deve`() {
        val q = apoio.quiosque("Barraca Comanda Antiga")
        val app = app(q)
        val token = apoio.novaComanda(q, "Devedor")
        pedido(token, Item(q.coco.id, 1))
        deOntem(token)
        val cliente = apoio.celular()

        val r = apoio.pedir(cliente, token, UUID.randomUUID(), Item(q.coco.id, 1))

        assertEquals(409, r.statusCode(), r.body())
        assertContains(r.body(), "comanda-de-outro-dia")
        assertContains(r.body(), "é de outro dia")
        assertContains(cliente.get("/api/c/$token").body(), "\"codigo\":\"comanda-de-outro-dia\"", message = "a página mostra o motivo")
        assertEquals(1, pedidos.findAll().count { it.comandaId == comandas.findByTokenAcesso(token)!!.id }, "nenhum pedido novo")

        // O cliente ainda pode fechar a conta e pagar; o quiosque recebe e a comanda se encerra
        assertEquals(200, cliente.postJson("/api/c/$token/conta/fechar", """{"metodo":"DINHEIRO"}""").statusCode())
        receber(app, token, 1200)
        assertEquals(StatusComanda.FECHADA, comandas.findByTokenAcesso(token)!!.status)
    }

    @Test
    fun `voltar e pedir mais tambem e recusado numa comanda de outro dia`() {
        val q = apoio.quiosque("Barraca Reabrir Antiga")
        val token = apoio.novaComanda(q, "Devedor")
        pedido(token, Item(q.coco.id, 1))
        val cliente = apoio.celular()
        assertEquals(200, cliente.postJson("/api/c/$token/conta/fechar", """{"metodo":"DINHEIRO"}""").statusCode())
        deOntem(token)

        val r = cliente.postJson("/api/c/$token/conta/reabrir", "{}")

        assertEquals(409, r.statusCode(), r.body())
        assertContains(r.body(), "comanda-de-outro-dia")
        assertEquals(StatusComanda.AGUARDANDO_PAGAMENTO, comandas.findByTokenAcesso(token)!!.status)
    }

    @Test
    fun `comanda de hoje continua aceitando pedido`() {
        val q = apoio.quiosque("Barraca Comanda De Hoje")
        val token = apoio.novaComanda(q)

        assertEquals(201, apoio.pedir(apoio.celular(), token, UUID.randomUUID(), Item(q.coco.id, 1)).statusCode())
    }

    // ---------- Sessão do dono depois de trocar a senha ----------

    @Test
    fun `trocar a senha pelo link do e-mail derruba as sessoes abertas do painel e do app`() {
        val email = painel.novoEmail("sessao")
        val painelAberto = painel.donoComBarraca(email = email)
        val appAberto = StaffApp(port)
        assertEquals(200, appAberto.post("/api/staff/entrar/dono", """{"email":"$email","senha":"$SENHA"}""").statusCode())
        assertEquals(200, painelAberto.get("/painel").statusCode())
        assertEquals(200, appAberto.get("/api/staff/cardapio").statusCode())

        painel.post(painel.session(), "/painel/esqueci-senha", "/painel/esqueci-senha", mapOf("email" to email))
        entregador.rodar()
        val link = assertNotNull(emails.ultimoLink(email))
        val novaSenha = "maré cheia às quatro"
        val troca =
            painel.post(painel.session(), link, "/painel/nova-senha", mapOf("token" to link.substringAfter("token="), "senha" to novaSenha))
        assertEquals(302, troca.statusCode(), troca.body())

        // O painel manda de volta para o login e o app responde 401, mesmo com a sessão antiga
        val painelDepois = painelAberto.get("/painel")
        assertEquals(302, painelDepois.statusCode())
        assertContains(painelDepois.headers().firstValue("Location").orElse(""), "/painel/entrar")
        assertEquals(401, appAberto.get("/api/staff/cardapio").statusCode())

        // Quem entra de novo com a senha nova funciona
        val novoApp = StaffApp(port)
        assertEquals(200, novoApp.post("/api/staff/entrar/dono", """{"email":"$email","senha":"$novaSenha"}""").statusCode())
        assertEquals(200, novoApp.get("/api/staff/cardapio").statusCode())
    }

    @Test
    fun `a sessao aberta depois da troca de senha continua valendo`() {
        val email = painel.novoEmail("sessao-nova")
        painel.donoComBarraca(email = email)
        jdbc.update("UPDATE usuario SET credenciais_em = now() - interval '1 hour' WHERE lower(email) = lower(?)", email)

        val http = painel.session()
        painel.login(http, email)

        assertEquals(200, http.get("/painel").statusCode())
    }
}
