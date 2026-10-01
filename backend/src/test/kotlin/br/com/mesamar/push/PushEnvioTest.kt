package br.com.mesamar.push

import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.barraca.ModoPagamento
import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.equipe.Atendente
import br.com.mesamar.equipe.AtendenteRepository
import br.com.mesamar.guardasol.GuardaSol
import br.com.mesamar.guardasol.GuardaSolRepository
import br.com.mesamar.pedido.AlertaAtrasoService
import br.com.mesamar.staff.StaffApp
import br.com.mesamar.support.AparelhoDeTeste
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.FakePushServer
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.Item
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.PainelTestSupport.Companion.SENHA
import br.com.mesamar.support.Quiosque
import br.com.mesamar.support.SseTestClient
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import java.security.Signature
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

/** Entrega dos avisos (Web Push cifrado e assinado) e o alerta de pedido atrasado (Fase 4 · 4.2). */
@IntegrationTest
class PushEnvioTest(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    checkin: CheckinService,
    private val barracas: BarracaRepository,
    private val guardaSois: GuardaSolRepository,
    private val atendentes: AtendenteRepository,
    private val subs: PushSubscriptionRepository,
    private val alertas: AlertaAtrasoService,
    private val jdbc: JdbcTemplate,
) {
    private val apoio = ClienteTestSupport(port, PainelTestSupport(port, convites), usuarios, categorias, produtos, checkin)
    private val chavePublicaVapid = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"

    private fun app(q: Quiosque): StaffApp =
        StaffApp(port).also {
            assertEquals(200, it.post("/api/staff/entrar/dono", """{"email":"${q.email}","senha":"$SENHA"}""").statusCode())
        }

    /** O dono ativa os avisos num aparelho cujo "serviço de push" é o servidor falso. */
    private fun inscrever(
        q: Quiosque,
        servidor: FakePushServer,
        caminho: String,
    ): AparelhoDeTeste {
        val aparelho = AparelhoDeTeste()
        val corpo =
            """{"endpoint":"${servidor.endpoint(caminho)}","keys":{"p256dh":"${Base64Url.codificar(aparelho.publicaBytes)}",""" +
                """"auth":"${Base64Url.codificar(aparelho.segredoAuth)}"}}"""
        assertEquals(204, app(q).post("/api/staff/push/inscrever", corpo).statusCode())
        return aparelho
    }

    private fun pedido(
        token: String,
        vararg linhas: Item,
    ): UUID {
        val r = apoio.pedir(apoio.celular(), token, UUID.randomUUID(), *linhas)
        assertEquals(201, r.statusCode(), r.body())
        return UUID.fromString(assertNotNull(Regex(""""id":"([0-9a-f-]{36})"""").find(r.body())).groupValues[1])
    }

    private fun envejecer(
        pedidoId: UUID,
        minutos: Int,
    ) {
        jdbc.update("UPDATE pedido SET criado_em = now() - make_interval(mins => ?) WHERE id = ?", minutos, pedidoId)
    }

    private fun alertaEm(pedidoId: UUID): Instant? =
        jdbc.queryForObject("SELECT alerta_atraso_em FROM pedido WHERE id = ?", java.sql.Timestamp::class.java, pedidoId)?.toInstant()

    // ---------- Pedido novo ----------

    @Test
    fun `pedido novo avisa so os aparelhos da barraca, com o texto cifrado e o servidor assinado`() {
        FakePushServer().use { servidor ->
            val a = apoio.quiosque("Barraca Push Envio A")
            val b = apoio.quiosque("Barraca Push Envio B")
            val aparelhoA = inscrever(a, servidor, "/push/a")
            inscrever(b, servidor, "/push/b")
            val guardaSol = guardaSois.save(GuardaSol(barracaId = a.barracaId, numero = 12, criadoEm = Instant.now()))
            val token = apoio.novaComanda(a, "Ana Silva", guardaSol.id)

            val id = pedido(token, Item(a.coco.id, 2), Item(a.pastel.id, 1))

            assertTrue(servidor.esperar("/push/a"), "o aparelho da barraca A recebe")
            assertTrue(servidor.nada("/push/b"), "o aparelho da barraca B não recebe nada")
            assertEquals(1, servidor.em("/push/a").size)
            val recebido = servidor.em("/push/a").single()

            // O texto só aparece para quem tem a chave do aparelho
            val texto = aparelhoA.decifrar(recebido.corpo)
            assertContains(texto, """"titulo":"Pedido nº 1 · Guarda-sol 12"""")
            assertContains(texto, """"corpo":"3 itens"""")
            assertContains(texto, """"url":"/staff/#/fila"""")
            assertContains(texto, """"tipo":"NOVO_PEDIDO"""")
            assertContains(texto, """"tag":"pedido-$id"""")
            assertFalse(texto.contains("Ana"), "o nome do cliente não vai no aviso: $texto")
            assertFalse(String(recebido.corpo, Charsets.ISO_8859_1).contains("Guarda-sol"), "no corpo da requisição o texto está cifrado")

            // Cabeçalhos do Web Push e assinatura do servidor (VAPID)
            assertEquals("aes128gcm", recebido.cabecalhos["content-encoding"])
            assertEquals("300", recebido.cabecalhos["ttl"])
            assertEquals("high", recebido.cabecalhos["urgency"])
            assertEquals("application/octet-stream", recebido.cabecalhos["content-type"])
            val autorizacao = assertNotNull(recebido.cabecalhos["authorization"])
            assertTrue(autorizacao.startsWith("vapid t="), autorizacao)
            assertTrue(autorizacao.endsWith(", k=$chavePublicaVapid"), autorizacao)
            val (h, c, s) = autorizacao.removePrefix("vapid t=").substringBefore(", k=").split(".")
            assertContains(String(Base64Url.decodificar(c)), """"aud":"http://localhost:${servidor.porta}"""")
            val assinaturaOk =
                Signature.getInstance("SHA256withECDSAinP1363Format").run {
                    initVerify(WebPushCripto.publicaDeBytes(Base64Url.decodificar(chavePublicaVapid)))
                    update("$h.$c".toByteArray())
                    verify(Base64Url.decodificar(s))
                }
            assertTrue(assinaturaOk, "a assinatura VAPID confere com a chave pública")
        }
    }

    @Test
    fun `no balcao o aviso diz Balcao, e com um item so diz 1 item`() {
        FakePushServer().use { servidor ->
            val q = apoio.quiosque("Barraca Push Balcao")
            val aparelho = inscrever(q, servidor, "/push/q")

            pedido(apoio.novaComanda(q), Item(q.coco.id, 1))

            assertTrue(servidor.esperar("/push/q"))
            val texto = aparelho.decifrar(servidor.em("/push/q").single().corpo)
            assertContains(texto, """"titulo":"Pedido nº 1 · Balcão"""")
            assertContains(texto, """"corpo":"1 item"""")
        }
    }

    @Test
    fun `pedido que aguarda pagamento ainda nao avisa o quiosque`() {
        FakePushServer().use { servidor ->
            val q = apoio.quiosque("Barraca Push Pagamento")
            inscrever(q, servidor, "/push/q")
            val barraca = barracas.findById(q.barracaId).orElseThrow()
            barraca.modoPagamento = ModoPagamento.POR_PEDIDO
            barracas.save(barraca)

            pedido(apoio.novaComanda(q), Item(q.coco.id, 1))

            assertTrue(servidor.nada("/push/q"), "o pedido só entra na fila (e avisa) depois do pagamento")
        }
    }

    @Test
    fun `o dono e os atendentes da barraca recebem, e o atendente desativado deixa de receber`() {
        FakePushServer().use { servidor ->
            val q = apoio.quiosque("Barraca Push Equipe")
            val donoAparelho = inscrever(q, servidor, "/push/dono")
            val agora = Instant.now()
            val carla =
                atendentes.save(
                    Atendente(barracaId = q.barracaId, nome = "Carla", pinHash = "x", criadoEm = agora, atualizadoEm = agora),
                )
            val duda =
                atendentes.save(
                    Atendente(barracaId = q.barracaId, nome = "Duda", pinHash = "x", criadoEm = agora, atualizadoEm = agora),
                )
            val aparelhoCarla = AparelhoDeTeste()
            val aparelhoDuda = AparelhoDeTeste()
            listOf(carla to aparelhoCarla, duda to aparelhoDuda).forEach { (at, ap) ->
                val caminho = "/push/${at.nome.lowercase()}"
                subs.save(
                    PushSubscription(
                        barracaId = q.barracaId,
                        usuarioId = null,
                        atendenteId = at.id,
                        endpoint = servidor.endpoint(caminho),
                        p256dh = Base64Url.codificar(ap.publicaBytes),
                        auth = Base64Url.codificar(ap.segredoAuth),
                        criadoEm = agora,
                    ),
                )
            }
            duda.ativo = false
            atendentes.save(duda)

            pedido(apoio.novaComanda(q), Item(q.coco.id, 1))

            assertTrue(servidor.esperar("/push/dono"), "o dono recebe")
            assertTrue(servidor.esperar("/push/carla"), "a atendente ativa recebe")
            assertTrue(servidor.nada("/push/duda"), "a atendente desativada não recebe")
            assertContains(donoAparelho.decifrar(servidor.em("/push/dono").single().corpo), "Pedido nº 1")
            assertContains(aparelhoCarla.decifrar(servidor.em("/push/carla").single().corpo), "Pedido nº 1")
            assertNull(subs.findByEndpoint(servidor.endpoint("/push/duda")), "e a inscrição dela é removida")
        }
    }

    @Test
    fun `o aviso de teste vai so para o aparelho que pediu, da propria barraca`() {
        FakePushServer().use { servidor ->
            val a = apoio.quiosque("Barraca Push Teste A")
            val b = apoio.quiosque("Barraca Push Teste B")
            val aparelhoA = inscrever(a, servidor, "/push/a")
            inscrever(a, servidor, "/push/a-outro")
            inscrever(b, servidor, "/push/b")

            assertEquals(204, app(a).post("/api/staff/push/testar", """{"endpoint":"${servidor.endpoint("/push/a")}"}""").statusCode())

            assertTrue(servidor.esperar("/push/a"))
            assertContains(aparelhoA.decifrar(servidor.em("/push/a").single().corpo), """"titulo":"MesaMar: avisos funcionando"""")
            assertTrue(servidor.nada("/push/a-outro"), "outros aparelhos da barraca não recebem o teste")
            assertTrue(servidor.nada("/push/b"))

            // Nem o dono da barraca B consegue disparar um aviso para o aparelho da A
            assertEquals(404, app(b).post("/api/staff/push/testar", """{"endpoint":"${servidor.endpoint("/push/a")}"}""").statusCode())
            assertEquals(
                404,
                app(a).post("/api/staff/push/testar", """{"endpoint":"https://fcm.googleapis.com/fcm/send/nao-inscrito"}""").statusCode(),
            )
            assertEquals(1, servidor.em("/push/a").size, "continua um aviso só")
        }
    }

    // ---------- Inscrição inválida e serviço fora do ar ----------

    @Test
    fun `inscricao que o servico de push diz que nao existe mais e removida, e erro temporario nao remove`() {
        FakePushServer().use { servidor ->
            val q = apoio.quiosque("Barraca Push 410")
            inscrever(q, servidor, "/push/ok")
            inscrever(q, servidor, "/push/gone")
            inscrever(q, servidor, "/push/notfound")
            inscrever(q, servidor, "/push/fora")
            servidor.responder("/push/gone", 410)
            servidor.responder("/push/notfound", 404)
            servidor.responder("/push/fora", 503)

            pedido(apoio.novaComanda(q), Item(q.coco.id, 1))

            listOf("/push/ok", "/push/gone", "/push/notfound", "/push/fora").forEach { assertTrue(servidor.esperar(it), it) }
            val restantes = { subs.findAllByBarracaId(q.barracaId).map { it.endpoint.substringAfter("/push/") }.toSet() }
            val fim = System.nanoTime() + 5_000_000_000
            while (System.nanoTime() < fim && restantes() != setOf("ok", "fora")) Thread.sleep(25)
            assertEquals(setOf("ok", "fora"), restantes(), "410 e 404 saem; 503 (temporário) e 201 ficam")
            assertNotNull(subs.findByEndpoint(servidor.endpoint("/push/ok"))?.ultimoUsoEm, "a entrega com sucesso marca o último uso")
        }
    }

    @Test
    fun `um servico de push fora do ar nao atrapalha o pedido`() {
        val q = apoio.quiosque("Barraca Push Fora")
        val aparelho = AparelhoDeTeste()
        val endpoint =
            FakePushServer().use { servidor ->
                servidor.endpoint("/push/morto").also {
                    val corpo =
                        """{"endpoint":"$it","keys":{"p256dh":"${Base64Url.codificar(aparelho.publicaBytes)}",""" +
                            """"auth":"${Base64Url.codificar(aparelho.segredoAuth)}"}}"""
                    assertEquals(204, app(q).post("/api/staff/push/inscrever", corpo).statusCode())
                }
            } // o servidor fechou: ninguém atende nesse endereço

        val inicio = System.nanoTime()
        pedido(apoio.novaComanda(q), Item(q.coco.id, 1))

        assertTrue((System.nanoTime() - inicio) / 1_000_000 < 3_000, "o cliente não espera o serviço de push")
        Thread.sleep(500)
        assertNotNull(subs.findByEndpoint(endpoint), "falha de rede não apaga a inscrição")
    }

    // ---------- Pedido atrasado ----------

    @Test
    fun `pedido parado alem do tempo avisa uma vez so, no app e por push`() {
        FakePushServer().use { servidor ->
            val q = apoio.quiosque("Barraca Atraso")
            val aparelho = inscrever(q, servidor, "/push/q")
            val guardaSol = guardaSois.save(GuardaSol(barracaId = q.barracaId, numero = 7, criadoEm = Instant.now()))
            val id = pedido(apoio.novaComanda(q, "Bia", guardaSol.id), Item(q.coco.id, 1))
            assertTrue(servidor.esperar("/push/q"), "o aviso de pedido novo")
            app(q).http.stream("/api/staff/eventos").let(::SseTestClient).use { stream ->
                assertNotNull(stream.esperar(":conectado"))
                assertNull(alertaEm(id), "pedido recente não tem alerta")
                alertas.verificar()
                assertNull(alertaEm(id), "dentro do prazo (10 min), ninguém é avisado")

                envejecer(id, 15)
                alertas.verificar()

                assertNotNull(alertaEm(id))
                assertTrue(servidor.esperar("/push/q", 2), "o aviso de atraso chegou depois do de pedido novo")
                val texto = aparelho.decifrar(servidor.em("/push/q").last().corpo)
                assertContains(texto, """"titulo":"Pedido nº 1 está parado"""")
                assertContains(texto, """"tipo":"PEDIDO_ATRASADO"""")
                assertContains(texto, "Guarda-sol 7 · esperando há 1")
                assertContains(texto, """"url":"/staff/#/fila"""")
                assertNotNull(stream.esperar("event:atraso"), "o app aberto também recebe, para tocar o alerta")
                assertContains(assertNotNull(stream.esperar("data:")), """"numero":1""")

                alertas.verificar()
                alertas.verificar()
                Thread.sleep(800)
                assertEquals(2, servidor.em("/push/q").size, "pedido novo + um único aviso de atraso")
            }
        }
    }

    @Test
    fun `so avisa o que ainda esta na fila e passou do tempo da propria barraca`() {
        val q = apoio.quiosque("Barraca Atraso Regras")
        val token = apoio.novaComanda(q)
        val recente = pedido(token, Item(q.coco.id, 1))
        val atrasado = pedido(token, Item(q.coco.id, 1))
        val preparando = pedido(token, Item(q.coco.id, 1))
        val entregue = pedido(token, Item(q.coco.id, 1))
        val cancelado = pedido(token, Item(q.coco.id, 1))
        val aguardando = pedido(token, Item(q.coco.id, 1))
        envejecer(recente, 5)
        listOf(atrasado, preparando, entregue, cancelado, aguardando).forEach { envejecer(it, 30) }
        jdbc.update("UPDATE pedido SET status = 'EM_PREPARO' WHERE id = ?", preparando)
        jdbc.update("UPDATE pedido SET status = 'ENTREGUE' WHERE id = ?", entregue)
        jdbc.update("UPDATE pedido SET status = 'CANCELADO' WHERE id = ?", cancelado)
        jdbc.update("UPDATE pedido SET status = 'AGUARDANDO_PAGAMENTO' WHERE id = ?", aguardando)

        alertas.verificar()

        assertNotNull(alertaEm(atrasado))
        assertNotNull(alertaEm(preparando), "em preparo também pode atrasar")
        listOf(recente, entregue, cancelado, aguardando).forEach { assertNull(alertaEm(it), "$it") }

        // O tempo é da barraca: com 60 minutos, 30 minutos de espera ainda não é atraso
        val outra = apoio.quiosque("Barraca Atraso Tempo Longo")
        barracas.findById(outra.barracaId).orElseThrow().also {
            it.tempoAlertaPedidoMin = 60
            barracas.save(it)
        }
        val tokenOutra = apoio.novaComanda(outra)
        val folga = pedido(tokenOutra, Item(outra.coco.id, 1))
        val estourou = pedido(tokenOutra, Item(outra.coco.id, 1))
        envejecer(folga, 30)
        envejecer(estourou, 61)

        alertas.verificar()

        assertNull(alertaEm(folga))
        assertNotNull(alertaEm(estourou))
    }

    @Test
    fun `varios servidores verificando ao mesmo tempo avisam cada pedido uma vez so`() {
        FakePushServer().use { servidor ->
            val q = apoio.quiosque("Barraca Atraso Corrida")
            inscrever(q, servidor, "/push/q")
            val token = apoio.novaComanda(q)
            val ids = (1..3).map { pedido(token, Item(q.coco.id, 1)) }
            assertTrue(servidor.esperar("/push/q", 3), "os 3 avisos de pedido novo")
            ids.forEach { envejecer(it, 20) }
            val pool = Executors.newFixedThreadPool(6)

            val avisados =
                try {
                    pool.invokeAll((1..6).map { Callable { alertas.verificar() } }).sumOf { it.get() }
                } finally {
                    pool.shutdown()
                }

            assertEquals(3, avisados, "cada pedido foi pego por um verificador só")
            assertTrue(servidor.esperar("/push/q", 6), "3 de pedido novo + 3 de atraso")
            Thread.sleep(800)
            assertEquals(6, servidor.em("/push/q").size, "nada em dobro")
            ids.forEach { assertNotNull(alertaEm(it)) }
        }
    }

    @Test
    fun `o alerta de atraso so chega aos aparelhos da propria barraca`() {
        FakePushServer().use { servidor ->
            val a = apoio.quiosque("Barraca Atraso Iso A")
            val b = apoio.quiosque("Barraca Atraso Iso B")
            inscrever(a, servidor, "/push/a")
            inscrever(b, servidor, "/push/b")
            val id = pedido(apoio.novaComanda(a), Item(a.coco.id, 1))
            assertTrue(servidor.esperar("/push/a"))
            envejecer(id, 20)

            alertas.verificar()

            assertTrue(servidor.esperar("/push/a", 2))
            assertTrue(servidor.nada("/push/b"), "B não recebe nada de A")
        }
    }
}
