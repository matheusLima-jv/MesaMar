package br.com.mesamar.staff

import br.com.mesamar.common.RateLimiter
import br.com.mesamar.problema.ProblemaRequest
import br.com.mesamar.problema.ProblemaService
import br.com.mesamar.problema.ResultadoProblema
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/** "Reportar problema" no app do quiosque. A barraca é sempre a de quem está logado. */
@RestController
@RequestMapping("/api/staff")
class StaffProblemasController(
    private val problemas: ProblemaService,
    private val rateLimiter: RateLimiter,
) {
    @PostMapping("/problemas")
    fun reportar(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @RequestBody body: ProblemaRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val quem = principal.usuarioId ?: principal.atendenteId
        if (!rateLimiter.tryAcquire("problema-staff:$quem", LIMITE, JANELA)) {
            return ResponseEntity
                .status(
                    HttpStatus.TOO_MANY_REQUESTS,
                ).body(ErroView("limite", "Você já mandou vários avisos. Espere um pouco."))
        }
        val resultado =
            problemas.registrar(
                principal.barracaId,
                "QUIOSQUE",
                body,
                request.getHeader("User-Agent"),
                usuarioId = principal.usuarioId,
                atendenteId = principal.atendenteId,
            )
        return when (resultado) {
            ResultadoProblema.Ok -> ResponseEntity.status(HttpStatus.CREATED).body(mapOf("ok" to true))
            is ResultadoProblema.Invalido ->
                ResponseEntity.badRequest().body(
                    ErroValidacaoView("validacao", resultado.mensagem, mapOf("mensagem" to resultado.mensagem)),
                )
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun jsonInvalido(): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(ErroView("validacao", "Não entendemos esse aviso. Atualize o app e tente de novo."))

    companion object {
        const val LIMITE = 10
        val JANELA: Duration = Duration.ofMinutes(10)
    }
}
