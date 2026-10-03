package br.com.mesamar.staff

import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.common.Retencao
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.equipe.AtendenteRepository
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.PainelTestSupport.Companion.SENHA
import br.com.mesamar.support.Quiosque
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** Fase 6.3: o dono vê quando cada aparelho da equipe sincronizou pela última vez e o que ficou guardado nele. */
@IntegrationTest
class SincroniaAparelhoTest(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    checkin: CheckinService,
    private val atendentes: AtendenteRepository,
    private val jdbc: JdbcTemplate,
    private val retencao: Retencao,
) {
    private val painel = PainelTestSupport(port, convites)
    private val apoio = ClienteTestSupport(port, painel, usuarios, categorias, produtos, checkin)

    private fun dono(q: Quiosque): StaffApp =
        StaffApp(port).also {
            assertEquals(200, it.post("/api/staff/entrar/dono", """{"email":"${q.email}","senha":"$SENHA"}""").statusCode())
        }

    private fun painelDoDono(q: Quiosque) = painel.session().also { painel.login(it, q.email) }

    private fun atendente(
        q: Quiosque,
        nome: String,
    ): StaffApp {
        val http = painelDoDono(q)
        painel.post(http, "/painel/equipe", "/painel/equipe", mapOf("nome" to nome, "pin" to "258014"))
        val codigo =
            assertNotNull(
                Regex("""<span class="mono">([a-z0-9-]+)</span>""").find(http.get("/painel/barraca").body()),
            ).groupValues[1]
        val atendente = atendentes.findAllByBarracaIdOrderByNome(q.barracaId).first { it.nome == nome }
        return StaffApp(port).also {
            val r = it.post("/api/staff/entrar/atendente", """{"codigo":"$codigo","atendenteId":"${atendente.id}","pin":"258014"}""")
            assertEquals(200, r.statusCode(), r.body())
        }
    }

    private fun informar(
        app: StaffApp,
        aparelho: UUID,
        pendentes: Int,
        conflitos: Int = 0,
        userAgent: String = ANDROID,
    ): HttpResponse<String> {
        if (app.csrf.isEmpty()) app.sessao()
        return app.http.postJson(
            "/api/staff/sincronizacao",
            """{"aparelhoId":"$aparelho","pendentes":$pendentes,"conflitos":$conflitos}""",
            mapOf("X-CSRF-TOKEN" to app.csrf, "User-Agent" to userAgent),
        )
    }

    private fun aparelhos(q: Quiosque): String {
        val pagina = painelDoDono(q).get("/painel/equipe").body()
        return pagina.substringAfter("Aparelhos da equipe")
    }

    @Test
    fun `o dono ve a ultima sincronizacao e as acoes guardadas em cada aparelho`() {
        val q = apoio.quiosque("Barraca Aparelhos")
        assertContains(aparelhos(q), "Nenhum aparelho sincronizou ainda")

        val carla = atendente(q, "Carla")
        val celular = UUID.randomUUID()
        assertEquals(204, informar(carla, celular, pendentes = 2).statusCode())
        assertEquals(204, informar(dono(q), UUID.randomUUID(), pendentes = 0, userAgent = IPHONE).statusCode())

        val lista = aparelhos(q)
        assertContains(lista, "Carla · Android")
        assertContains(lista, "Última sincronização:")
        assertContains(lista, "2 ações aguardando envio")
        assertContains(lista, "· iPhone/iPad")
        assertContains(lista, "Tudo enviado")

        // O mesmo aparelho informa de novo: atualiza a linha, não cria outra
        assertEquals(204, informar(carla, celular, pendentes = 0).statusCode())
        assertEquals(1, linhas(q.barracaId, celular))
        assertEquals(
            0,
            jdbc.queryForObject("SELECT pendentes FROM sincronizacao_aparelho WHERE aparelho_id = ?", Int::class.java, celular),
        )
    }

    @Test
    fun `acao parada ha mais de 15 minutos ou recusada aparece com atencao`() {
        val q = apoio.quiosque("Barraca Aparelho Parado")
        val carla = atendente(q, "Carla")
        val parado = UUID.randomUUID()
        assertEquals(204, informar(carla, parado, pendentes = 3).statusCode())
        assertFalse(aparelhos(q).contains("Atenção:"), "acabou de sincronizar: ainda não é atraso")

        jdbc.update("UPDATE sincronizacao_aparelho SET sincronizado_em = now() - interval '40 minutes' WHERE aparelho_id = ?", parado)
        assertContains(aparelhos(q), "Atenção: 3 ações aguardando envio")

        assertEquals(204, informar(dono(q), UUID.randomUUID(), pendentes = 0, conflitos = 1).statusCode())
        assertContains(aparelhos(q), "Atenção: Tudo enviado · 1 recusada: confira no aparelho")
    }

    @Test
    fun `isolamento - uma barraca nao ve nem altera o aparelho de outra, mesmo com o mesmo id`() {
        val a = apoio.quiosque("Barraca Aparelho A")
        val b = apoio.quiosque("Barraca Aparelho B")
        val mesmo = UUID.randomUUID()
        assertEquals(204, informar(atendente(a, "Ana"), mesmo, pendentes = 5).statusCode())
        assertEquals(204, informar(atendente(b, "Bia"), mesmo, pendentes = 0).statusCode())

        assertEquals(
            5,
            jdbc.queryForObject("SELECT pendentes FROM sincronizacao_aparelho WHERE barraca_id = ?", Int::class.java, a.barracaId),
        )
        assertEquals(1, linhas(a.barracaId, mesmo))
        assertEquals(1, linhas(b.barracaId, mesmo))
        val listaA = aparelhos(a)
        val listaB = aparelhos(b)
        assertContains(listaA, "Ana · Android")
        assertFalse(listaA.contains("Bia"))
        assertContains(listaB, "Bia · Android")
        assertFalse(listaB.contains("Ana"))
    }

    @Test
    fun `sem login, sem token de seguranca ou com valores fora da faixa nada e gravado`() {
        val q = apoio.quiosque("Barraca Aparelho Validacao")
        val app = dono(q)
        val aparelho = UUID.randomUUID()

        val anonimo = StaffApp(port)
        assertEquals(401, informar(anonimo, aparelho, pendentes = 1).statusCode())
        assertEquals(
            403,
            app
                .post(
                    "/api/staff/sincronizacao",
                    """{"aparelhoId":"$aparelho","pendentes":1,"conflitos":0}""",
                    comCsrf = false,
                ).statusCode(),
        )
        listOf(
            """{"aparelhoId":"$aparelho","pendentes":201,"conflitos":0}""",
            """{"aparelhoId":"$aparelho","pendentes":-1,"conflitos":0}""",
            """{"aparelhoId":"$aparelho","pendentes":0}""",
            """{"pendentes":0,"conflitos":0}""",
            """{"aparelhoId":"nao-e-uuid","pendentes":0,"conflitos":0}""",
        ).forEach { corpo -> assertEquals(400, app.post("/api/staff/sincronizacao", corpo).statusCode(), corpo) }
        assertEquals(0, linhas(q.barracaId, aparelho))
    }

    @Test
    fun `aparelho sem sincronizar ha mais de 30 dias sai da lista`() {
        val q = apoio.quiosque("Barraca Aparelho Antigo")
        val antigo = UUID.randomUUID()
        val recente = UUID.randomUUID()
        val app = dono(q)
        assertEquals(204, informar(app, antigo, pendentes = 0).statusCode())
        assertEquals(204, informar(app, recente, pendentes = 0).statusCode())
        jdbc.update("UPDATE sincronizacao_aparelho SET sincronizado_em = now() - interval '31 days' WHERE aparelho_id = ?", antigo)

        retencao.limpar()

        assertEquals(0, linhas(q.barracaId, antigo))
        assertEquals(1, linhas(q.barracaId, recente))
    }

    private fun linhas(
        barracaId: UUID,
        aparelho: UUID,
    ): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM sincronizacao_aparelho WHERE barraca_id = ? AND aparelho_id = ?",
            Int::class.java,
            barracaId,
            aparelho,
        )!!

    companion object {
        const val ANDROID = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
        const val IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile Safari/604.1"
    }
}
