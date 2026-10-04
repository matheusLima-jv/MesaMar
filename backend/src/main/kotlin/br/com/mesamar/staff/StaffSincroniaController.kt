package br.com.mesamar.staff

import br.com.mesamar.sincronia.AutorAparelho
import br.com.mesamar.sincronia.EstadoAparelho
import br.com.mesamar.sincronia.SincroniaAparelho
import br.com.mesamar.sincronia.TipoAparelho
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class SincronizacaoRequest(
    /** Gerado uma vez no aparelho e guardado nele. */
    val aparelhoId: UUID? = null,
    /** Ações desta sessão (barraca/autor) que ainda estão só no aparelho. */
    val pendentes: Int? = null,
    /** Ações recusadas pelo servidor, esperando alguém conferir no aparelho. */
    val conflitos: Int? = null,
    /** Quando nasceu a ação pendente mais antiga; o heartbeat não redefine sua idade. */
    val pendenteDesde: Instant? = null,
)

/** O app conta, a cada rodada de sincronia, quantas ações ainda estão no aparelho. Barraca e autor vêm da sessão. */
@RestController
@RequestMapping("/api/staff")
class StaffSincroniaController(
    private val sincronia: SincroniaAparelho,
) {
    @PostMapping("/sincronizacao")
    fun informar(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @RequestBody body: SincronizacaoRequest,
        @RequestHeader(HttpHeaders.USER_AGENT, required = false) userAgent: String?,
    ): ResponseEntity<Any> {
        val aparelho = body.aparelhoId
        val pendentes = body.pendentes
        val conflitos = body.conflitos
        val faixa = 0..SincroniaAparelho.MAX_ACOES
        if (aparelho == null || pendentes == null || conflitos == null || pendentes !in faixa || conflitos !in faixa) {
            return ResponseEntity.badRequest().body(ErroView("validacao", "Informe o aparelho e as quantidades de ações (0 a 200)."))
        }
        sincronia.registrar(
            principal.barracaId,
            AutorAparelho(principal.usuarioId, principal.atendenteId),
            EstadoAparelho(aparelho, pendentes, conflitos, body.pendenteDesde),
            TipoAparelho.de(userAgent),
        )
        return ResponseEntity.noContent().build()
    }
}
