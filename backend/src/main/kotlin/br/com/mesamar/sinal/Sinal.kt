package br.com.mesamar.sinal

import br.com.mesamar.common.Praia
import br.com.mesamar.common.RateLimiter
import br.com.mesamar.config.SiteProperties
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseBody
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Medição anônima de sinal feita pela página /teste-sinal (sem IP e sem dados pessoais). */
@Entity
@Table(name = "medicao_sinal")
class MedicaoSinal(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val praia: Praia,
    @Column(name = "carregamento_ms", nullable = false)
    val carregamentoMs: Int,
    @Column(name = "ping_ms", nullable = false)
    val pingMs: Int,
    @Column(name = "tipo_conexao", length = 20)
    val tipoConexao: String?,
    @Column(name = "criada_em", nullable = false)
    val criadaEm: Instant,
)

interface MedicaoSinalRepository : JpaRepository<MedicaoSinal, UUID>

data class NovaMedicao(
    val praia: Praia,
    @field:Min(0)
    @field:Max(MAX_MS)
    val carregamentoMs: Int,
    @field:Min(0)
    @field:Max(MAX_MS)
    val pingMs: Int,
    @field:Size(max = 20)
    val tipoConexao: String? = null,
) {
    companion object {
        /** Acima de 2 minutos a medição não serve para nada (e provavelmente é lixo). */
        const val MAX_MS = 120_000L
    }
}

@Controller
class SinalController(
    private val repository: MedicaoSinalRepository,
    private val rateLimiter: RateLimiter,
    private val site: SiteProperties,
    private val clock: Clock,
) {
    @GetMapping("/teste-sinal")
    fun page(model: Model): String {
        model.addAttribute("site", site)
        model.addAttribute("praias", Praia.entries)
        return "landing/teste-sinal"
    }

    /** Resposta mínima e nunca cacheada: o tempo de ida e volta dela é o "ping". */
    @GetMapping("/api/teste-sinal/ping")
    fun ping(): ResponseEntity<Void> = ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()

    @PostMapping("/api/teste-sinal/medicoes")
    @ResponseBody
    fun register(
        @Valid @RequestBody body: NovaMedicao,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> {
        if (!rateLimiter.tryAcquire("sinal:${request.remoteAddr}", LIMIT, WINDOW)) {
            val erro = mapOf("erro" to "Muitas medições seguidas. Espere alguns minutos.")
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(erro)
        }
        val saved =
            repository.save(
                MedicaoSinal(
                    praia = body.praia,
                    carregamentoMs = body.carregamentoMs,
                    pingMs = body.pingMs,
                    tipoConexao = body.tipoConexao?.take(20),
                    criadaEm = Instant.now(clock),
                ),
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf("id" to saved.id.toString()))
    }

    companion object {
        const val LIMIT = 30
        val WINDOW: Duration = Duration.ofMinutes(10)
    }
}
