package br.com.mesamar.staff

import br.com.mesamar.barraca.Barraca
import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.cardapio.CardapioService
import br.com.mesamar.cardapio.Produto
import br.com.mesamar.common.RateLimiter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.util.UUID

data class EntrarDonoRequest(
    val email: String = "",
    val senha: String = "",
)

data class EntrarAtendenteRequest(
    val codigo: String = "",
    val atendenteId: UUID,
    val pin: String = "",
)

data class EsgotadoRequest(
    val esgotado: Boolean,
)

data class UsuarioView(
    val nome: String,
    val papel: PapelStaff,
)

data class BarracaView(
    val nome: String,
    val codigo: String,
    val praia: String,
    /** Barraca de treino: o app mostra uma faixa de aviso. */
    val demo: Boolean = false,
)

/** O app chama isto ao abrir: diz se já está logado e entrega o token CSRF para os POSTs. */
data class SessaoView(
    val logado: Boolean,
    val csrf: String,
    val usuario: UsuarioView? = null,
    val barraca: BarracaView? = null,
)

data class ProdutoView(
    val id: UUID,
    val nome: String,
    val descricao: String?,
    val precoCentavos: Long,
    val preco: String,
    val esgotado: Boolean,
) {
    companion object {
        fun of(p: Produto) = ProdutoView(p.id, p.nome, p.descricao, p.precoCentavos, p.preco, p.esgotado)
    }
}

data class SecaoView(
    val id: UUID,
    val nome: String,
    val produtos: List<ProdutoView>,
)

data class ErroView(
    val erro: String,
    val mensagem: String,
    val tentativasRestantes: Int? = null,
)

/**
 * API do app do quiosque (/staff). Sessão com cookie + CSRF no cabeçalho X-CSRF-TOKEN.
 * Nenhum endpoint recebe id de barraca: a barraca é sempre a de quem entrou.
 */
@RestController
@RequestMapping("/api/staff")
class StaffController(
    private val login: StaffLoginService,
    private val sessao: StaffSessao,
    private val barracas: BarracaRepository,
    private val cardapio: CardapioService,
    private val rateLimiter: RateLimiter,
) {
    @GetMapping("/sessao")
    fun sessao(
        @AuthenticationPrincipal principal: StaffPrincipal?,
        csrf: CsrfToken,
    ): SessaoView = sessaoView(principal, csrf)

    @GetMapping("/barracas/{codigo}/atendentes")
    fun atendentes(
        @PathVariable codigo: String,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        if (!rateLimiter.tryAcquire("staff-lista:${request.remoteAddr}", LISTA_LIMITE, JANELA)) return muitasTentativas()
        val lista =
            login.atendentesDaBarraca(codigo)
                ?: return erro(HttpStatus.NOT_FOUND, "barraca", "Não achamos uma barraca com esse código. Confira com o dono.")
        return ResponseEntity.ok(lista)
    }

    @PostMapping("/entrar/dono")
    fun entrarDono(
        @RequestBody body: EntrarDonoRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
        csrf: CsrfToken,
    ): ResponseEntity<Any> {
        if (!rateLimiter.tryAcquire("staff-dono:${request.remoteAddr}", LOGIN_LIMITE, JANELA)) return muitasTentativas()
        return responder(login.entrarDono(body.email, body.senha), request, response, csrf)
    }

    @PostMapping("/entrar/atendente")
    fun entrarAtendente(
        @RequestBody body: EntrarAtendenteRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
        csrf: CsrfToken,
    ): ResponseEntity<Any> {
        if (!rateLimiter.tryAcquire("staff-pin:${request.remoteAddr}", PIN_LIMITE, JANELA)) return muitasTentativas()
        return responder(login.entrarAtendente(body.codigo, body.atendenteId, body.pin, request.remoteAddr), request, response, csrf)
    }

    @PostMapping("/sair")
    fun sair(request: HttpServletRequest): ResponseEntity<Void> {
        SecurityContextHolder.clearContext()
        request.getSession(false)?.removeAttribute(StaffSessao.CHAVE)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/cardapio")
    fun cardapio(
        @AuthenticationPrincipal principal: StaffPrincipal,
    ): List<SecaoView> =
        cardapio.secoes(barraca(principal)).map { s ->
            SecaoView(s.categoria.id, s.categoria.nome, s.noCardapio.map(ProdutoView::of))
        }

    @PostMapping("/produtos/{id}/esgotado")
    fun esgotado(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable id: UUID,
        @RequestBody body: EsgotadoRequest,
    ): ResponseEntity<Any> {
        val produto =
            cardapio.definirEsgotado(barraca(principal), id, body.esgotado)
                ?: return erro(HttpStatus.NOT_FOUND, "produto", "Esse produto não está no seu cardápio.")
        return ResponseEntity.ok(ProdutoView.of(produto))
    }

    private fun responder(
        resultado: ResultadoLogin,
        request: HttpServletRequest,
        response: HttpServletResponse,
        csrf: CsrfToken,
    ): ResponseEntity<Any> =
        when (resultado) {
            is ResultadoLogin.Ok -> {
                gravarSessao(resultado.principal, request, response)
                ResponseEntity.ok(sessaoView(resultado.principal, csrf))
            }
            is ResultadoLogin.Recusado ->
                if (resultado.tentativasRestantes == null) {
                    erro(HttpStatus.UNAUTHORIZED, "credenciais", "E-mail ou senha não conferem.")
                } else {
                    val n = resultado.tentativasRestantes
                    ResponseEntity
                        .status(HttpStatus.UNAUTHORIZED)
                        .body(
                            ErroView(
                                "pin",
                                "PIN errado. ${if (n == 1) "Resta 1 tentativa" else "Restam $n tentativas"} antes do bloqueio.",
                                n,
                            ),
                        )
                }
            is ResultadoLogin.Bloqueado ->
                erro(HttpStatus.LOCKED, "bloqueado", "Muitos PINs errados. Espere 15 minutos ou peça para o dono desbloquear no painel.")
            ResultadoLogin.SemBarraca ->
                erro(HttpStatus.CONFLICT, "sem-barraca", "Cadastre a sua barraca no painel (mesamar.0mega.fun/painel) antes de usar o app.")
            ResultadoLogin.NaoEncontrado ->
                erro(HttpStatus.NOT_FOUND, "atendente", "Esse atendente não está disponível. Confira com o dono.")
        }

    /** Login bem-sucedido: troca o id da sessão (contra fixação de sessão) e grava o contexto. */
    private fun gravarSessao(
        principal: StaffPrincipal,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        request.getSession(true)
        request.changeSessionId()
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities)
        SecurityContextHolder.setContext(context)
        sessao.repository.saveContext(context, request, response)
    }

    private fun sessaoView(
        principal: StaffPrincipal?,
        csrf: CsrfToken,
    ): SessaoView {
        if (principal == null) return SessaoView(logado = false, csrf = csrf.token)
        val b = barraca(principal)
        return SessaoView(
            logado = true,
            csrf = csrf.token,
            usuario = UsuarioView(principal.nome, principal.papel),
            barraca = BarracaView(b.nome, b.slug, b.praia.label, b.demo),
        )
    }

    private fun barraca(principal: StaffPrincipal): Barraca = barracas.findById(principal.barracaId).orElseThrow()

    private fun erro(
        status: HttpStatus,
        codigo: String,
        mensagem: String,
    ): ResponseEntity<Any> = ResponseEntity.status(status).body(ErroView(codigo, mensagem))

    private fun muitasTentativas() = erro(HttpStatus.TOO_MANY_REQUESTS, "limite", "Muitas tentativas seguidas. Espere alguns minutos.")

    companion object {
        const val LOGIN_LIMITE = 10
        const val PIN_LIMITE = 20
        const val LISTA_LIMITE = 30
        val JANELA: Duration = Duration.ofMinutes(10)
    }
}
