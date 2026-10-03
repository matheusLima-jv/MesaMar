package br.com.mesamar.staff

import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.comanda.ClienteRepository
import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.comanda.StatusComanda
import br.com.mesamar.common.MailProperties
import br.com.mesamar.common.SmtpOrLogEmailGateway
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.equipe.Atendente
import br.com.mesamar.equipe.AtendenteRepository
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.Item
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.PainelTestSupport.Companion.SENHA
import br.com.mesamar.support.Quiosque
import br.com.mesamar.support.SseTestClient
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.mail.javamail.JavaMailSender
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Correções da revisão de código, lote 3: segurança e dados pessoais (itens 49 a 54, 57 a 59). */
@IntegrationTest
class CorrecoesRevisao3Test(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    checkin: CheckinService,
    private val comandas: ComandaRepository,
    private val clientes: ClienteRepository,
    private val atendentes: AtendenteRepository,
) {
    private val painel = PainelTestSupport(port, convites)
    private val apoio = ClienteTestSupport(port, painel, usuarios, categorias, produtos, checkin)

    private fun dono(q: Quiosque): StaffApp =
        StaffApp(port).also {
            assertEquals(200, it.post("/api/staff/entrar/dono", """{"email":"${q.email}","senha":"$SENHA"}""").statusCode())
        }

    private class Equipe(
        val carla: Atendente,
        val codigo: String,
        val painel: HttpTestClient,
    )

    /** A barraca ganha a atendente Carla (PIN 258014). Devolve também o painel logado do dono. */
    private fun comCarla(q: Quiosque): Equipe {
        val http = painel.session().also { painel.login(it, q.email) }
        painel.post(http, "/painel/equipe", "/painel/equipe", mapOf("nome" to "Carla", "pin" to "258014"))
        val codigo =
            assertNotNull(
                Regex("""<span class="mono">([a-z0-9-]+)</span>""").find(http.get("/painel/barraca").body()),
            ).groupValues[1]
        return Equipe(atendentes.findAllByBarracaIdOrderByNome(q.barracaId).first { it.nome == "Carla" }, codigo, http)
    }

    private fun entrarCarla(e: Equipe): StaffApp =
        StaffApp(port).also {
            val r = it.post("/api/staff/entrar/atendente", """{"codigo":"${e.codigo}","atendenteId":"${e.carla.id}","pin":"258014"}""")
            assertEquals(200, r.statusCode(), r.body())
        }

    private fun comandaId(token: String): UUID = comandas.findByTokenAcesso(token)!!.id

    // ---------- Item 50: revogar sessões ----------

    @Test
    fun `sair de todos os aparelhos derruba a sessao da atendente, e o PIN continua entrando`() {
        val q = apoio.quiosque("Barraca Revoga 1")
        val e = comCarla(q)
        val sessao = entrarCarla(e)
        assertEquals(200, sessao.get("/api/staff/fila").statusCode())

        val r = painel.post(e.painel, "/painel/equipe", "/painel/equipe/${e.carla.id}/revogar-sessoes", emptyMap())

        assertEquals(302, r.statusCode(), r.body())
        assertEquals(401, sessao.get("/api/staff/fila").statusCode(), "a sessão antiga caiu")
        assertEquals(200, entrarCarla(e).get("/api/staff/fila").statusCode(), "o PIN continua valendo para entrar de novo")
    }

    @Test
    fun `desativar e reativar nao faz a sessao antiga voltar a valer`() {
        val q = apoio.quiosque("Barraca Revoga 2")
        val e = comCarla(q)
        val antiga = entrarCarla(e)

        painel.post(e.painel, "/painel/equipe", "/painel/equipe/${e.carla.id}/ativo", mapOf("ativo" to "false"))
        assertEquals(401, antiga.get("/api/staff/fila").statusCode())
        painel.post(e.painel, "/painel/equipe", "/painel/equipe/${e.carla.id}/ativo", mapOf("ativo" to "true"))

        assertEquals(401, antiga.get("/api/staff/fila").statusCode(), "reativar não ressuscita a sessão de antes")
        assertEquals(200, entrarCarla(e).get("/api/staff/fila").statusCode(), "um login novo funciona")
    }

    @Test
    fun `o dono de outra barraca nao revoga as sessoes da atendente alheia`() {
        val a = apoio.quiosque("Barraca Revoga A")
        val b = apoio.quiosque("Barraca Revoga B")
        val e = comCarla(a)
        val sessao = entrarCarla(e)
        val painelB = painel.session().also { painel.login(it, b.email) }

        painel.post(painelB, "/painel/equipe", "/painel/equipe/${e.carla.id}/revogar-sessoes", emptyMap())

        assertEquals(200, sessao.get("/api/staff/fila").statusCode(), "a sessão da barraca A segue de pé")
        assertEquals(null, atendentes.findById(e.carla.id).orElseThrow().sessoesRevogadasEm)
    }

    // ---------- Item 49: conexões em tempo real abertas ----------

    @Test
    fun `desativar o atendente derruba a conexao de tempo real dele, e nao a do dono`() {
        val q = apoio.quiosque("Barraca Revoga SSE")
        val e = comCarla(q)
        val carla = entrarCarla(e)
        val dono = dono(q)

        SseTestClient(carla.http.stream("/api/staff/eventos")).use { streamCarla ->
            SseTestClient(dono.http.stream("/api/staff/eventos")).use { streamDono ->
                assertEquals(200, streamCarla.status)
                assertEquals(200, streamDono.status)

                painel.post(e.painel, "/painel/equipe", "/painel/equipe/${e.carla.id}/ativo", mapOf("ativo" to "false"))

                assertTrue(streamCarla.encerrado(), "a conexão da atendente desativada cai, sem esperar a próxima abertura")
                assertFalse(streamDono.encerrado(1_500), "a do dono segue aberta")
            }
        }
    }

    // ---------- Item 51: limite por conta ----------

    @Test
    fun `tentativas de senha espalhadas por muitos IPs batem no limite da conta`() {
        val email = "conta-${UUID.randomUUID()}@exemplo.com"
        val respostas =
            (1..StaffController.LOGIN_LIMITE_CONTA + 1).map {
                // Cada aparelho simulado tem o seu próprio IP: o limite por IP nunca é atingido
                StaffApp(port).post("/api/staff/entrar/dono", """{"email":"$email","senha":"errada errada"}""").statusCode()
            }

        assertTrue(respostas.take(StaffController.LOGIN_LIMITE_CONTA).all { it == 401 }, "até o limite, só senha errada: $respostas")
        assertEquals(429, respostas.last(), "a tentativa seguinte é freada, venha de onde vier")
    }

    @Test
    fun `o mesmo vale para o login do painel`() {
        val email = "painel-${UUID.randomUUID()}@exemplo.com"
        val respostas = (1..16).map { painel.login(painel.session(), email, "errada errada").statusCode() }

        assertTrue(respostas.take(15).none { it == 429 }, "$respostas")
        assertEquals(429, respostas.last())
    }

    // ---------- Item 53: e-mail sem SMTP ----------

    private fun logsDo(
        logarCorpo: Boolean,
        envio: () -> Unit,
    ): List<String> {
        val logger = LoggerFactory.getLogger(SmtpOrLogEmailGateway::class.java) as Logger
        val coletor = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(coletor)
        try {
            envio()
        } finally {
            logger.detachAppender(coletor)
        }
        return coletor.list.map { it.formattedMessage }
    }

    @Test
    fun `sem SMTP o link de troca de senha nao vai para o log, a menos que o desenvolvimento ligue`() {
        val semSmtp = StaticListableBeanFactory().getBeanProvider(JavaMailSender::class.java)
        val corpo = "Abra https://exemplo/painel/nova-senha?token=SEGREDO-123"

        val producao = logsDo(false) { SmtpOrLogEmailGateway(semSmtp, MailProperties()).send("dono@exemplo.com", "Troca", corpo) }
        assertTrue(producao.isNotEmpty(), "ainda avisa que não enviou")
        assertFalse(
            producao.any { it.contains("SEGREDO-123") || it.contains("dono@exemplo.com") },
            "nada de credencial nem e-mail: $producao",
        )

        val dev =
            logsDo(
                true,
            ) { SmtpOrLogEmailGateway(semSmtp, MailProperties(logarCorpoSemSmtp = true)).send("dono@exemplo.com", "Troca", corpo) }
        assertTrue(dev.any { it.contains("SEGREDO-123") }, "no desenvolvimento o link aparece, para testar à mão")
    }

    // ---------- Item 59: versão do texto de consentimento ----------

    private fun checkin(
        app: StaffApp,
        versao: String?,
    ): String {
        val campo = versao?.let { ""","consentimentoVersao":"$it"""" }.orEmpty()
        val r = app.post("/api/staff/clientes", """{"id":"${UUID.randomUUID()}","nome":"Teste","consentimento":true$campo}""")
        assertEquals(201, r.statusCode(), r.body())
        return assertNotNull(Regex(""""link":"[^"]*/c/([A-Za-z0-9_-]+)"""").find(r.body())).groupValues[1]
    }

    @Test
    fun `o check-in registra a versao do texto de consentimento que o app mostrou`() {
        val app = dono(apoio.quiosque("Barraca Consentimento"))

        fun versaoDe(token: String) = clientes.findById(comandas.findByTokenAcesso(token)!!.clienteId).orElseThrow().consentimentoVersao

        assertEquals(CheckinService.CONSENTIMENTO_VERSAO_ATUAL, versaoDe(checkin(app, CheckinService.CONSENTIMENTO_VERSAO_ATUAL)))
        assertEquals("desconhecida", versaoDe(checkin(app, null)), "app antigo em cache, sem a versão")
        assertEquals("desconhecida", versaoDe(checkin(app, "<script>alert(1)</script>")), "formato estranho não é gravado")
    }

    // ---------- Item 54: QR novo ----------

    @Test
    fun `QR novo troca o link, o antigo para de abrir, e a comanda e os pedidos ficam`() {
        val q = apoio.quiosque("Barraca QR Novo")
        val velho = apoio.novaComanda(q, "Ana")
        val comanda = comandaId(velho)
        val pedido = UUID.randomUUID()
        assertEquals(201, apoio.pedir(apoio.celular(), velho, pedido, Item(q.coco.id, 2)).statusCode())
        val app = dono(q)

        SseTestClient(apoio.celular().stream("/api/c/$velho/eventos")).use { stream ->
            assertEquals(200, stream.status)
            val r = app.post("/api/staff/comandas/$comanda/novo-qr")

            assertEquals(200, r.statusCode(), r.body())
            val novo = assertNotNull(Regex(""""link":"[^"]*/c/([A-Za-z0-9_-]+)"""").find(r.body())).groupValues[1]
            assertNotEquals(velho, novo)
            assertEquals(22, novo.length)
            assertTrue(stream.encerrado(), "a conexão em tempo real do link antigo cai")

            assertEquals(404, apoio.celular().get("/api/c/$velho").statusCode(), "o link antigo não abre mais")
            val nova = apoio.celular().get("/api/c/$novo")
            assertEquals(200, nova.statusCode())
            assertEquals(comanda, comandaId(novo), "é a mesma comanda")
            assertContains(apoio.celular().get("/api/c/$novo/pedidos").body(), "$pedido")
        }
    }

    @Test
    fun `a pagina antiga ainda descobre que o pedido que reenviava ja tinha chegado, e so isso`() {
        val q = apoio.quiosque("Barraca QR Novo 2")
        val velho = apoio.novaComanda(q)
        val pedido = UUID.randomUUID()
        assertEquals(201, apoio.pedir(apoio.celular(), velho, pedido, Item(q.coco.id, 1)).statusCode())
        assertEquals(200, dono(q).post("/api/staff/comandas/${comandaId(velho)}/novo-qr").statusCode())

        val reenvio = apoio.pedir(apoio.celular(), velho, pedido, Item(q.coco.id, 1))
        assertEquals(200, reenvio.statusCode(), "o pedido que já existe é reconhecido pelo link antigo")
        assertContains(reenvio.body(), "$pedido")

        assertEquals(404, apoio.pedir(apoio.celular(), velho, UUID.randomUUID(), Item(q.coco.id, 1)).statusCode(), "pedido novo, não")
        assertEquals(404, apoio.celular().get("/api/c/$velho/pedidos").statusCode(), "e nada mais abre pelo link antigo")
    }

    @Test
    fun `QR novo respeita a barraca e so vale para comanda ativa`() {
        val a = apoio.quiosque("Barraca QR Novo A")
        val b = apoio.quiosque("Barraca QR Novo B")
        val token = apoio.novaComanda(a)
        val comanda = comandaId(token)

        assertEquals(404, dono(b).post("/api/staff/comandas/$comanda/novo-qr").statusCode(), "dono de outra barraca")
        assertEquals(200, apoio.celular().get("/api/c/$token").statusCode(), "o link não mudou")

        val c = comandas.findById(comanda).orElseThrow()
        c.status = StatusComanda.FECHADA
        comandas.save(c)
        assertEquals(404, dono(a).post("/api/staff/comandas/$comanda/novo-qr").statusCode(), "comanda encerrada")
        assertEquals(401, StaffApp(port).post("/api/staff/comandas/$comanda/novo-qr").statusCode(), "sem login")
    }
}
