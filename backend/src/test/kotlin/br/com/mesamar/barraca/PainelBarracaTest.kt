package br.com.mesamar.barraca

import br.com.mesamar.common.TipoChavePix
import br.com.mesamar.conta.ConviteService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.assertHtmlContains
import br.com.mesamar.support.header
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@IntegrationTest
class PainelBarracaTest(
    @Value("\${local.server.port}") port: Int,
    convites: ConviteService,
    private val usuarios: UsuarioRepository,
    private val barracas: BarracaRepository,
) {
    private val painel = PainelTestSupport(port, convites)

    private fun barracaDo(email: String): Barraca =
        barracas.findById(assertNotNull(usuarios.findByEmailIgnoreCase(email)?.barracaId)).orElseThrow()

    @Test
    fun `primeiro acesso cria a barraca com as regras padrao e mostra o checklist`() {
        val email = painel.novoEmail()
        val http = painel.donoLogado(email)

        val pagina = http.get("/painel/barraca").body()
        assertHtmlContains(pagina, "Vamos cadastrar a barraca", anchor = "<main")

        val response =
            painel.post(
                http,
                "/painel/barraca",
                "/painel/barraca",
                mapOf(
                    "nome" to "Barraca do Zé Ávila",
                    "praia" to "PITANGUEIRAS",
                ),
            )
        assertEquals(302, response.statusCode(), response.body())
        assertTrue(response.header("Location").endsWith("/painel"))

        val barraca = barracaDo(email)
        assertEquals("Barraca do Zé Ávila", barraca.nome)
        assertTrue(barraca.slug.startsWith("barraca-do-ze-avila"), barraca.slug)
        assertEquals(30, barraca.limiteGuardaSois)
        assertEquals(ModoPagamento.FINAL, barraca.modoPagamento)
        assertNull(barraca.limiteSaldoAbertoCentavos)
        assertTrue(!barraca.temPix)

        val home = http.get("/painel").body()
        assertHtmlContains(home, "Barraca criada. Agora falta a chave Pix.", anchor = "<main")
        assertHtmlContains(home, "O que falta para abrir", anchor = "<main")
        assertHtmlContains(home, "Configurar", anchor = "<main")
        assertHtmlContains(home, "Em breve", anchor = "<main")
    }

    @Test
    fun `nome e praia sao obrigatorios`() {
        val http = painel.donoLogado()
        val response = painel.post(http, "/painel/barraca", "/painel/barraca", mapOf("nome" to "  ", "praia" to "LUA"))

        assertEquals(422, response.statusCode())
        assertHtmlContains(response.body(), "Qual é o nome da barraca?")
        assertHtmlContains(response.body(), "Escolha a praia.")
    }

    @Test
    fun `nomes iguais ganham codigos diferentes e o codigo nao muda ao renomear`() {
        val a = painel.novoEmail()
        val b = painel.novoEmail()
        painel
            .post(painel.donoLogado(a), "/painel/barraca", "/painel/barraca", mapOf("nome" to "Quiosque Repetido", "praia" to "TOMBO"))
            .also { assertEquals(302, it.statusCode()) }
        val httpB = painel.donoLogado(b)
        painel.post(httpB, "/painel/barraca", "/painel/barraca", mapOf("nome" to "Quiosque Repetido", "praia" to "TOMBO"))

        val slugA = barracaDo(a).slug
        val slugB = barracaDo(b).slug
        assertNotEquals(slugA, slugB)

        painel.post(httpB, "/painel/barraca", "/painel/barraca", mapOf("nome" to "Nome Novo", "praia" to "ASTURIAS"))
        val depois = barracaDo(b)
        assertEquals("Nome Novo", depois.nome)
        assertEquals(slugB, depois.slug)
    }

    @Test
    fun `regras salvam valores em reais e validam os limites`() {
        val email = painel.novoEmail()
        val http = painel.donoLogado(email)
        painel.post(http, "/painel/barraca", "/painel/barraca", mapOf("nome" to "Barraca Regras", "praia" to "ENSEADA"))

        val invalido =
            painel.post(
                http,
                "/painel/regras",
                "/painel/regras",
                mapOf("limiteGuardaSois" to "0", "modoPagamento" to "FIADO", "limiteSaldo" to "abc", "tempoAlertaMin" to "500"),
            )
        assertEquals(422, invalido.statusCode())
        assertHtmlContains(invalido.body(), "Informe um número de 1 a 200.")
        assertHtmlContains(invalido.body(), "Escolha quando o cliente paga.")
        assertHtmlContains(invalido.body(), "Use só números, como 150 ou 150,00.")
        assertHtmlContains(invalido.body(), "Informe de 1 a 120 minutos.")

        val ok =
            painel.post(
                http,
                "/painel/regras",
                "/painel/regras",
                mapOf("limiteGuardaSois" to "45", "modoPagamento" to "POR_PEDIDO", "limiteSaldo" to "1.250,50", "tempoAlertaMin" to "8"),
            )
        assertEquals(302, ok.statusCode(), ok.body())
        val barraca = barracaDo(email)
        assertEquals(45, barraca.limiteGuardaSois)
        assertEquals(ModoPagamento.POR_PEDIDO, barraca.modoPagamento)
        assertEquals(125050L, barraca.limiteSaldoAbertoCentavos)
        assertEquals(8, barraca.tempoAlertaPedidoMin)
        assertHtmlContains(http.get("/painel").body(), "R$ 1.250,50", anchor = "<main")
        assertHtmlContains(http.get("/painel/regras").body(), "value=\"1250,50\"")

        // Vazio volta a "sem limite"
        painel.post(
            http,
            "/painel/regras",
            "/painel/regras",
            mapOf(
                "limiteGuardaSois" to "45",
                "modoPagamento" to "FINAL",
                "limiteSaldo" to "",
                "tempoAlertaMin" to "8",
            ),
        )
        assertNull(barracaDo(email).limiteSaldoAbertoCentavos)
    }

    @Test
    fun `chave Pix e validada e guardada sem mascara`() {
        val email = painel.novoEmail()
        val http = painel.donoLogado(email)
        painel.post(http, "/painel/barraca", "/painel/barraca", mapOf("nome" to "Barraca Pix", "praia" to "ENSEADA"))

        val cpfErrado =
            painel.post(
                http,
                "/painel/pix",
                "/painel/pix",
                mapOf(
                    "tipo" to "CPF",
                    "chave" to "123.456.789-00",
                    "nomeRecebedor" to "José",
                    "cidade" to "Guarujá",
                ),
            )
        assertEquals(422, cpfErrado.statusCode())
        assertHtmlContains(cpfErrado.body(), "Essa chave não parece um CPF válido.")
        assertTrue(!barracaDo(email).temPix)

        val nomeLongo =
            painel.post(
                http,
                "/painel/pix",
                "/painel/pix",
                mapOf(
                    "tipo" to "CPF",
                    "chave" to "529.982.247-25",
                    "nomeRecebedor" to "Maria Aparecida dos Santos Oliveira",
                    "cidade" to "Guarujá",
                ),
            )
        assertHtmlContains(nomeLongo.body(), "Use no máximo 25 letras")

        val ok =
            painel.post(
                http,
                "/painel/pix",
                "/painel/pix",
                mapOf("tipo" to "CPF", "chave" to "529.982.247-25", "nomeRecebedor" to "José da Praia", "cidade" to "Guarujá"),
            )
        assertEquals(302, ok.statusCode(), ok.body())
        val barraca = barracaDo(email)
        assertEquals(TipoChavePix.CPF, barraca.tipoChavePix)
        assertEquals("52998224725", barraca.chavePix)
        assertEquals("JOSE DA PRAIA", barraca.nomeRecebedorPix)
        assertEquals("GUARUJA", barraca.cidadePix)

        val home = http.get("/painel").body()
        assertHtmlContains(home, "529.982.247-25", anchor = "<main")
        assertHtmlContains(home, "Recebendo em JOSE DA PRAIA", anchor = "<main")
    }

    @Test
    fun `cada dono so ve a propria barraca`() {
        val a = painel.novoEmail()
        val httpA = painel.donoLogado(a)
        painel.post(httpA, "/painel/barraca", "/painel/barraca", mapOf("nome" to "Barraca Secreta do A", "praia" to "ENSEADA"))

        val httpB = painel.donoLogado()
        painel.post(httpB, "/painel/barraca", "/painel/barraca", mapOf("nome" to "Barraca do B", "praia" to "TOMBO"))
        painel.post(
            httpB,
            "/painel/pix",
            "/painel/pix",
            mapOf(
                "tipo" to "EMAIL",
                "chave" to "b@exemplo.com",
                "nomeRecebedor" to "B",
                "cidade" to "Guaruja",
            ),
        )

        listOf("/painel", "/painel/barraca", "/painel/regras", "/painel/pix").forEach { path ->
            assertTrue(!httpB.get(path).body().contains("Barraca Secreta do A"), path)
        }
        assertEquals("Barraca Secreta do A", barracaDo(a).nome)
        assertTrue(!barracaDo(a).temPix, "o Pix do B não pode ir para a barraca do A")
    }
}
