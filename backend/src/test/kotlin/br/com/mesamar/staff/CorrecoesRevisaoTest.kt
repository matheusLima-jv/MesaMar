package br.com.mesamar.staff

import br.com.mesamar.barraca.BarracaForm
import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.barraca.BarracaService
import br.com.mesamar.barraca.ModoPagamento
import br.com.mesamar.barraca.RegrasForm
import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.comanda.StatusComanda
import br.com.mesamar.common.Fuso
import br.com.mesamar.conta.ContaService
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.RecuperacaoSenhaService
import br.com.mesamar.conta.ResetSenhaRepository
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.guardasol.GuardaSolRepository
import br.com.mesamar.guardasol.GuardaSolService
import br.com.mesamar.guardasol.LoteForm
import br.com.mesamar.pagamento.PagamentoService
import br.com.mesamar.pedido.PausaService
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
import org.springframework.validation.BeanPropertyBindingResult
import java.time.Clock
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Correções da revisão de código (2026-10-02). Rodados contra o código anterior, 14 destes testes falham; os outros
 * dois (fechar sem pagamento algum e senha longa no login) já passavam e ficam como guarda.
 */
@IntegrationTest
class CorrecoesRevisaoTest(
    @Value("\${local.server.port}") private val port: Int,
    private val convites: ConviteService,
    private val usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    checkin: CheckinService,
    private val barracas: BarracaRepository,
    private val barracaService: BarracaService,
    private val comandas: ComandaRepository,
    private val pedidos: PedidoRepository,
    private val pagamentoService: PagamentoService,
    private val pausa: PausaService,
    private val contaService: ContaService,
    private val recuperacao: RecuperacaoSenhaService,
    private val resets: ResetSenhaRepository,
    private val emails: RecordingEmailGateway,
    private val guardaSolService: GuardaSolService,
    private val guardaSois: GuardaSolRepository,
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    private val painel = PainelTestSupport(port, convites)
    private val apoio = ClienteTestSupport(port, painel, usuarios, categorias, produtos, checkin)

    private fun app(q: Quiosque): StaffApp =
        StaffApp(port).also {
            assertEquals(200, it.post("/api/staff/entrar/dono", """{"email":"${q.email}","senha":"$SENHA"}""").statusCode())
        }

    private fun idDe(token: String): UUID = comandas.findByTokenAcesso(token)!!.id

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
        .post("/api/staff/comandas/${idDe(token)}/pagamentos", """{"id":"${UUID.randomUUID()}","metodo":"PIX","valorCentavos":$valor}""")
        .also { assertEquals(201, it.statusCode(), it.body()) }

    private fun porPedido(q: Quiosque) {
        val b = barracas.findById(q.barracaId).orElseThrow()
        b.modoPagamento = ModoPagamento.POR_PEDIDO
        barracas.save(b)
    }

    /** Roda as tarefas ao mesmo tempo (todas esperam a largada) e devolve os resultados. */
    private fun <T> juntos(vararg tarefas: () -> T): List<T> {
        val largada = CountDownLatch(1)
        Executors.newFixedThreadPool(tarefas.size).use { pool ->
            val futuros =
                tarefas.map { t ->
                    pool.submit(
                        Callable {
                            largada.await()
                            t()
                        },
                    )
                }
            largada.countDown()
            return futuros.map { it.get() }
        }
    }

    // ---------- Itens 2 e 3: fechamento forçado ----------

    @Test
    fun `fechar sem receber com pedidos pagos em parte e recusado e nao libera nada para a fila`() {
        val q = apoio.quiosque("Barraca Revisao Forcado")
        porPedido(q)
        val app = app(q)
        val token = apoio.novaComanda(q)
        val caro = pedido(token, Item(q.coco.id, 2)) // R$ 24,00
        val barato = pedido(token, Item(q.coco.id, 1)) // R$ 12,00
        receber(app, token, 1000) // não cobre nenhum dos dois

        val r = app.post("/api/staff/comandas/${idDe(token)}/fechamento-forcado", """{"motivo":"Foi embora"}""")

        assertEquals(409, r.statusCode(), r.body())
        assertContains(r.body(), "pago-em-parte")
        assertContains(r.body(), "R$ 10,00")
        // Antes, cancelar o primeiro liberava o segundo para a fila e a conta fechava com pedido pendurado
        assertEquals(StatusPedido.AGUARDANDO_PAGAMENTO, pedidos.findById(caro).orElseThrow().status)
        assertEquals(StatusPedido.AGUARDANDO_PAGAMENTO, pedidos.findById(barato).orElseThrow().status)
        assertEquals(StatusComanda.ABERTA, comandas.findByTokenAcesso(token)!!.status)
    }

    @Test
    fun `fechar sem receber sem pagamento cancela tudo o que esperava e nada entra na fila`() {
        val q = apoio.quiosque("Barraca Revisao Forcado 2")
        porPedido(q)
        val app = app(q)
        val token = apoio.novaComanda(q)
        val a = pedido(token, Item(q.coco.id, 2))
        val b = pedido(token, Item(q.pastel.id, 1))

        assertEquals(200, app.post("/api/staff/comandas/${idDe(token)}/fechamento-forcado", """{"motivo":"Foi embora"}""").statusCode())

        assertEquals(StatusPedido.CANCELADO, pedidos.findById(a).orElseThrow().status)
        assertEquals(StatusPedido.CANCELADO, pedidos.findById(b).orElseThrow().status)
        assertEquals(StatusComanda.FECHADA, comandas.findByTokenAcesso(token)!!.status)
    }

    // ---------- Item 12: bloqueada sem saldo ----------

    @Test
    fun `comanda bloqueada sem saldo continua na aba Pagamentos e entra no encerramento diario`() {
        val q = apoio.quiosque("Barraca Revisao Bloqueada")
        val app = app(q)
        val token = apoio.novaComanda(q)
        val id = idDe(token)
        assertEquals(200, app.post("/api/staff/comandas/$id/bloquear").statusCode())

        assertContains(app.get("/api/staff/pagamentos").body(), id.toString(), message = "aparece para desbloquear ou encerrar")

        jdbc.update("UPDATE comanda SET dia = dia - 1 WHERE id = ?", id)
        pagamentoService.encerrarSemSaldoDeDiasAnteriores(Fuso.hoje(clock))
        assertEquals(StatusComanda.FECHADA, comandas.findById(id).orElseThrow().status)
    }

    // ---------- Item 13: conta encerrada com saldo perdoado ----------

    @Test
    fun `conta fechada sem receber nao aparece como paga para o cliente`() {
        val q = apoio.quiosque("Barraca Revisao Perdoado")
        val app = app(q)
        val token = apoio.novaComanda(q)
        pedido(token, Item(q.pastel.id, 1))
        assertEquals(200, app.post("/api/staff/comandas/${idDe(token)}/fechamento-forcado", """{"motivo":"Foi embora"}""").statusCode())

        val conta = apoio.celular().get("/api/c/$token/conta").body()

        assertContains(conta, "\"status\":\"FECHADA\"")
        assertContains(conta, "\"encerradaComSaldo\":true")
        assertFalse(conta.contains("Foi embora"), "o motivo fica só para o dono")
    }

    // ---------- Item 14: preço mudou depois de montar o carrinho ----------

    @Test
    fun `pedido com preco diferente do atual e recusado para o cliente conferir, e o preco certo passa`() {
        val q = apoio.quiosque("Barraca Revisao Preco")
        val token = apoio.novaComanda(q)
        val celular = apoio.celular()
        jdbc.update("UPDATE produto SET preco_centavos = 1500 WHERE id = ?", q.coco.id)

        val velho =
            celular.postJson(
                "/api/c/$token/pedidos",
                """{"id":"${UUID.randomUUID()}","itens":[{"produtoId":"${q.coco.id}","quantidade":1,"precoCentavos":1200}]}""",
            )
        assertEquals(409, velho.statusCode(), velho.body())
        assertContains(velho.body(), "preco-mudou")
        assertContains(velho.body(), "Água de coco")

        val novo =
            celular.postJson(
                "/api/c/$token/pedidos",
                """{"id":"${UUID.randomUUID()}","itens":[{"produtoId":"${q.coco.id}","quantidade":1,"precoCentavos":1500}]}""",
            )
        assertEquals(201, novo.statusCode(), novo.body())
        assertContains(novo.body(), "\"totalCentavos\":1500")

        // Página antiga em cache, sem o preço: vale o preço atual
        assertEquals(201, apoio.pedir(celular, token, UUID.randomUUID(), Item(q.coco.id, 1)).statusCode())
    }

    @Test
    fun `a equipe tambem confere o preco ao lancar`() {
        val q = apoio.quiosque("Barraca Revisao Preco Equipe")
        val token = apoio.novaComanda(q)
        jdbc.update("UPDATE produto SET preco_centavos = 1500 WHERE id = ?", q.coco.id)

        val r =
            app(q).post(
                "/api/staff/comandas/${idDe(token)}/pedidos",
                """{"id":"${UUID.randomUUID()}","itens":[{"produtoId":"${q.coco.id}","quantidade":1,"precoCentavos":1200}]}""",
            )

        assertEquals(409, r.statusCode())
        assertContains(r.body(), "preco-mudou")
    }

    // ---------- Item 15: salvar as regras não apaga a pausa ----------

    @Test
    fun `salvar as regras com a barraca lida antes nao desfaz a pausa feita no app`() {
        val q = apoio.quiosque("Barraca Revisao Pausa")
        val lidaAntes = barracas.findById(q.barracaId).orElseThrow() // o painel carregou a barraca
        pausa.definir(q.barracaId, true, "Já volto") // a atendente pausou no app enquanto isso

        val form = RegrasForm(limiteGuardaSois = "40", modoPagamento = "FINAL", limiteSaldo = "", tempoAlertaMin = "15")
        assertTrue(barracaService.atualizarRegras(lidaAntes, form, BeanPropertyBindingResult(form, "form")))

        val agora = barracas.findById(q.barracaId).orElseThrow()
        assertTrue(agora.pedidosPausados, "a pausa continua")
        assertEquals("Já volto", agora.mensagemPausa)
        assertEquals(40, agora.limiteGuardaSois)
    }

    // ---------- Itens 16 e 17: criar a barraca ----------

    @Test
    fun `dois envios do cadastro da barraca ao mesmo tempo criam uma barraca so`() {
        val email = painel.novoEmail("revisao")
        painel.donoLogado(email)
        val usuario = assertNotNull(usuarios.findByEmailIgnoreCase(email))

        val criadas =
            juntos(
                { barracaService.criar(usuario, BarracaForm("Barraca Dupla", "ENSEADA"), BeanPropertyBindingResult(Any(), "f"))?.id },
                { barracaService.criar(usuario, BarracaForm("Barraca Dupla", "ENSEADA"), BeanPropertyBindingResult(Any(), "f"))?.id },
            )

        assertEquals(criadas[0], criadas[1], "o segundo envio devolve a mesma barraca")
        assertEquals(criadas[0], usuarios.findById(usuario.id).orElseThrow().barracaId)
    }

    @Test
    fun `duas barracas com o mesmo nome ao mesmo tempo ganham enderecos diferentes`() {
        val nome = "Barraca Gemea ${UUID.randomUUID().toString().take(6)}"
        val donos =
            (1..2).map {
                val email = painel.novoEmail("gemea")
                painel.donoLogado(email)
                assertNotNull(usuarios.findByEmailIgnoreCase(email))
            }

        val criadas =
            juntos(
                { barracaService.criar(donos[0], BarracaForm(nome, "ENSEADA"), BeanPropertyBindingResult(Any(), "f")) },
                { barracaService.criar(donos[1], BarracaForm(nome, "ENSEADA"), BeanPropertyBindingResult(Any(), "f")) },
            )

        assertNotEquals(assertNotNull(criadas[0]).slug, assertNotNull(criadas[1]).slug)
    }

    // ---------- Item 18: limites com pedidos simultâneos ----------

    @Test
    fun `dois lotes de guarda-sois ao mesmo tempo nao passam do limite`() {
        val q = apoio.quiosque("Barraca Revisao Lote")
        val barraca = barracas.findById(q.barracaId).orElseThrow() // limite padrão: 30

        val r =
            juntos(
                { guardaSolService.criarLote(barraca, LoteForm("1", "20"), BeanPropertyBindingResult(Any(), "f")) },
                { guardaSolService.criarLote(barraca, LoteForm("21", "40"), BeanPropertyBindingResult(Any(), "f")) },
            )

        assertEquals(1, r.count { it != null }, "só um dos dois cabe")
        assertEquals(20, guardaSois.countByBarracaIdAndAtivoTrue(q.barracaId))
    }

    // ---------- Itens 6 e 7: convite e troca de senha ----------

    @Test
    fun `o mesmo convite usado ao mesmo tempo cria uma conta so`() {
        val token = convites.criar(null, "revisao").token
        val r =
            juntos(
                *(1..4)
                    .map { i -> { contaService.cadastrar(token, "Dono $i", painel.novoEmail("convite"), SENHA) } }
                    .toTypedArray(),
            )

        assertEquals(1, r.count { it is br.com.mesamar.conta.CadastroResultado.Ok })
    }

    @Test
    fun `o mesmo link de troca de senha usado ao mesmo tempo so vale uma vez`() {
        val email = painel.novoEmail("reset")
        painel.donoLogado(email)
        recuperacao.solicitar(email)
        val token = assertNotNull(Regex("token=([A-Za-z0-9_-]+)").find(assertNotNull(emails.ultimoLink(email)))).groupValues[1]

        val r = juntos({ recuperacao.redefinir(token, "senha nova 1 longa") }, { recuperacao.redefinir(token, "senha nova 2 longa") })

        assertEquals(1, r.count { it })
    }

    @Test
    fun `pedidos simultaneos de troca de senha deixam um link valido so`() {
        val email = painel.novoEmail("reset2")
        painel.donoLogado(email)
        val usuario = assertNotNull(usuarios.findByEmailIgnoreCase(email))

        juntos({ recuperacao.solicitar(email) }, { recuperacao.solicitar(email) }, { recuperacao.solicitar(email) })

        assertEquals(1, resets.findAllByUsuarioIdAndUsadoEmIsNull(usuario.id).size)
    }

    // ---------- Item 11: senha em bytes ----------

    @Test
    fun `senha longa demais em bytes no login e so senha errada, nao erro interno`() {
        val q = apoio.quiosque("Barraca Revisao Senha")
        val r = StaffApp(port).post("/api/staff/entrar/dono", """{"email":"${q.email}","senha":"${"é".repeat(60)}"}""")
        assertEquals(401, r.statusCode(), r.body())
    }
}
