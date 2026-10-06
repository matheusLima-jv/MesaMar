package br.com.mesamar.staff

import br.com.mesamar.admin.AdminQueries
import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.common.CloudflareTurnstileVerifier
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.OwnerLoginSuccessHandler
import br.com.mesamar.conta.OwnerPrincipal
import br.com.mesamar.conta.OwnerUserDetailsService
import br.com.mesamar.conta.Usuario
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.equipe.AtendenteRepository
import br.com.mesamar.equipe.AtendenteService
import br.com.mesamar.problema.ProblemaRequest
import br.com.mesamar.problema.ProblemaService
import br.com.mesamar.push.Base64Url
import br.com.mesamar.push.PushService
import br.com.mesamar.push.PushSubscriptionRepository
import br.com.mesamar.support.AparelhoDeTeste
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.Item
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.PainelTestSupport.Companion.SENHA
import br.com.mesamar.support.Quiosque
import br.com.mesamar.support.header
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.body
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Correções da terceira revisão externa (docs/relatorios/correcoes-terceira-revisao.md). */
@IntegrationTest
class CorrecoesTerceiraRevisaoTest(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    private val usuarios: UsuarioRepository,
    private val checkin: CheckinService,
    private val jdbc: JdbcTemplate,
    private val encoder: PasswordEncoder,
    private val barracas: BarracaRepository,
    private val atendentes: AtendenteRepository,
    private val atendenteService: AtendenteService,
    private val acesso: StaffAcesso,
    private val subs: PushSubscriptionRepository,
    private val queries: AdminQueries,
    private val problemas: ProblemaService,
    private val transacoes: PlatformTransactionManager,
) {
    private val painel = PainelTestSupport(port, convites)
    private val apoio = ClienteTestSupport(port, painel, usuarios, categorias, produtos, checkin)

    private fun dono(q: Quiosque): StaffApp =
        StaffApp(port).also {
            assertEquals(200, it.post("/api/staff/entrar/dono", """{"email":"${q.email}","senha":"$SENHA"}""").statusCode())
        }

    // ---------- A01: o último login grava só a própria coluna ----------

    @Test
    fun `registrar o ultimo login nao desfaz troca de senha nem barraca confirmadas ao mesmo tempo`() {
        val email = painel.novoEmail("login-corrida")
        painel.donoComBarraca(email = email)
        val antes = assertNotNull(usuarios.findByEmailIgnoreCase(email))
        val principal = OwnerUserDetailsService(usuarios).loadUserByUsername(email)
        val senhaNova = requireNotNull(encoder.encode("uma senha nova e longa"))
        var trocou = false

        /** Outra requisição troca a senha (como o link do e-mail) entre a leitura e a gravação do login. */
        fun trocaConcorrente() {
            if (trocou) return
            trocou = true
            jdbc.update("UPDATE usuario SET senha_hash = ?, credenciais_em = now() WHERE id = ?", senhaNova, antes.id)
        }
        val comCorrida =
            object : UsuarioRepository by usuarios {
                override fun findById(id: UUID): Optional<Usuario> = usuarios.findById(id).also { trocaConcorrente() }

                override fun registrarLogin(
                    id: UUID,
                    em: Instant,
                ): Int {
                    trocaConcorrente()
                    return usuarios.registrarLogin(id, em)
                }
            }

        OwnerLoginSuccessHandler(comCorrida, Clock.systemUTC()).onAuthenticationSuccess(
            MockHttpServletRequest(),
            MockHttpServletResponse(),
            UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities),
        )

        assertTrue(trocou)
        val depois = usuarios.findById(antes.id).orElseThrow()
        assertEquals(senhaNova, depois.senhaHash, "a senha nova continua valendo")
        assertNotNull(depois.credenciaisEm)
        assertEquals(antes.barracaId, depois.barracaId)
        assertNotNull(depois.ultimoLoginEm)
    }

    // ---------- A02: a sessão vale para a credencial que o login conferiu ----------

    @Test
    fun `troca de senha confirmada durante a conferencia do login do dono derruba a sessao do app`() {
        val q = apoio.quiosque("Corrida senha app")
        val senhaNova = requireNotNull(encoder.encode("uma senha nova e longa"))
        val donos =
            object : OwnerUserDetailsService(usuarios) {
                override fun loadUserByUsername(username: String): UserDetails =
                    super.loadUserByUsername(username).also {
                        // O hash antigo já foi lido; a troca é confirmada enquanto o BCrypt confere a senha
                        jdbc.update(
                            "UPDATE usuario SET senha_hash = ?, credenciais_em = ? WHERE id = ?",
                            senhaNova,
                            Timestamp.from(Instant.now()),
                            q.usuarioId,
                        )
                    }
            }
        val login = StaffLoginService(donos, encoder, usuarios, barracas, atendentes, atendenteService)

        val principal = assertIs<ResultadoLogin.Ok>(login.entrarDono(q.email, SENHA)).principal

        assertFalse(acesso.valido(principal), "a sessão aberta com a senha antiga não vale depois da troca")
    }

    @Test
    fun `sessao do painel cai com uma troca de senha que o login nao viu, mesmo marcada antes do login`() {
        val email = painel.novoEmail("painel-corrida")
        val http = painel.donoComBarraca(email = email)
        assertEquals(200, http.get("/painel").statusCode())
        // O estado que a corrida deixa: o login conferiu o hash antigo, e a troca, marcada antes, foi confirmada depois
        jdbc.update(
            "UPDATE usuario SET senha_hash = ?, credenciais_em = now() - interval '1 day' WHERE lower(email) = ?",
            encoder.encode("uma senha nova e longa"),
            email,
        )

        val r = http.get("/painel")

        assertEquals(302, r.statusCode())
        assertContains(r.header("Location"), "/painel/entrar")
    }

    @Test
    fun `sessao do painel continua valendo enquanto a senha nao muda`() {
        val email = painel.novoEmail("painel-estavel")
        val http = painel.donoComBarraca(email = email)
        val principal = OwnerUserDetailsService(usuarios).loadUserByUsername(email) as OwnerPrincipal
        assertNull(principal.versaoCredencial)
        assertEquals(200, http.get("/painel").statusCode())
        assertEquals(200, http.get("/painel").statusCode())
    }

    @Test
    fun `revogacao que o login do atendente nao viu derruba a sessao, mesmo marcada antes do login`() {
        val q = apoio.quiosque("Corrida revogacao")
        val http = painel.session().also { painel.login(it, q.email) }
        painel.post(http, "/painel/equipe", "/painel/equipe", mapOf("nome" to "Carla", "pin" to "258014"))
        val codigo =
            assertNotNull(Regex("""<span class="mono">([a-z0-9-]+)</span>""").find(http.get("/painel/barraca").body())).groupValues[1]
        val carla = atendentes.findAllByBarracaIdOrderByNome(q.barracaId).first { it.nome == "Carla" }
        val app = StaffApp(port)
        val r = app.post("/api/staff/entrar/atendente", """{"codigo":"$codigo","atendenteId":"${carla.id}","pin":"258014"}""")
        assertEquals(200, r.statusCode(), r.body())
        assertEquals(200, app.get("/api/staff/fila").statusCode())

        jdbc.update("UPDATE atendente SET sessoes_revogadas_em = now() - interval '1 day' WHERE id = ?", carla.id)

        assertEquals(401, app.get("/api/staff/fila").statusCode())
    }

    // ---------- A05: o link antigo segue o prazo do extrato ----------

    @Test
    fun `link antigo so reconhece o pedido dentro do prazo do extrato`() {
        val q = apoio.quiosque("Link antigo vence")
        val velho = apoio.novaComanda(q)
        val pedido = UUID.randomUUID()
        assertEquals(201, apoio.pedir(apoio.celular(), velho, pedido, Item(q.coco.id, 1)).statusCode())
        val comanda = jdbc.queryForObject("SELECT id FROM comanda WHERE token_acesso = ?", UUID::class.java, velho)!!
        assertNotNull(checkin.novoQr(q.barracaId, comanda))

        jdbc.update("UPDATE comanda SET status = 'FECHADA', fechada_em = now() - interval '6 days 23 hours' WHERE id = ?", comanda)
        assertEquals(200, apoio.pedir(apoio.celular(), velho, pedido, Item(q.coco.id, 1)).statusCode(), "dentro do prazo")

        jdbc.update("UPDATE comanda SET fechada_em = now() - interval '7 days 1 hour' WHERE id = ?", comanda)
        val vencido = apoio.pedir(apoio.celular(), velho, pedido, Item(q.coco.id, 1))
        assertEquals(404, vencido.statusCode(), "depois do prazo, nem o link antigo devolve o pedido")
        assertEquals(apoio.celular().get("/api/c/${"Z".repeat(22)}").body(), vencido.body(), "igual a um link que nunca existiu")
    }

    // ---------- A06: o Portão A conta só aberturas ----------

    @Test
    fun `resumo do sinal conta so as aberturas e mediana sem amostra fica vazia`() {
        TransactionTemplate(transacoes).executeWithoutResult { status ->
            jdbc.update("DELETE FROM medicao_sinal")

            fun medir(
                metrica: String,
                carregamento: Int?,
                ping: Int? = null,
            ) = jdbc.update(
                """INSERT INTO medicao_sinal (id, praia, carregamento_ms, ping_ms, tipo_conexao, criada_em, metrica, sucesso, amostras)
                   VALUES (?, 'ENSEADA', ?, ?, '4g', now(), ?, true, '[{"duracaoMs":900,"sucesso":true}]')""",
                UUID.randomUUID(),
                carregamento,
                ping,
                metrica,
            )
            medir("ABERTURA_FRIA", 2000)
            repeat(9) { medir("FILA", null) }
            medir("PEDIDO", null)
            medir("REABERTURA", 300)

            val r = queries.resumoSinal().single()

            assertEquals(1, r.medicoes, "pedido, fila e reabertura não entram no Portão A")
            assertEquals(100, r.percentualAte5s)
            assertTrue(r.atingeMeta)
            assertEquals(2000, r.medianaCarregamentoMs)
            assertNull(r.medianaPingMs, "sem amostra de servidor, não é zero")
            status.setRollbackOnly()
        }
    }

    // ---------- A07: o teto de aparelhos vale também para quem vem de outra barraca ----------

    @Test
    fun `aparelho que vem de outra barraca respeita o teto da barraca nova`() {
        val a = apoio.quiosque("Push teto origem")
        val b = apoio.quiosque("Push teto destino")
        val appA = dono(a)
        val appB = dono(b)
        val aparelho = AparelhoDeTeste()

        fun inscrever(
            app: StaffApp,
            endpoint: String,
        ) = app.post(
            "/api/staff/push/inscrever",
            """{"endpoint":"$endpoint","keys":{"p256dh":"${Base64Url.codificar(aparelho.publicaBytes)}","auth":"${Base64Url.codificar(
                aparelho.segredoAuth,
            )}"}}""",
        )
        val movido = "https://fcm.googleapis.com/fcm/send/teto-move-${a.barracaId}"
        assertEquals(204, inscrever(appA, movido).statusCode())
        repeat(PushService.MAX_POR_BARRACA) {
            assertEquals(204, inscrever(appB, "https://fcm.googleapis.com/fcm/send/teto-$it-${b.barracaId}").statusCode())
        }

        val r = inscrever(appB, movido)

        assertEquals(400, r.statusCode(), r.body())
        assertEquals(PushService.MAX_POR_BARRACA.toLong(), subs.countByBarracaId(b.barracaId))
        assertEquals(a.barracaId, subs.findByEndpoint(movido)?.barracaId, "a inscrição continua na barraca de origem")
    }

    // ---------- A09: a conferência do Turnstile tem prazo ----------

    @Test
    fun `servidor do Turnstile que aceita e nao responde nao prende a requisicao`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { mudo ->
            val conexoes = mutableListOf<Socket>()
            thread(isDaemon = true) { runCatching { while (true) conexoes += mudo.accept() } }
            val cliente =
                CloudflareTurnstileVerifier.cliente(
                    "http://127.0.0.1:${mudo.localPort}",
                    Duration.ofSeconds(1),
                    Duration.ofMillis(300),
                )
            val inicio = System.nanoTime()

            assertFails {
                cliente
                    .post()
                    .uri("/turnstile/v0/siteverify")
                    .retrieve()
                    .body<String>()
            }

            assertTrue(Duration.ofNanos(System.nanoTime() - inicio) < Duration.ofSeconds(5), "o prazo de leitura encerra a espera")
            conexoes.forEach { it.close() }
        }
        assertEquals(Duration.ofSeconds(5), CloudflareTurnstileVerifier.CONEXAO)
        assertEquals(Duration.ofSeconds(10), CloudflareTurnstileVerifier.LEITURA)
    }

    // ---------- A13: o que vai para o log fica numa linha ----------

    @Test
    fun `tela e conexao do problema reportado ficam numa linha so, e a mensagem guarda as quebras`() {
        val q = apoio.quiosque("Problema numa linha")
        val logger = LoggerFactory.getLogger(ProblemaService::class.java) as Logger
        val coletor = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(coletor)
        try {
            problemas.registrar(
                q.barracaId,
                "CLIENTE",
                ProblemaRequest("Não consigo pedir\nsegunda linha", "cardapio\n2026-10-06 ERROR falso", "4g FALSO\r\nx"),
                "Navegador\u0085X",
            )
        } finally {
            logger.detachAppender(coletor)
        }

        val linha = jdbc.queryForMap("SELECT tela, conexao, mensagem, agente FROM problema_reportado WHERE barraca_id = ?", q.barracaId)
        assertEquals("cardapio 2026-10-06 ERROR falso", linha["tela"])
        assertEquals("4g FALSO x", linha["conexao"])
        assertEquals("Navegador X", linha["agente"])
        assertEquals("Não consigo pedir\nsegunda linha", linha["mensagem"])
        val registrado = coletor.list.single { it.formattedMessage.startsWith("Problema reportado") }.formattedMessage
        assertFalse(registrado.any { it == '\n' || it == '\r' || it == ' ' }, registrado)
    }
}
