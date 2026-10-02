package br.com.mesamar.staff

import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.eventos.HubEventos
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.Item
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.PainelTestSupport.Companion.SENHA
import br.com.mesamar.support.Quiosque
import br.com.mesamar.support.SseTestClient
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tempo real: SSE alimentado pelo LISTEN/NOTIFY do PostgreSQL, separado por barraca e por comanda (Fase 4 · 4.1). */
@IntegrationTest
class TempoRealApiTest(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    checkin: CheckinService,
    private val hub: HubEventos,
) {
    private val apoio = ClienteTestSupport(port, PainelTestSupport(port, convites), usuarios, categorias, produtos, checkin)

    private fun app(q: Quiosque): StaffApp =
        StaffApp(port).also {
            assertEquals(200, it.post("/api/staff/entrar/dono", """{"email":"${q.email}","senha":"$SENHA"}""").statusCode())
        }

    private fun ouvirQuiosque(app: StaffApp) = SseTestClient(app.http.stream("/api/staff/eventos"))

    private fun ouvirComanda(token: String) = SseTestClient(apoio.celular().stream("/api/c/$token/eventos"))

    private fun pedir(
        token: String,
        q: Quiosque,
    ): UUID {
        val r = apoio.pedir(apoio.celular(), token, UUID.randomUUID(), Item(q.coco.id, 1))
        assertEquals(201, r.statusCode(), r.body())
        return UUID.fromString(assertNotNull(Regex(""""id":"([0-9a-f-]{36})"""").find(r.body())).groupValues[1])
    }

    @Test
    fun `pedido novo avisa so o app da barraca certa e so a comanda certa`() {
        val a = apoio.quiosque("Barraca Tempo Real A")
        val b = apoio.quiosque("Barraca Tempo Real B")
        val ana = apoio.novaComanda(a, "Ana")
        val beto = apoio.novaComanda(a, "Beto")
        val appA = app(a)
        val appB = app(b)

        ouvirQuiosque(appA).use { quiosqueA ->
            ouvirQuiosque(appB).use { quiosqueB ->
                ouvirComanda(ana).use { paginaAna ->
                    ouvirComanda(beto).use { paginaBeto ->
                        assertEquals(200, quiosqueA.status)
                        assertEquals(200, paginaAna.status)

                        val id = pedir(ana, a)

                        assertNotNull(quiosqueA.esperar("event:fila"), "o app da barraca A recebe o pedido novo")
                        assertContains(assertNotNull(quiosqueA.esperar("data:")), "$id")
                        assertNotNull(paginaAna.esperar("event:pedido"), "a página da Ana recebe")
                        assertTrue(quiosqueB.nada("event:fila"), "o app da barraca B não recebe nada da A")
                        assertTrue(paginaBeto.nada("event:pedido"), "a página do Beto não recebe o pedido da Ana")

                        // Mudar o status também avisa os dois
                        assertEquals(200, appA.post("/api/staff/pedidos/$id/status", """{"status":"EM_PREPARO"}""").statusCode())
                        assertNotNull(quiosqueA.esperar("event:fila"))
                        assertNotNull(paginaAna.esperar("event:pedido"))
                        assertTrue(paginaBeto.nada("event:pedido"))
                    }
                }
            }
        }
    }

    @Test
    fun `batimento e ressincronizacao chegam a quem esta ouvindo`() {
        val q = apoio.quiosque("Barraca Batimento")
        val token = apoio.novaComanda(q)

        ouvirQuiosque(app(q)).use { quiosque ->
            ouvirComanda(token).use { pagina ->
                hub.batimento()
                assertNotNull(quiosque.esperar(":ping"), "o comentário mantém a conexão viva atrás do proxy")
                assertNotNull(pagina.esperar(":ping"))

                hub.ressincronizar()
                assertNotNull(quiosque.esperar("event:ressincronizar"))
                assertNotNull(pagina.esperar("event:ressincronizar"))
            }
        }
    }

    @Test
    fun `a conexao que some e retirada no proximo batimento`() {
        val q = apoio.quiosque("Barraca Conexao Cai")
        val token = apoio.novaComanda(q)
        val antes = hub.total()

        val pagina = ouvirComanda(token)
        assertNotNull(pagina.esperar(":conectado"))
        assertEquals(antes + 1, hub.total())
        pagina.close()

        // O servidor só nota o celular que sumiu quando tenta escrever: o batimento faz isso
        repeat(25) {
            if (hub.total() > antes) {
                hub.batimento()
                Thread.sleep(200)
            }
        }
        assertTrue(hub.total() <= antes, "não pode sobrar assinante de conexão que caiu: ${hub.total()} (antes: $antes)")
    }

    @Test
    fun `token que nao existe nao abre o fluxo e o uso demais e freado`() {
        val q = apoio.quiosque("Barraca Eventos Limite")
        val token = apoio.novaComanda(q)
        val celular = apoio.celular()

        val invalido = celular.stream("/api/c/${"Y".repeat(22)}/eventos")
        assertEquals(404, invalido.statusCode())
        invalido.body().close()

        repeat(30) {
            celular
                .stream("/api/c/$token/eventos")
                .also { assertEquals(200, it.statusCode()) }
                .body()
                .close()
        }
        val demais = celular.stream("/api/c/$token/eventos")
        assertEquals(429, demais.statusCode())
        demais.body().close()
    }

    @Test
    fun `a pagina nao ouve o fluxo de outra barraca com o token errado`() {
        val a = apoio.quiosque("Barraca Fluxo A")
        val b = apoio.quiosque("Barraca Fluxo B")
        val tokenB = apoio.novaComanda(b)

        ouvirComanda(tokenB).use { paginaB ->
            pedir(apoio.novaComanda(a), a)
            assertTrue(paginaB.nada("event:pedido"), "o pedido da barraca A não chega à comanda da barraca B")
            assertNull(paginaB.esperar("data:", 300))
        }
    }
}
