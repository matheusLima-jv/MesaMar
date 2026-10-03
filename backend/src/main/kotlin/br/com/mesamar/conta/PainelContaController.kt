package br.com.mesamar.conta

import br.com.mesamar.common.RateLimiter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Duration

data class CadastroForm(
    var convite: String = "",
    @field:NotBlank(message = "Como você se chama?")
    @field:Size(max = 80, message = "O nome pode ter no máximo 80 letras.")
    var nome: String = "",
    @field:NotBlank(message = "Informe o seu e-mail.")
    @field:Email(message = "Esse e-mail não parece certo. Confira.")
    @field:Size(max = 254, message = "E-mail longo demais.")
    var email: String = "",
    var senha: String = "",
)

data class NovaSenhaForm(
    var token: String = "",
    var senha: String = "",
)

/**
 * Páginas abertas do /painel: entrar, criar conta (só com convite), esqueci a senha e nova senha.
 * O POST de /painel/entrar é tratado pelo Spring Security (ver SecurityConfig).
 */
@Controller
class PainelContaController(
    private val contas: ContaService,
    private val convites: ConviteService,
    private val recuperacao: RecuperacaoSenhaService,
    private val rateLimiter: RateLimiter,
) {
    @GetMapping("/painel/entrar")
    fun entrar(
        @RequestParam(required = false) erro: String?,
        @RequestParam(required = false) saiu: String?,
        @RequestParam(name = "conta-criada", required = false) contaCriada: String?,
        @RequestParam(name = "senha-trocada", required = false) senhaTrocada: String?,
        @RequestParam(required = false) email: String?,
        authentication: Authentication?,
        model: Model,
    ): String {
        if (authentication?.principal is OwnerPrincipal) return "redirect:/painel"
        model.addAttribute("erro", erro != null)
        model.addAttribute(
            "aviso",
            when {
                saiu != null -> "Você saiu do painel."
                contaCriada != null -> "Conta criada! Entre com o e-mail e a senha que você acabou de escolher."
                senhaTrocada != null -> "Senha trocada. Entre com a senha nova."
                else -> null
            },
        )
        model.addAttribute("email", email.orEmpty().take(254))
        return "painel/entrar"
    }

    @GetMapping("/painel/cadastro")
    fun cadastro(
        @RequestParam(name = "convite", required = false) token: String?,
        response: HttpServletResponse,
        model: Model,
    ): String {
        if (convites.buscarValido(token) == null) {
            response.status = HttpStatus.NOT_FOUND.value()
            return "painel/convite-invalido"
        }
        model.addAttribute("cadastroForm", CadastroForm(convite = token!!))
        return "painel/cadastro"
    }

    @PostMapping("/painel/cadastro")
    fun cadastrar(
        @Valid @ModelAttribute("cadastroForm") form: CadastroForm,
        bindingResult: BindingResult,
        response: HttpServletResponse,
    ): String {
        SenhaRules.problema(form.senha, form.email)?.let { bindingResult.rejectValue("senha", "senha", it) }
        if (bindingResult.hasErrors()) {
            response.status = HttpStatus.UNPROCESSABLE_ENTITY.value()
            form.senha = ""
            return "painel/cadastro"
        }
        val resultado =
            try {
                contas.cadastrar(form.convite, form.nome, form.email, form.senha)
            } catch (erro: org.springframework.dao.DataIntegrityViolationException) {
                if (!br.com.mesamar.common.Restricoes
                        .unica(erro, "uk_usuario_email")
                ) {
                    throw erro
                }
                CadastroResultado.EmailEmUso
            }
        return when (resultado) {
            is CadastroResultado.Ok -> "redirect:/painel/entrar?conta-criada&email=${urlEncode(resultado.usuario.email)}"
            CadastroResultado.ConviteInvalido -> {
                response.status = HttpStatus.NOT_FOUND.value()
                "painel/convite-invalido"
            }
            CadastroResultado.EmailEmUso -> {
                bindingResult.rejectValue("email", "em-uso", "Já existe uma conta com esse e-mail. Tente entrar ou trocar a senha.")
                response.status = HttpStatus.UNPROCESSABLE_ENTITY.value()
                form.senha = ""
                "painel/cadastro"
            }
        }
    }

    @GetMapping("/painel/esqueci-senha")
    fun esqueciSenha(
        @RequestParam(required = false) enviado: String?,
        model: Model,
    ): String {
        model.addAttribute("enviado", enviado != null)
        return "painel/esqueci-senha"
    }

    @PostMapping("/painel/esqueci-senha")
    fun solicitarTroca(
        @RequestParam(required = false, defaultValue = "") email: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
        model: Model,
    ): String {
        if (!rateLimiter.tryAcquire("reset:${request.remoteAddr}", RESET_LIMIT, RESET_WINDOW)) {
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            model.addAttribute("enviado", false)
            model.addAttribute("limite", true)
            return "painel/esqueci-senha"
        }
        if (email.isNotBlank() && email.length <= 254) recuperacao.solicitar(email)
        // Mesma resposta sempre: não revela se o e-mail tem conta.
        return "redirect:/painel/esqueci-senha?enviado"
    }

    @GetMapping("/painel/nova-senha")
    fun novaSenha(
        @RequestParam(required = false) token: String?,
        response: HttpServletResponse,
        model: Model,
    ): String {
        if (!recuperacao.tokenValido(token)) {
            response.status = HttpStatus.NOT_FOUND.value()
            return "painel/link-expirado"
        }
        model.addAttribute("novaSenhaForm", NovaSenhaForm(token = token!!))
        return "painel/nova-senha"
    }

    @PostMapping("/painel/nova-senha")
    fun redefinir(
        @ModelAttribute("novaSenhaForm") form: NovaSenhaForm,
        bindingResult: BindingResult,
        response: HttpServletResponse,
    ): String {
        val email = recuperacao.emailDoToken(form.token)
        if (email == null) {
            response.status = HttpStatus.NOT_FOUND.value()
            return "painel/link-expirado"
        }
        SenhaRules.problema(form.senha, email)?.let { bindingResult.rejectValue("senha", "senha", it) }
        if (bindingResult.hasErrors()) {
            response.status = HttpStatus.UNPROCESSABLE_ENTITY.value()
            form.senha = ""
            return "painel/nova-senha"
        }
        if (!recuperacao.redefinir(form.token, form.senha)) {
            response.status = HttpStatus.NOT_FOUND.value()
            return "painel/link-expirado"
        }
        return "redirect:/painel/entrar?senha-trocada&email=${urlEncode(email)}"
    }

    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        const val RESET_LIMIT = 5
        val RESET_WINDOW: Duration = Duration.ofMinutes(15)
    }
}
