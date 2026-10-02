package br.com.mesamar.barraca

import br.com.mesamar.conta.ConviteService
import br.com.mesamar.support.HttpTestClient
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.TestIps
import br.com.mesamar.support.header
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** O guia de 1 página e a plaquinha do balcão, prontos para imprimir (Fase 7 · checkpoint 7.2). */
@IntegrationTest
class MateriaisTest(
    @Value("\${local.server.port}") private val port: Int,
    convites: ConviteService,
) {
    private val painel = PainelTestSupport(port, convites)

    @Test
    fun `sem entrar no painel nao ha materiais`() {
        val anonimo = painel.anonymous()

        listOf("/painel/materiais", "/painel/materiais/guia", "/painel/materiais/plaquinha").forEach {
            val r = anonimo.get(it)
            assertEquals(302, r.statusCode(), it)
            assertContains(r.header("Location"), "/painel/entrar")
        }
    }

    @Test
    fun `quem ainda nao cadastrou a barraca volta para o cadastro`() {
        val dono = painel.donoLogado()

        listOf("/painel/materiais", "/painel/materiais/guia", "/painel/materiais/plaquinha").forEach {
            assertContains(dono.get(it).header("Location"), "/painel/barraca", message = it)
        }
    }

    @Test
    fun `o guia traz o nome e o codigo da barraca e as regras que importam`() {
        val dono = painel.donoComBarraca("Barraca Guia Teste")

        val guia = dono.get("/painel/materiais/guia")

        assertEquals(200, guia.statusCode())
        listOf(
            "Guia rápido da equipe",
            "Barraca Guia Teste",
            "barraca-guia-teste",
            "/staff/",
            "Check-in do cliente",
            "Atender a fila",
            "Receber o pagamento",
            "Pix: confira no app do seu banco ANTES de confirmar",
            "Só o dono:",
            "Fechar sem receber",
        ).forEach { assertContains(guia.body(), it, message = it) }
        assertContains(guia.body(), "/css/impresso")
    }

    @Test
    fun `a plaquinha tem a chamada do plano e o nome da barraca`() {
        val dono = painel.donoComBarraca("Barraca Plaquinha Teste")

        val plaquinha = dono.get("/painel/materiais/plaquinha")

        assertEquals(200, plaquinha.statusCode())
        listOf("Peça pelo celular", "Faça seu cadastro aqui e receba seu QR", "Barraca Plaquinha Teste", "Pix", "dinheiro", "cartão")
            .forEach { assertContains(plaquinha.body(), it, message = it) }
    }

    @Test
    fun `cada dono ve so a propria barraca nos materiais`() {
        val a = painel.donoComBarraca("Barraca Material A")
        val b = painel.donoComBarraca("Barraca Material B")

        listOf("/painel/materiais/guia", "/painel/materiais/plaquinha").forEach {
            assertContains(b.get(it).body(), "Barraca Material B", message = it)
            assertFalse(b.get(it).body().contains("Barraca Material A"), it)
            assertFalse(a.get(it).body().contains("Barraca Material B"), it)
        }
    }

    @Test
    fun `o menu do painel tem os materiais, e as paginas nao usam script nem estilo na propria pagina`() {
        val dono = painel.donoComBarraca("Barraca Material Csp")

        assertContains(dono.get("/painel").body(), "/painel/materiais")
        val materiais = dono.get("/painel/materiais").body()
        assertContains(materiais, "Guia rápido da equipe")
        assertContains(materiais, "Plaquinha do balcão")
        listOf("/painel/materiais/guia", "/painel/materiais/plaquinha").forEach {
            val html = dono.get(it).body()
            assertFalse(Regex("""<script(?![^>]*\bsrc=)""").containsMatchIn(html), "script dentro da página: $it")
            assertFalse(html.contains("onclick="), it)
            assertFalse(Regex("""\sstyle=""").containsMatchIn(html), "estilo dentro da página: $it")
        }
        // A folha de estilo de impressão e o botão existem como arquivos próprios
        val css = HttpTestClient(port, TestIps.next()).get("/css/impresso.css")
        assertEquals(200, css.statusCode())
        assertTrue(css.body().contains("@media print") && css.body().contains("A4"))
        assertContains(HttpTestClient(port, TestIps.next()).get("/js/imprimir.js").body(), "window.print()")
    }
}
