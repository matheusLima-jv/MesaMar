package br.com.mesamar.problema

import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.header
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** Os avisos de problema no painel do dono: só os da própria barraca, escapados e sem o navegador de quem avisou. */
@IntegrationTest
class ProblemasPainelTest(
    @Value("\${local.server.port}") port: Int,
    convites: ConviteService,
    private val usuarios: UsuarioRepository,
    private val problemas: ProblemaService,
) {
    private val painel = PainelTestSupport(port, convites)

    private fun barracaId(email: String): UUID = assertNotNull(usuarios.findByEmailIgnoreCase(email)?.barracaId)

    private fun avisar(
        barracaId: UUID,
        origem: String,
        mensagem: String,
        tela: String = "fila",
        conexao: String = "online",
    ) {
        problemas.registrar(barracaId, origem, ProblemaRequest(mensagem, tela, conexao), agente = "NavegadorSecreto/1.0")
    }

    @Test
    fun `o dono ve os avisos da propria barraca, do cliente e da equipe, e nao o navegador de quem avisou`() {
        val email = painel.novoEmail("avisos")
        val http = painel.donoComBarraca(email = email)
        avisar(barracaId(email), "QUIOSQUE", "A fila não carrega", tela = "fila", conexao = "offline")
        avisar(barracaId(email), "CLIENTE", "Não consegui pagar", tela = "conta", conexao = "online · 4g")

        val r = http.get("/painel/problemas")

        assertEquals(200, r.statusCode())
        listOf("A fila não carrega", "Não consegui pagar", "Equipe", "Cliente", "conta", "online · 4g", "offline").forEach {
            assertContains(r.body(), it)
        }
        assertFalse(r.body().contains("NavegadorSecreto"), "o navegador só aparece no /admin")
        assertContains(http.get("/painel").body(), "/painel/problemas", message = "o menu tem o link")
    }

    @Test
    fun `isolamento - o dono de outra barraca nao ve nenhum aviso alheio`() {
        val a = painel.novoEmail("avisos-a")
        painel.donoComBarraca(email = a)
        avisar(barracaId(a), "CLIENTE", "Segredo da barraca A")
        val b = painel.novoEmail("avisos-b")
        val httpB = painel.donoComBarraca(email = b)
        avisar(barracaId(b), "CLIENTE", "Aviso da barraca B")

        val r = httpB.get("/painel/problemas").body()

        assertContains(r, "Aviso da barraca B")
        assertFalse(r.contains("Segredo da barraca A"))
    }

    @Test
    fun `texto com HTML aparece escapado e barraca sem avisos mostra a mensagem vazia`() {
        val vazio = painel.donoComBarraca()
        assertContains(vazio.get("/painel/problemas").body(), "Nenhum aviso até agora.")

        val email = painel.novoEmail("avisos-html")
        val http = painel.donoComBarraca(email = email)
        avisar(barracaId(email), "CLIENTE", "<script>alert(1)</script> quebrou")

        val html = http.get("/painel/problemas").body()
        assertContains(html, "&lt;script&gt;alert(1)&lt;/script&gt; quebrou")
        assertFalse(html.contains("<script>alert(1)"))
    }

    @Test
    fun `sem entrar vai para o login, e dono sem barraca vai cadastrar a barraca`() {
        val anonimo = painel.anonymous().get("/painel/problemas")
        assertEquals(302, anonimo.statusCode())
        assertContains(anonimo.header("Location"), "/painel/entrar")

        val semBarraca = painel.donoLogado().get("/painel/problemas")
        assertEquals(302, semBarraca.statusCode())
        assertContains(semBarraca.header("Location"), "/painel/barraca")
    }
}
