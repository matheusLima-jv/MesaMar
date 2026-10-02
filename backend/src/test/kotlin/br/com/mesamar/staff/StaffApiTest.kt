package br.com.mesamar.staff

import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.equipe.AtendenteRepository
import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.PainelTestSupport.Companion.SENHA
import br.com.mesamar.support.TestIps
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** O app do quiosque visto de fora: um "celular" com cookie de sessão e o token CSRF. */
class StaffApp(
    port: Int,
) {
    val http = HttpTestClient(port, TestIps.next(), cookies = true)
    var csrf: String = ""

    fun sessao(): HttpResponse<String> =
        http.get("/api/staff/sessao").also { r -> Regex(""""csrf":"([^"]+)"""").find(r.body())?.let { csrf = it.groupValues[1] } }

    fun post(
        path: String,
        json: String = "{}",
        comCsrf: Boolean = true,
    ): HttpResponse<String> {
        if (csrf.isEmpty()) sessao()
        val headers = if (comCsrf) mapOf("X-CSRF-TOKEN" to csrf) else emptyMap()
        return http.postJson(path, json, headers).also { r ->
            Regex(""""csrf":"([^"]+)"""").find(r.body())?.let { csrf = it.groupValues[1] }
        }
    }

    fun get(path: String): HttpResponse<String> = http.get(path)
}

@IntegrationTest
class StaffApiTest(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    private val usuarios: UsuarioRepository,
    private val atendentes: AtendenteRepository,
    private val produtos: ProdutoRepository,
) {
    private val painel = PainelTestSupport(port, convites)

    private fun barracaId(email: String): UUID = assertNotNull(usuarios.findByEmailIgnoreCase(email)?.barracaId)

    private fun codigo(email: String): String =
        assertNotNull(
            Regex("""<span class="mono">([a-z0-9-]+)</span>""").find(painelDe(email).get("/painel/barraca").body()),
        ).groupValues[1]

    private val sessoesPainel = mutableMapOf<String, HttpTestClient>()

    private fun painelDe(email: String): HttpTestClient = sessoesPainel.getValue(email)

    /** Dono com barraca, 1 categoria com "Coco", e a atendente "Carla" (PIN 258014). */
    private fun barracaPronta(nome: String = "Barraca App"): String {
        val email = painel.novoEmail("app")
        val http = painel.donoComBarraca(nomeBarraca = nome, email = email)
        sessoesPainel[email] = http
        painel.post(http, "/painel/cardapio", "/painel/cardapio/categorias", mapOf("nome" to "Bebidas"))
        http.get("/painel/cardapio")
        val cat =
            Regex(
                """/painel/cardapio/produtos/novo\?categoria=([0-9a-f-]+)""",
            ).find(http.get("/painel/cardapio").body())!!.groupValues[1]
        painel.post(
            http,
            "/painel/cardapio/produtos/novo",
            "/painel/cardapio/produtos",
            mapOf(
                "categoriaId" to cat,
                "nome" to "Coco",
                "preco" to "12",
            ),
        )
        painel.post(http, "/painel/equipe", "/painel/equipe", mapOf("nome" to "Carla", "pin" to "258014"))
        http.get("/painel/equipe")
        return email
    }

    private fun carla(email: String) = atendentes.findAllByBarracaIdOrderByNome(barracaId(email)).first { it.nome == "Carla" }

    private fun coco(email: String) = produtos.findAllByBarracaIdOrderByOrdem(barracaId(email)).first { it.nome == "Coco" }

    private fun donoNoApp(email: String): StaffApp =
        StaffApp(port).also { assertEquals(200, it.post("/api/staff/entrar/dono", """{"email":"$email","senha":"$SENHA"}""").statusCode()) }

    private fun carlaNoApp(email: String): StaffApp =
        StaffApp(port).also {
            val r =
                it.post(
                    "/api/staff/entrar/atendente",
                    """{"codigo":"${codigo(email)}","atendenteId":"${carla(email).id}","pin":"258014"}""",
                )
            assertEquals(200, r.statusCode(), r.body())
        }

    @Test
    fun `sem login a sessao diz que nao esta logado e o resto responde 401 em JSON`() {
        val app = StaffApp(port)
        val sessao = app.sessao()
        assertEquals(200, sessao.statusCode())
        assertContains(sessao.body(), "\"logado\":false")
        assertTrue(app.csrf.isNotBlank())

        val cardapio = app.get("/api/staff/cardapio")
        assertEquals(401, cardapio.statusCode())
        assertContains(cardapio.body(), "\"erro\":\"sessao\"")
    }

    @Test
    fun `dono entra com e-mail e senha e ve o cardapio da barraca`() {
        val email = barracaPronta("Quiosque do Dono")

        val errado = StaffApp(port).post("/api/staff/entrar/dono", """{"email":"$email","senha":"outra senha qualquer"}""")
        assertEquals(401, errado.statusCode())
        assertContains(errado.body(), "E-mail ou senha não conferem.")

        val app = donoNoApp(email.uppercase())
        val sessao = app.sessao().body()
        assertContains(sessao, "\"logado\":true")
        assertContains(sessao, "\"papel\":\"DONO\"")
        assertContains(sessao, "\"nome\":\"Quiosque do Dono\"")

        val cardapio = app.get("/api/staff/cardapio")
        assertEquals(200, cardapio.statusCode())
        assertContains(cardapio.body(), "\"nome\":\"Bebidas\"")
        assertContains(cardapio.body(), "\"nome\":\"Coco\"")
        assertContains(cardapio.body(), "\"precoCentavos\":1200")
    }

    @Test
    fun `POST sem o token CSRF e recusado`() {
        val email = barracaPronta()
        val app = StaffApp(port)
        app.sessao()

        val semToken = app.post("/api/staff/entrar/dono", """{"email":"$email","senha":"$SENHA"}""", comCsrf = false)

        assertEquals(403, semToken.statusCode())
        assertContains(semToken.body(), "\"erro\":\"csrf\"")
    }

    @Test
    fun `dono sem barraca e mandado para o painel`() {
        val email = painel.novoEmail()
        painel.donoLogado(email)

        val r = StaffApp(port).post("/api/staff/entrar/dono", """{"email":"$email","senha":"$SENHA"}""")

        assertEquals(409, r.statusCode())
        assertContains(r.body(), "\"erro\":\"sem-barraca\"")
    }

    @Test
    fun `atendente entra com codigo da barraca, nome e PIN, com bloqueio`() {
        val email = barracaPronta()
        val codigo = codigo(email)
        val carla = carla(email)

        val lista = StaffApp(port).get("/api/staff/barracas/${codigo.uppercase()}/atendentes")
        assertEquals(200, lista.statusCode())
        assertContains(lista.body(), "\"nome\":\"Carla\"")
        assertFalse(lista.body().contains("pin"), "a lista não expõe nada além de id e nome")
        assertEquals(404, StaffApp(port).get("/api/staff/barracas/nao-existe/atendentes").statusCode())

        val app = StaffApp(port)
        val body = { pin: String -> """{"codigo":"$codigo","atendenteId":"${carla.id}","pin":"$pin"}""" }
        val primeira = app.post("/api/staff/entrar/atendente", body("9999"))
        assertEquals(401, primeira.statusCode())
        assertContains(primeira.body(), "Restam 4 tentativas")
        repeat(3) { app.post("/api/staff/entrar/atendente", body("9999")) }
        val quinta = app.post("/api/staff/entrar/atendente", body("9999"))
        assertEquals(423, quinta.statusCode())
        assertContains(quinta.body(), "\"erro\":\"bloqueado\"")
        assertEquals(423, app.post("/api/staff/entrar/atendente", body("258014")).statusCode(), "bloqueada, nem o PIN certo entra")

        painel.post(painelDe(email), "/painel/equipe", "/painel/equipe/${carla.id}/desbloquear", emptyMap())
        val ok = app.post("/api/staff/entrar/atendente", body("258014"))
        assertEquals(200, ok.statusCode())
        assertContains(ok.body(), "\"papel\":\"ATENDENTE\"")
        assertEquals(200, app.get("/api/staff/cardapio").statusCode())
    }

    @Test
    fun `atendente desativado perde o acesso na hora`() {
        val email = barracaPronta()
        val app = carlaNoApp(email)
        assertEquals(200, app.get("/api/staff/cardapio").statusCode())

        painel.post(painelDe(email), "/painel/equipe", "/painel/equipe/${carla(email).id}/ativo", mapOf("ativo" to "false"))

        assertEquals(401, app.get("/api/staff/cardapio").statusCode())
        assertContains(app.sessao().body(), "\"logado\":false")
    }

    @Test
    fun `atendente marca esgotado e o painel do dono ve na hora`() {
        val email = barracaPronta()
        val app = carlaNoApp(email)
        val coco = coco(email)

        val r = app.post("/api/staff/produtos/${coco.id}/esgotado", """{"esgotado":true}""")
        assertEquals(200, r.statusCode(), r.body())
        assertContains(r.body(), "\"esgotado\":true")
        // Repetir a mesma chamada (ex.: reenvio) não desfaz.
        app.post("/api/staff/produtos/${coco.id}/esgotado", """{"esgotado":true}""")
        assertTrue(produtos.findById(coco.id).orElseThrow().esgotado)
        assertContains(painelDe(email).get("/painel/cardapio").body(), "Voltar a vender Coco")
    }

    @Test
    fun `app de uma barraca nao mexe no cardapio de outra`() {
        val a = barracaPronta("Barraca A do App")
        val cocoA = coco(a)
        val b = barracaPronta("Barraca B do App")
        val appB = donoNoApp(b)

        val r = appB.post("/api/staff/produtos/${cocoA.id}/esgotado", """{"esgotado":true}""")

        assertEquals(404, r.statusCode())
        assertFalse(produtos.findById(cocoA.id).orElseThrow().esgotado)
        assertFalse(appB.get("/api/staff/cardapio").body().contains(cocoA.id.toString()))
        // PIN da Carla de A com o código da barraca B: não entra.
        val cruzado =
            StaffApp(
                port,
            ).post("/api/staff/entrar/atendente", """{"codigo":"${codigo(b)}","atendenteId":"${carla(a).id}","pin":"258014"}""")
        assertEquals(404, cruzado.statusCode())
    }

    @Test
    fun `login do painel nao vale no app e sair encerra o app`() {
        val email = barracaPronta()
        // A sessão do /painel (mesmo navegador) não dá acesso à API do app.
        assertEquals(401, painelDe(email).get("/api/staff/cardapio").statusCode())

        val app = donoNoApp(email)
        assertEquals(204, app.post("/api/staff/sair").statusCode())
        assertEquals(401, app.get("/api/staff/cardapio").statusCode())
    }
}
