package br.com.mesamar.problema

import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.staff.StaffApp
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.PainelTestSupport.Companion.SENHA
import br.com.mesamar.support.PainelTestSupport.Companion.csrfFrom
import br.com.mesamar.support.Quiosque
import br.com.mesamar.support.TestIps
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** "Reportar problema" nos dois apps (Fase 7 · checkpoint 7.3). */
@IntegrationTest
class ProblemasApiTest(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    checkin: CheckinService,
    private val comandas: ComandaRepository,
    private val jdbc: JdbcTemplate,
) {
    private val painel = PainelTestSupport(port, convites)
    private val apoio = ClienteTestSupport(port, painel, usuarios, categorias, produtos, checkin)

    private fun app(q: Quiosque): StaffApp =
        StaffApp(port).also {
            assertEquals(200, it.post("/api/staff/entrar/dono", """{"email":"${q.email}","senha":"$SENHA"}""").statusCode())
        }

    private fun linhas(q: Quiosque) =
        jdbc.queryForList("SELECT * FROM problema_reportado WHERE barraca_id = ? ORDER BY criado_em", q.barracaId)

    private fun corpo(
        mensagem: String?,
        tela: String? = "fila",
        conexao: String? = "online · 4g",
    ) = "{" +
        listOfNotNull(
            mensagem?.let {
                "\"mensagem\":${aspas(it)}"
            },
            tela?.let { "\"tela\":\"$it\"" },
            conexao?.let { "\"conexao\":\"$it\"" },
        ).joinToString(",") +
        "}"

    private fun aspas(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test
    fun `o quiosque reporta com a tela e a conexao, e fica ligado a barraca de quem reportou`() {
        val q = apoio.quiosque("Barraca Problema Quiosque")
        val outra = apoio.quiosque("Barraca Problema Outra")

        val r = app(q).post("/api/staff/problemas", corpo("O pedido do guarda-sol 5 não apareceu"))

        assertEquals(201, r.statusCode(), r.body())
        val linha = linhas(q).single()
        assertEquals("QUIOSQUE", linha["origem"])
        assertEquals("fila", linha["tela"])
        assertEquals("online · 4g", linha["conexao"])
        assertEquals("O pedido do guarda-sol 5 não apareceu", linha["mensagem"])
        assertEquals(q.usuarioId, linha["usuario_id"])
        assertNotNull(linha["criado_em"], "o horário é do servidor")
        assertTrue(linhas(outra).isEmpty(), "não aparece na outra barraca")
    }

    @Test
    fun `o cliente reporta pelo token e a barraca e a comanda vem dele`() {
        val q = apoio.quiosque("Barraca Problema Cliente")
        val token = apoio.novaComanda(q, "Ana")

        val r =
            apoio.celular().postJson(
                "/api/c/$token/problemas",
                corpo("Não consigo fechar a conta", tela = "conta", conexao = "offline"),
            )

        assertEquals(201, r.statusCode(), r.body())
        val linha = linhas(q).single()
        assertEquals("CLIENTE", linha["origem"])
        assertEquals(comandas.findByTokenAcesso(token)!!.id, linha["comanda_id"])
        assertEquals("conta", linha["tela"])
        assertFalse(linha.values.any { it.toString().contains("Ana") }, "o nome do cliente não é guardado")
    }

    @Test
    fun `mensagem curta ou ausente e recusada, e o texto e limpo e cortado`() {
        val q = apoio.quiosque("Barraca Problema Texto")
        val app = app(q)

        assertEquals(400, app.post("/api/staff/problemas", corpo("  a ")).statusCode())
        assertEquals(400, app.post("/api/staff/problemas", corpo(null)).statusCode())
        assertEquals(400, app.post("/api/staff/problemas", "não é json").statusCode())
        assertTrue(linhas(q).isEmpty())

        assertEquals(201, app.post("/api/staff/problemas", corpo("x".repeat(900), tela = null, conexao = null)).statusCode())
        val linha = linhas(q).single()
        assertEquals(500, (linha["mensagem"] as String).length)
        assertEquals("desconhecida", linha["tela"])
        assertEquals("desconhecida", linha["conexao"])
    }

    @Test
    fun `quem reporta demais e freado`() {
        val q = apoio.quiosque("Barraca Problema Limite")
        val app = app(q)
        val token = apoio.novaComanda(q)
        val celular = apoio.celular()

        val staff = (1..11).map { app.post("/api/staff/problemas", corpo("aviso número $it")).statusCode() }
        val cliente = (1..6).map { celular.postJson("/api/c/$token/problemas", corpo("aviso número $it")).statusCode() }

        assertEquals(10, staff.count { it == 201 })
        assertEquals(429, staff.last())
        assertEquals(5, cliente.count { it == 201 })
        assertEquals(429, cliente.last())
    }

    @Test
    fun `sem entrar, sem token de seguranca ou com token desconhecido nada e gravado`() {
        val q = apoio.quiosque("Barraca Problema Acesso")

        assertEquals(401, StaffApp(port).post("/api/staff/problemas", corpo("sem login")).statusCode())
        assertEquals(403, app(q).post("/api/staff/problemas", corpo("sem csrf"), comCsrf = false).statusCode())
        assertEquals(404, apoio.celular().postJson("/api/c/${"Z".repeat(22)}/problemas", corpo("token inventado")).statusCode())
        assertTrue(linhas(q).isEmpty())
    }

    @Test
    fun `o admin ve os problemas com a barraca e o contexto, e texto com HTML nao vira HTML`() {
        val q = apoio.quiosque("Barraca Problema Admin")
        app(q).post("/api/staff/problemas", corpo("<script>alert(1)</script> quebrou", tela = "pagamentos", conexao = "offline"))
        val admin = HttpTestClient(port, TestIps.next(), cookies = true)
        val anonimo = admin.get("/admin")
        assertEquals(302, anonimo.statusCode(), "sem login, vai para o login")
        admin.postForm(
            "/admin/login",
            mapOf(
                "username" to "admin",
                "password" to "senha-de-teste",
                "_csrf" to csrfFrom(admin.get("/admin/login").body()),
            ),
        )

        val html = admin.get("/admin").body()

        assertContains(html, "Problemas reportados")
        assertContains(html, "Barraca Problema Admin")
        assertContains(html, "pagamentos")
        assertContains(html, "offline")
        assertContains(html, "Quiosque")
        assertContains(html, "&lt;script&gt;alert(1)&lt;/script&gt; quebrou")
        assertFalse(html.contains("<script>alert(1)"))
    }
}
