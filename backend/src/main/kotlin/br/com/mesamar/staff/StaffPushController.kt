package br.com.mesamar.staff

import br.com.mesamar.push.PushService
import br.com.mesamar.push.ResultadoInscricao
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** As chaves do aparelho, como o navegador entrega em `subscription.toJSON()`. */
data class ChavesPush(
    val p256dh: String? = null,
    val auth: String? = null,
)

data class InscricaoPushRequest(
    val endpoint: String? = null,
    val keys: ChavesPush? = null,
)

data class CancelarPushRequest(
    val endpoint: String? = null,
)

/** `habilitado` é falso enquanto o servidor não tem as chaves VAPID: o app mostra o motivo em vez de um botão que não funciona. */
data class PushConfigView(
    val habilitado: Boolean,
    val chavePublica: String?,
)

/**
 * Inscrição do aparelho nos avisos de pedido (Web Push). Quem está logado inscreve o próprio aparelho, sempre na
 * barraca dele (StaffPrincipal): nenhum endpoint recebe id de barraca.
 */
@RestController
@RequestMapping("/api/staff/push")
class StaffPushController(
    private val push: PushService,
) {
    @GetMapping
    fun config(): PushConfigView = PushConfigView(push.habilitado, push.chavePublica)

    /** Inscreve (ou atualiza) este aparelho. Pode ser chamado de novo sem problema. */
    @PostMapping("/inscrever")
    fun inscrever(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @RequestBody body: InscricaoPushRequest,
    ): ResponseEntity<Any> =
        when (
            val r =
                push.inscrever(
                    principal.barracaId,
                    principal.usuarioId,
                    principal.atendenteId,
                    body.endpoint,
                    body.keys?.p256dh,
                    body.keys?.auth,
                )
        ) {
            ResultadoInscricao.Ok -> ResponseEntity.noContent().build()
            ResultadoInscricao.Desligado ->
                erro(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "desligado",
                    "Os avisos ainda não foram configurados no servidor.",
                )
            is ResultadoInscricao.Invalido -> erro(HttpStatus.BAD_REQUEST, "validacao", r.mensagem)
            ResultadoInscricao.Conflito ->
                erro(
                    HttpStatus.CONFLICT,
                    "conflito",
                    "Não deu para ativar os avisos neste aparelho. Tente de novo.",
                )
        }

    /** Desativa os avisos deste aparelho. */
    @PostMapping("/cancelar")
    fun cancelar(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @RequestBody body: CancelarPushRequest,
    ): ResponseEntity<Void> {
        push.cancelar(principal.barracaId, body.endpoint)
        return ResponseEntity.noContent().build()
    }

    /** Manda um aviso de teste para este aparelho, para o dono ver que funciona. */
    @PostMapping("/testar")
    fun testar(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @RequestBody body: CancelarPushRequest,
    ): ResponseEntity<Any> =
        if (push.enviarTeste(principal.barracaId, body.endpoint)) {
            ResponseEntity.noContent().build()
        } else {
            erro(HttpStatus.NOT_FOUND, "inscricao", "Este aparelho não está com os avisos ativados. Ative e tente de novo.")
        }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun jsonInvalido(): ResponseEntity<Any> =
        erro(HttpStatus.BAD_REQUEST, "validacao", "Não entendemos o pedido. Atualize o app e tente de novo.")

    private fun erro(
        status: HttpStatus,
        codigo: String,
        mensagem: String,
    ): ResponseEntity<Any> = ResponseEntity.status(status).body(ErroView(codigo, mensagem))
}
