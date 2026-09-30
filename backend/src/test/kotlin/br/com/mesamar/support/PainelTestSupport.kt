package br.com.mesamar.support

import br.com.mesamar.conta.ConviteService
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Atalhos para os testes do /painel: convite, cadastro, login e CSRF. */
class PainelTestSupport(
    private val port: Int,
    private val convites: ConviteService,
) {
    fun anonymous() = HttpTestClient(port, TestIps.next())

    fun session() = HttpTestClient(port, TestIps.next(), cookies = true)

    fun novoToken(): String = convites.criar(leadId = null, observacao = "teste").token

    /** E-mail único por teste, para os testes não dependerem da ordem. */
    fun novoEmail(prefixo: String = "dono"): String = "$prefixo-${counter.incrementAndGet()}@exemplo.com"

    fun cadastrar(
        http: HttpTestClient,
        email: String,
        senha: String = SENHA,
        nome: String = "Zé da Barraca",
        token: String = novoToken(),
    ): HttpResponse<String> {
        val csrf = csrfFrom(http.get("/painel/cadastro?convite=$token").body())
        return http.postForm(
            "/painel/cadastro",
            mapOf("convite" to token, "nome" to nome, "email" to email, "senha" to senha, "_csrf" to csrf),
        )
    }

    fun login(
        http: HttpTestClient,
        email: String,
        senha: String = SENHA,
    ): HttpResponse<String> {
        val csrf = csrfFrom(http.get("/painel/entrar").body())
        return http.postForm("/painel/entrar", mapOf("email" to email, "senha" to senha, "_csrf" to csrf))
    }

    /** Conta nova, já logada (ainda sem barraca). */
    fun donoLogado(email: String = novoEmail()): HttpTestClient {
        val http = session()
        assertEquals(302, cadastrar(http, email).statusCode())
        val response = login(http, email)
        assertEquals(302, response.statusCode())
        assertTrue(response.header("Location").endsWith("/painel"), "após o login deveria ir para /painel")
        return http
    }

    /** Conta nova, logada e com barraca criada. */
    fun donoComBarraca(
        nomeBarraca: String = "Barraca do Zé",
        praia: String = "ENSEADA",
    ): HttpTestClient {
        val http = donoLogado()
        val response = post(http, "/painel/barraca", "/painel/barraca", mapOf("nome" to nomeBarraca, "praia" to praia))
        assertEquals(302, response.statusCode(), response.body())
        return http
    }

    /** Abre a página (para pegar o CSRF) e envia o formulário. */
    fun post(
        http: HttpTestClient,
        pagina: String,
        acao: String,
        campos: Map<String, String>,
    ): HttpResponse<String> = http.postForm(acao, campos + ("_csrf" to csrfFrom(http.get(pagina).body())))

    companion object {
        const val SENHA = "guarda-sol azul"
        private val counter = AtomicInteger()

        fun csrfFrom(html: String): String =
            requireNotNull(Regex("""name="_csrf" value="([^"]+)"""").find(html)) { "token CSRF não encontrado" }.groupValues[1]
    }
}
