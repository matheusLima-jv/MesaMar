package br.com.mesamar.comanda

import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.common.Fuso
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.pagamento.PagamentoRepository
import br.com.mesamar.relatorio.RelatorioService
import br.com.mesamar.staff.StaffApp
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.Item
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.PainelTestSupport.Companion.SENHA
import br.com.mesamar.support.Quiosque
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** LGPD: depois de 90 dias o nome e o WhatsApp somem, e os valores ficam (Fase 5 · checkpoint 5.3). */
@IntegrationTest
class AnonimizacaoTest(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    checkin: CheckinService,
    private val comandas: ComandaRepository,
    private val clientes: ClienteRepository,
    private val pagamentos: PagamentoRepository,
    private val relatorios: RelatorioService,
    private val anonimizacao: AnonimizacaoService,
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    private val painel = PainelTestSupport(port, convites)
    private val apoio = ClienteTestSupport(port, painel, usuarios, categorias, produtos, checkin)

    private fun app(q: Quiosque): StaffApp =
        StaffApp(port).also {
            assertEquals(200, it.post("/api/staff/entrar/dono", """{"email":"${q.email}","senha":"$SENHA"}""").statusCode())
        }

    private fun clienteDe(token: String) = clientes.findById(comandas.findByTokenAcesso(token)!!.clienteId).orElseThrow()

    private fun idDe(token: String) = comandas.findByTokenAcesso(token)!!.id

    private fun envelhecer(
        token: String,
        dias: Int,
    ) {
        jdbc.update(
            "UPDATE cliente SET criado_em = now() - make_interval(days => ?) WHERE id = ?",
            dias,
            comandas.findByTokenAcesso(token)!!.clienteId,
        )
    }

    /** Check-in com WhatsApp (o teste põe o número direto no banco). */
    private fun checkinComZap(
        q: Quiosque,
        nome: String,
    ): String {
        val token = apoio.novaComanda(q, nome)
        jdbc.update("UPDATE cliente SET whatsapp_e164 = '+5513999998888' WHERE id = ?", comandas.findByTokenAcesso(token)!!.clienteId)
        return token
    }

    private fun hoje() = Fuso.hoje(clock)

    @Test
    fun `depois de 90 dias o nome vira Cliente e o WhatsApp e apagado, e os valores ficam`() {
        val q = apoio.quiosque("Barraca LGPD")
        val app = app(q)
        val velha = checkinComZap(q, "Ana Silva")
        apoio.pedir(apoio.celular(), velha, UUID.randomUUID(), Item(q.coco.id, 2), Item(q.pastel.id, 1))
        val pago =
            app.post(
                "/api/staff/comandas/${idDe(velha)}/pagamentos",
                """{"id":"${UUID.randomUUID()}","metodo":"PIX","valorCentavos":4900}""",
            )
        assertEquals(201, pago.statusCode(), pago.body())
        assertEquals(200, app.post("/api/staff/comandas/${idDe(velha)}/encerrar").statusCode())
        val antes = relatorios.dia(q.barracaId, hoje())
        envelhecer(velha, 91)

        assertTrue(anonimizacao.anonimizar() >= 1)

        val cliente = clienteDe(velha)
        assertEquals("Cliente", cliente.nome)
        assertNull(cliente.whatsappE164)
        assertNotNull(cliente.anonimizadoEm)
        // O que a barraca precisa para as contas continua igual
        val depois = relatorios.dia(q.barracaId, hoje())
        assertEquals(antes.recebidoCentavos, depois.recebidoCentavos)
        assertEquals(antes.vendidoCentavos, depois.vendidoCentavos)
        assertEquals(antes.maisVendidos, depois.maisVendidos)
        assertEquals(4900, pagamentos.totalCentavos(idDe(velha)))
        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM item_pedido i JOIN pedido p ON p.id = i.pedido_id WHERE p.comanda_id = ?",
                Int::class.java,
                idDe(velha),
            ),
        )
        // A página do cliente, se alguém abrir o link, já não mostra o nome
        assertContains(apoio.celular().get("/api/c/$velha").body(), "\"nome\":\"Cliente\"")
    }

    @Test
    fun `quem tem menos de 90 dias nao e tocado`() {
        val q = apoio.quiosque("Barraca LGPD Recente")
        val token = checkinComZap(q, "Beto")
        envelhecer(token, 89)

        anonimizacao.anonimizar()

        val cliente = clienteDe(token)
        assertEquals("Beto", cliente.nome)
        assertEquals("+5513999998888", cliente.whatsappE164)
        assertNull(cliente.anonimizadoEm)
    }

    @Test
    fun `quem ainda tem comanda aberta, fechando ou bloqueada fica de fora ate fechar`() {
        val q = apoio.quiosque("Barraca LGPD Devendo")
        val devendo = checkinComZap(q, "Caio")
        val fechando = checkinComZap(q, "Dani")
        val bloqueado = checkinComZap(q, "Edu")
        apoio.pedir(apoio.celular(), fechando, UUID.randomUUID(), Item(q.coco.id, 1))
        assertEquals(200, apoio.celular().postJson("/api/c/$fechando/conta/fechar", """{"metodo":"CARTAO"}""").statusCode())
        assertEquals(200, app(q).post("/api/staff/comandas/${idDe(bloqueado)}/bloquear").statusCode())
        listOf(devendo, fechando, bloqueado).forEach { envelhecer(it, 120) }

        anonimizacao.anonimizar()

        listOf(devendo to "Caio", fechando to "Dani", bloqueado to "Edu").forEach { (token, nome) ->
            assertEquals(nome, clienteDe(token).nome, "$nome ainda deve ou pode dever: o dono precisa saber quem é")
            assertNotNull(clienteDe(token).whatsappE164)
        }
        // Fechada a conta, na próxima rodada é anonimizado; as outras continuam como estavam
        assertEquals(200, app(q).post("/api/staff/comandas/${idDe(devendo)}/encerrar").statusCode())
        anonimizacao.anonimizar()
        assertEquals("Cliente", clienteDe(devendo).nome)
        assertEquals("Dani", clienteDe(fechando).nome)
        assertEquals("Edu", clienteDe(bloqueado).nome)
    }

    @Test
    fun `rodar de novo nao anonimiza duas vezes`() {
        val q = apoio.quiosque("Barraca LGPD Repete")
        val token = checkinComZap(q, "Fabi")
        envelhecer(token, 100)
        anonimizacao.anonimizar()
        val primeira = clienteDe(token).anonimizadoEm

        anonimizacao.anonimizar()

        assertEquals(primeira, clienteDe(token).anonimizadoEm, "a data da anonimização não muda")
    }

    @Test
    fun `o job agendado roda de madrugada no fuso das barracas`() {
        val agendado = AgendadorAnonimizacao::class.java.getMethod("rodar").getAnnotation(Scheduled::class.java)

        assertEquals("America/Sao_Paulo", agendado.zone)
        assertContains(agendado.cron, "3 * * *")
    }

    @Test
    fun `os avisos de problema com mais de 90 dias sao apagados e os recentes ficam`() {
        val q = apoio.quiosque("Barraca LGPD Problemas")

        fun aviso(diasAtras: Int): UUID =
            UUID.randomUUID().also {
                jdbc.update(
                    "INSERT INTO problema_reportado (id, barraca_id, origem, tela, mensagem, conexao, criado_em) " +
                        "VALUES (?, ?, 'QUIOSQUE', 'fila', 'travou', 'online', now() - make_interval(days => ?))",
                    it,
                    q.barracaId,
                    diasAtras,
                )
            }
        val velho = aviso(91)
        val recente = aviso(89)

        anonimizacao.anonimizar()

        fun existe(id: UUID) = jdbc.queryForObject("SELECT COUNT(*) FROM problema_reportado WHERE id = ?", Int::class.java, id) == 1
        assertTrue(!existe(velho), "o aviso de 91 dias some")
        assertTrue(existe(recente), "o de 89 dias fica")
    }
}
