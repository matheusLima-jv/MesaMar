package br.com.mesamar.barraca

import br.com.mesamar.cardapio.CategoriaRepository
import br.com.mesamar.cardapio.ProdutoRepository
import br.com.mesamar.comanda.AbertaPor
import br.com.mesamar.comanda.CheckinRequest
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.comanda.ResultadoCheckin
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.pagamento.PagamentoRepository
import br.com.mesamar.staff.StaffApp
import br.com.mesamar.support.ClienteTestSupport
import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.Item
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.PainelTestSupport.Companion.SENHA
import br.com.mesamar.support.PainelTestSupport.Companion.csrfFrom
import br.com.mesamar.support.TestIps
import br.com.mesamar.support.header
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A barraca de demonstração: o /admin cria e reinicia, e tudo nela avisa que é treino (Fase 7 · checkpoint 7.2). */
@IntegrationTest
class DemoBarracaTest(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
    private val usuarios: UsuarioRepository,
    categorias: CategoriaRepository,
    produtos: ProdutoRepository,
    private val checkin: CheckinService,
    private val barracas: BarracaRepository,
    private val comandas: ComandaRepository,
    private val pagamentos: PagamentoRepository,
    private val jdbc: JdbcTemplate,
) {
    private val painel = PainelTestSupport(port, convites)
    private val apoio = ClienteTestSupport(port, painel, usuarios, categorias, produtos, checkin)

    private class Credenciais(
        val email: String,
        val senha: String,
        val codigo: String,
        val pin: String,
    )

    private fun admin(): HttpTestClient {
        val http = HttpTestClient(port, TestIps.next(), cookies = true)
        val csrf = csrfFrom(http.get("/admin/login").body())
        http.postForm("/admin/login", mapOf("username" to "admin", "password" to "senha-de-teste", "_csrf" to csrf))
        return http
    }

    /** Aperta "criar ou reiniciar" no /admin e lê as credenciais que aparecem uma vez. */
    private fun criarOuReiniciar(admin: HttpTestClient = admin()): Credenciais {
        val r = admin.postForm("/admin/demo", mapOf("_csrf" to csrfFrom(admin.get("/admin").body())))
        assertEquals(302, r.statusCode(), r.body())
        assertContains(r.header("Location"), "/admin#demo")
        val html = admin.get("/admin").body()

        fun valor(rotulo: String) =
            assertNotNull(Regex("""$rotulo</dt><dd class="mono">([^<]+)</dd>""").find(html), "faltou '$rotulo'").groupValues[1]
        val credenciais =
            Credenciais(valor("E-mail do dono"), valor("Senha do dono"), valor("Código da barraca \\(app\\)"), valor("PIN da atendente"))
        // As credenciais aparecem uma vez só
        assertFalse(admin.get("/admin").body().contains(credenciais.senha), "a senha não pode aparecer de novo")
        return credenciais
    }

    private fun demoId() = barracas.findAll().single { it.demo }.id

    private fun donoNoApp(
        c: Credenciais,
        senha: String = c.senha,
    ): Pair<StaffApp, Int> {
        val app = StaffApp(port)
        val r = app.post("/api/staff/entrar/dono", """{"email":"${c.email}","senha":"$senha"}""")
        return app to r.statusCode()
    }

    private fun atendenteNoApp(
        c: Credenciais,
        pin: String = c.pin,
    ): Int {
        val app = StaffApp(port)
        val lista = app.get("/api/staff/barracas/${c.codigo}/atendentes").body()
        val id = assertNotNull(Regex(""""id":"([0-9a-f-]{36})"""").find(lista)).groupValues[1]
        return app.post("/api/staff/entrar/atendente", """{"codigo":"${c.codigo}","atendenteId":"$id","pin":"$pin"}""").statusCode()
    }

    private fun contar(
        tabela: String,
        barracaId: UUID,
    ) = jdbc.queryForObject("SELECT COUNT(*) FROM $tabela WHERE barraca_id = ?", Int::class.java, barracaId) ?: 0

    @Test
    fun `so o admin cria a barraca de demonstracao`() {
        val anonimo = HttpTestClient(port, TestIps.next(), cookies = true)
        val csrf = csrfFrom(anonimo.get("/admin/login").body())

        val r = anonimo.postForm("/admin/demo", mapOf("_csrf" to csrf))

        assertEquals(302, r.statusCode())
        assertContains(r.header("Location"), "/admin/login")
    }

    @Test
    fun `cria a barraca com cardapio, guarda-sois, equipe e clientes de exemplo, tudo marcado como treino`() {
        val c = criarOuReiniciar()
        val id = demoId()
        val barraca = barracas.findById(id).orElseThrow()

        assertEquals(true, barraca.demo)
        assertEquals("Barraca Demo (treino)", barraca.nome)
        assertEquals(DemoService.CHAVE_PIX_FICTICIA, barraca.chavePix, "a chave Pix é fictícia")
        assertEquals(6, contar("produto", id))
        assertEquals(2, contar("categoria", id))
        assertEquals(20, contar("guarda_sol", id))
        assertEquals(1, contar("atendente", id))
        assertEquals(2, contar("comanda", id))
        assertEquals(2, contar("pedido", id))
        assertEquals(0, contar("pagamento", id))

        // O app mostra a faixa de treino e a fila já tem os pedidos de exemplo
        val (app, status) = donoNoApp(c)
        assertEquals(200, status)
        assertContains(app.get("/api/staff/sessao").body(), "\"demo\":true")
        val fila = app.get("/api/staff/fila").body()
        assertContains(fila, "Ana (treino)")
        assertContains(fila, "Beto (treino)")
        assertEquals(200, atendenteNoApp(c), "a atendente entra com o PIN mostrado")

        // O painel do dono mostra a faixa
        val painelDono = painel.session().also { assertEquals(302, painel.login(it, c.email, c.senha).statusCode()) }
        assertContains(painelDono.get("/painel").body(), "Barraca de treino: nada aqui é de verdade")

        // E a página do cliente também
        val token = comandas.findAll().first { it.barracaId == id }.tokenAcesso
        assertContains(apoio.celular().get("/api/c/$token").body(), "\"demo\":true")
    }

    @Test
    fun `o QR Pix da demonstracao leva a uma chave que nao e de ninguem`() {
        criarOuReiniciar()
        val token = comandas.findAll().first { it.barracaId == demoId() }.tokenAcesso

        val r = apoio.celular().postJson("/api/c/$token/conta/fechar", """{"metodo":"PIX"}""")

        assertEquals(200, r.statusCode(), r.body())
        assertContains(r.body(), DemoService.CHAVE_PIX_FICTICIA)
        assertContains(r.body(), "BARRACA DEMO")
    }

    @Test
    fun `reiniciar apaga o que o treino gerou, troca a senha e o PIN e nao mexe em outras barracas`() {
        val antigas = criarOuReiniciar()
        val id = demoId()
        val (app, _) = donoNoApp(antigas)
        // O treino mexe nas regras, faz check-in, pede, recebe e pausa
        val token = novaComandaNaDemo(id, "Treinando")
        apoio.pedir(apoio.celular(), token, UUID.randomUUID(), Item(produtoDa(id, "Água de coco"), 1))
        assertEquals(
            201,
            app
                .post(
                    "/api/staff/comandas/${comandas.findByTokenAcesso(token)!!.id}/pagamentos",
                    """{"id":"${UUID.randomUUID()}","metodo":"PIX","valorCentavos":1200}""",
                ).statusCode(),
        )
        assertEquals(200, app.post("/api/staff/pausa", """{"pausado":true,"mensagem":"treino"}""").statusCode())
        assertEquals(
            201,
            app.post("/api/staff/problemas", """{"mensagem":"aviso de treino","tela":"fila","conexao":"online"}""").statusCode(),
        )
        jdbc.update("UPDATE barraca SET modo_pagamento = 'POR_PEDIDO', limite_saldo_aberto_centavos = 100 WHERE id = ?", id)
        assertEquals(3, contar("comanda", id))
        assertEquals(1, pagamentos.findAll().count { it.barracaId == id })

        // Uma barraca de verdade, com dados, ao lado
        val real = apoio.quiosque("Barraca Real Ao Lado")
        val tokenReal = apoio.novaComanda(real, "Cliente Real")
        apoio.pedir(apoio.celular(), tokenReal, UUID.randomUUID(), Item(real.coco.id, 2))
        StaffApp(port).also {
            assertEquals(200, it.post("/api/staff/entrar/dono", """{"email":"${real.email}","senha":"$SENHA"}""").statusCode())
            assertEquals(201, it.post("/api/staff/problemas", """{"mensagem":"aviso de verdade"}""").statusCode())
        }
        val antesDaReal =
            listOf("comanda", "pedido", "item_pedido", "cliente", "produto", "problema_reportado").map { contar(it, real.barracaId) }

        val novas = criarOuReiniciar()

        assertEquals(id, demoId(), "é a mesma barraca")
        assertEquals(1, barracas.findAll().count { it.demo }, "continua uma só")
        assertEquals(2, contar("comanda", id), "só os dois clientes de exemplo")
        assertEquals(0, contar("pagamento", id))
        assertEquals(0, contar("problema_reportado", id), "os avisos do treino somem")
        assertEquals(6, contar("produto", id))
        val barraca = barracas.findById(id).orElseThrow()
        assertEquals(ModoPagamento.FINAL, barraca.modoPagamento)
        assertEquals(null, barraca.limiteSaldoAbertoCentavos)
        assertFalse(barraca.pedidosPausados)
        // Credenciais novas; as antigas deixam de valer
        assertNotEquals(antigas.senha, novas.senha)
        assertEquals(401, donoNoApp(antigas).second)
        assertEquals(401, app.get("/api/staff/cardapio").statusCode(), "a sessão aberta antes do reinício cai")
        assertEquals(200, donoNoApp(novas).second)
        assertEquals(200, atendenteNoApp(novas))
        // A barraca real não mudou
        assertEquals(
            antesDaReal,
            listOf("comanda", "pedido", "item_pedido", "cliente", "produto", "problema_reportado").map { contar(it, real.barracaId) },
        )
        assertEquals(0, contar("pagamento", real.barracaId))
        assertContains(apoio.celular().get("/api/c/$tokenReal").body(), "\"demo\":false")
    }

    @Test
    fun `a senha do dono demo fica guardada so como hash`() {
        val c = criarOuReiniciar()

        val hash = usuarios.findByEmailIgnoreCase(c.email)!!.senhaHash

        assertNotEquals(c.senha, hash)
        assertTrue(hash.startsWith("{bcrypt}"), "o formato do hash não é o do encoder da aplicação")
        assertFalse(
            jdbc.queryForList("SELECT pin_hash FROM atendente WHERE barraca_id = ?", String::class.java, demoId()).any { it == c.pin },
        )
    }

    @Test
    fun `uma barraca de verdade nao tem faixa de treino`() {
        val q = apoio.quiosque("Barraca Sem Faixa")
        val dono = painel.session().also { painel.login(it, q.email) }

        assertFalse(dono.get("/painel").body().contains("Barraca de treino"))
        val app = StaffApp(port)
        app.post("/api/staff/entrar/dono", """{"email":"${q.email}","senha":"${PainelTestSupport.SENHA}"}""")
        assertContains(app.get("/api/staff/sessao").body(), "\"demo\":false")
    }

    // ---------- apoio ----------

    private fun produtoDa(
        barracaId: UUID,
        nome: String,
    ): UUID = jdbc.queryForObject("SELECT id FROM produto WHERE barraca_id = ? AND nome = ?", UUID::class.java, barracaId, nome)!!

    /** Um check-in feito no quiosque da demonstração (pelo dono dela). */
    private fun novaComandaNaDemo(
        barracaId: UUID,
        nome: String,
    ): String {
        val dono = usuarios.findAll().first { it.barracaId == barracaId }
        val r = checkin.checkin(barracaId, AbertaPor(dono.id, null), CheckinRequest(nome = nome, consentimento = true))
        return (r as ResultadoCheckin.Ok).detalhe.comanda.tokenAcesso
    }
}
