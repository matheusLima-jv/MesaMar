package br.com.mesamar.conta

import br.com.mesamar.common.SecureTokens
import br.com.mesamar.support.IntegrationTest
import br.com.mesamar.support.PainelTestSupport
import br.com.mesamar.support.PainelTestSupport.Companion.SENHA
import br.com.mesamar.support.PainelTestSupport.Companion.csrfFrom
import br.com.mesamar.support.assertHtmlContains
import br.com.mesamar.support.header
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@IntegrationTest
class PainelContaTest(
    @Value("\${local.server.port}") port: Int,
    convites: ConviteService,
    private val usuarios: UsuarioRepository,
    private val jdbc: JdbcTemplate,
) {
    private val painel = PainelTestSupport(port, convites)

    @Test
    fun `painel exige login e manda para a pagina de entrar`() {
        listOf("/painel", "/painel/barraca", "/painel/regras", "/painel/pix").forEach { path ->
            val response = painel.anonymous().get(path)
            assertEquals(302, response.statusCode(), path)
            assertContains(response.header("Location"), "/painel/entrar")
        }
    }

    @Test
    fun `cadastro sem convite ou com convite inventado nao abre`() {
        listOf("/painel/cadastro", "/painel/cadastro?convite=inventado").forEach { path ->
            val response = painel.anonymous().get(path)
            assertEquals(404, response.statusCode(), path)
            assertContains(response.body(), "Esse convite não vale mais.")
        }
    }

    @Test
    fun `convite valido cria a conta, com e-mail minusculo e senha com hash`() {
        val http = painel.session()
        val response = painel.cadastrar(http, "Ana.Costa@Exemplo.com", nome = "Ana Costa")

        assertEquals(302, response.statusCode(), response.body())
        assertContains(response.header("Location"), "/painel/entrar?conta-criada")
        val usuario = assertNotNull(usuarios.findByEmailIgnoreCase("ana.costa@exemplo.com"))
        assertEquals("ana.costa@exemplo.com", usuario.email)
        assertEquals("Ana Costa", usuario.nome)
        assertNotEquals(SENHA, usuario.senhaHash)
        assertTrue(usuario.senhaHash.startsWith("{bcrypt}"), "senha guardada com BCrypt")
    }

    @Test
    fun `convite so vale uma vez`() {
        val token = painel.novoToken()
        assertEquals(302, painel.cadastrar(painel.session(), painel.novoEmail(), token = token).statusCode())

        assertEquals(404, painel.anonymous().get("/painel/cadastro?convite=$token").statusCode())
        // Mesmo reenviando o formulário antigo (com o token no campo escondido), não cria outra conta.
        val segundo = painel.session()
        val csrf = csrfFrom(segundo.get("/painel/entrar").body())
        val email = painel.novoEmail()
        val response =
            segundo.postForm(
                "/painel/cadastro",
                mapOf("convite" to token, "nome" to "Outro", "email" to email, "senha" to SENHA, "_csrf" to csrf),
            )
        assertEquals(404, response.statusCode())
        assertNull(usuarios.findByEmailIgnoreCase(email))
    }

    @Test
    fun `convite expirado nao abre`() {
        val novo = painel.novoToken()
        jdbc.update("UPDATE convite SET expira_em = now() - interval '1 second' WHERE token_hash = ?", SecureTokens.hash(novo))

        assertEquals(404, painel.anonymous().get("/painel/cadastro?convite=$novo").statusCode())
    }

    @Test
    fun `e-mail ja cadastrado nao cria segunda conta, mesmo com outra caixa`() {
        val email = painel.novoEmail("repetido")
        assertEquals(302, painel.cadastrar(painel.session(), email).statusCode())

        val token = painel.novoToken()
        val response = painel.cadastrar(painel.session(), email.uppercase(), token = token)

        assertEquals(422, response.statusCode())
        assertHtmlContains(response.body(), "Já existe uma conta com esse e-mail.")
        // O convite não foi gasto: a pessoa pode usar outro e-mail.
        assertEquals(200, painel.anonymous().get("/painel/cadastro?convite=$token").statusCode())
    }

    @Test
    fun `regras de senha e campos obrigatorios`() {
        val email = painel.novoEmail()
        val curta = painel.cadastrar(painel.session(), email, senha = "curta")
        assertEquals(422, curta.statusCode())
        assertHtmlContains(curta.body(), "A senha precisa ter pelo menos 10 caracteres.")
        // A senha digitada nunca volta preenchida no HTML.
        assertTrue(!curta.body().contains("value=\"curta\""), "senha não pode voltar no HTML")

        val igualEmail = painel.cadastrar(painel.session(), email, senha = email)
        assertHtmlContains(igualEmail.body(), "A senha não pode ser igual ao e-mail.")

        val semNome = painel.cadastrar(painel.session(), "nao-e-email", nome = "")
        assertEquals(422, semNome.statusCode())
        assertHtmlContains(semNome.body(), "Como você se chama?")
        assertHtmlContains(semNome.body(), "Esse e-mail não parece certo.")
        assertNull(usuarios.findByEmailIgnoreCase(email))
    }

    @Test
    fun `login com e-mail em qualquer caixa, registra o ultimo acesso e sair encerra`() {
        val email = painel.novoEmail("login")
        assertEquals(302, painel.cadastrar(painel.session(), email).statusCode())

        val http = painel.session()
        val response = painel.login(http, email.uppercase())
        assertEquals(302, response.statusCode())
        assertTrue(response.header("Location").endsWith("/painel"))
        assertNotNull(usuarios.findByEmailIgnoreCase(email)?.ultimoLoginEm)

        // Sem barraca ainda: a home manda criar.
        val home = http.get("/painel")
        assertEquals(302, home.statusCode())
        assertTrue(home.header("Location").endsWith("/painel/barraca"))

        val sair = http.postForm("/painel/sair", mapOf("_csrf" to csrfFrom(http.get("/painel/barraca").body())))
        assertEquals(302, sair.statusCode())
        assertTrue(sair.header("Location").endsWith("/painel/entrar?saiu"))
        assertEquals(302, http.get("/painel/barraca").statusCode())
    }

    @Test
    fun `senha errada volta com mensagem`() {
        val email = painel.novoEmail()
        painel.cadastrar(painel.session(), email)
        val http = painel.session()

        val response = painel.login(http, email, senha = "outra senha qualquer")

        assertEquals(302, response.statusCode())
        assertTrue(response.header("Location").endsWith("/painel/entrar?erro"))
        assertContains(http.get("/painel/entrar?erro").body(), "E-mail ou senha não conferem.")
    }

    @Test
    fun `login do admin nao entra no painel e o do dono nao entra no admin`() {
        val email = painel.novoEmail()
        painel.cadastrar(painel.session(), email)

        val dono = painel.session()
        painel.login(dono, email)
        val admin = dono.get("/admin")
        assertEquals(302, admin.statusCode())
        assertContains(admin.header("Location"), "/admin/login")

        val adminHttp = painel.session()
        val csrf = csrfFrom(adminHttp.get("/admin/login").body())
        adminHttp.postForm("/admin/login", mapOf("username" to "admin", "password" to "senha-de-teste", "_csrf" to csrf))
        assertEquals(200, adminHttp.get("/admin").statusCode())
        assertEquals(302, adminHttp.get("/painel/barraca").statusCode())
        // E a senha do admin não serve no formulário do painel.
        assertTrue(painel.login(painel.session(), "admin", "senha-de-teste").header("Location").endsWith("?erro"))
    }

    @Test
    fun `mais de 10 tentativas de login no painel do mesmo IP recebem 429`() {
        val http = painel.session()
        repeat(10) { assertEquals(302, painel.login(http, "ninguem@exemplo.com", "chute-numero-$it").statusCode()) }

        val blocked = painel.login(http, "ninguem@exemplo.com")
        assertEquals(429, blocked.statusCode())
        assertContains(blocked.body(), "Muitas tentativas de login")
    }
}
