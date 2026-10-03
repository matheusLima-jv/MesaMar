package br.com.mesamar.conta

import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.PainelTestSupport.Companion.SENHA
import br.com.mesamar.support.RecordingEmailGateway
import br.com.mesamar.support.assertHtmlContains
import br.com.mesamar.support.header
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@IntegrationTest
class RecuperacaoSenhaTest(
    private val entregador: br.com.mesamar.common.Entregador,
    @Value("\${local.server.port}") port: Int,
    convites: ConviteService,
    private val emails: RecordingEmailGateway,
    private val jdbc: JdbcTemplate,
) {
    private val painel = PainelTestSupport(port, convites)

    private fun pedirTroca(email: String) =
        painel.post(painel.session(), "/painel/esqueci-senha", "/painel/esqueci-senha", mapOf("email" to email)).also { entregador.rodar() }

    private fun contaCriada(): String = painel.novoEmail("reset").also { painel.cadastrar(painel.session(), it) }

    @Test
    fun `pedido de troca responde igual exista ou nao a conta`() {
        val existente = pedirTroca(contaCriada())
        val inexistente = pedirTroca("ninguem-aqui@exemplo.com")

        assertEquals(302, existente.statusCode())
        assertEquals(existente.header("Location"), inexistente.header("Location"))
        assertTrue(existente.header("Location").endsWith("/painel/esqueci-senha?enviado"))
        assertContains(painel.anonymous().get("/painel/esqueci-senha?enviado").body(), "Se existir uma conta com esse e-mail")
        assertTrue(emails.para("ninguem-aqui@exemplo.com").isEmpty())
    }

    @Test
    fun `link do e-mail troca a senha uma vez so`() {
        val email = contaCriada()
        pedirTroca(email.uppercase())
        val link = assertNotNull(emails.ultimoLink(email), "e-mail com o link deveria ter sido enviado")
        assertContains(link, "/painel/nova-senha?token=")
        assertContains(emails.para(email).last().body, "vale por 1 hora")

        val http = painel.session()
        assertEquals(200, http.get(link).statusCode())
        val novaSenha = "maré cheia às quatro"
        val token = link.substringAfter("token=")
        val troca = painel.post(http, link, "/painel/nova-senha", mapOf("token" to token, "senha" to novaSenha))

        assertEquals(302, troca.statusCode(), troca.body())
        assertContains(troca.header("Location"), "/painel/entrar?senha-trocada")
        assertTrue(painel.login(painel.session(), email, SENHA).header("Location").endsWith("?erro"), "senha antiga não vale mais")
        assertTrue(painel.login(painel.session(), email, novaSenha).header("Location").endsWith("/painel"), "senha nova entra")

        val reuso = painel.anonymous().get(link)
        assertEquals(404, reuso.statusCode())
        assertContains(reuso.body(), "Esse link já não funciona.")
    }

    @Test
    fun `pedir de novo invalida o link anterior`() {
        val email = contaCriada()
        pedirTroca(email)
        val primeiro = assertNotNull(emails.ultimoLink(email))
        pedirTroca(email)
        val segundo = assertNotNull(emails.ultimoLink(email))

        assertEquals(404, painel.anonymous().get(primeiro).statusCode())
        assertEquals(200, painel.anonymous().get(segundo).statusCode())
    }

    @Test
    fun `link expirado nao troca a senha`() {
        val email = contaCriada()
        pedirTroca(email)
        val link = assertNotNull(emails.ultimoLink(email))
        jdbc.update(
            "UPDATE reset_senha SET expira_em = now() - interval '1 second' " +
                "WHERE usuario_id = (SELECT id FROM usuario WHERE email = ?)",
            email,
        )

        assertEquals(404, painel.anonymous().get(link).statusCode())
        val http = painel.session()
        val csrf = PainelTestSupport.csrfFrom(http.get("/painel/entrar").body())
        val troca =
            http.postForm(
                "/painel/nova-senha",
                mapOf(
                    "token" to link.substringAfter("token="),
                    "senha" to "senha nova qualquer",
                    "_csrf" to csrf,
                ),
            )
        assertEquals(404, troca.statusCode())
    }

    @Test
    fun `senha nova curta volta com erro e o link continua valendo`() {
        val email = contaCriada()
        pedirTroca(email)
        val link = assertNotNull(emails.ultimoLink(email))
        val http = painel.session()

        val troca = painel.post(http, link, "/painel/nova-senha", mapOf("token" to link.substringAfter("token="), "senha" to "123"))

        assertEquals(422, troca.statusCode())
        assertHtmlContains(troca.body(), "A senha precisa ter pelo menos 10 caracteres.")
        assertEquals(200, painel.anonymous().get(link).statusCode())
    }

    @Test
    fun `mais de 5 pedidos de troca do mesmo IP em 15 minutos recebem 429`() {
        val http = painel.session()
        repeat(5) {
            assertEquals(
                302,
                painel.post(http, "/painel/esqueci-senha", "/painel/esqueci-senha", mapOf("email" to "x$it@exemplo.com")).statusCode(),
            )
        }
        val bloqueado = painel.post(http, "/painel/esqueci-senha", "/painel/esqueci-senha", mapOf("email" to "y@exemplo.com"))
        assertEquals(429, bloqueado.statusCode())
        assertContains(bloqueado.body(), "Muitos pedidos seguidos.")
    }
}
