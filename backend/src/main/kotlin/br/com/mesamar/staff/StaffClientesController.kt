package br.com.mesamar.staff

import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.comanda.AbertaPor
import br.com.mesamar.comanda.CheckinRequest
import br.com.mesamar.comanda.CheckinService
import br.com.mesamar.comanda.ComandaDetalhe
import br.com.mesamar.comanda.ResultadoCheckin
import br.com.mesamar.common.BrazilianPhone
import br.com.mesamar.common.Fuso
import br.com.mesamar.config.SiteProperties
import br.com.mesamar.guardasol.GuardaSolService
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.format.DateTimeFormatter
import java.util.UUID

data class ComandaView(
    val id: UUID,
    val numeroChegada: Int,
    val nome: String,
    val guardaSol: Int?,
    /** Só dígitos com DDI (ex.: 5513999998888), pronto para o wa.me. */
    val whatsapp: String?,
    val whatsappExibicao: String?,
    /** O link do QR. É a credencial do cliente: só aparece para a equipe da própria barraca. */
    val link: String,
    val hora: String,
    val status: String,
)

data class GuardaSolOpcao(
    val id: UUID,
    val numero: Int,
    /** Quantos clientes com comanda ativa estão nesse guarda-sol (pode haver mais de um). */
    val clientes: Int,
)

data class ErroValidacaoView(
    val erro: String,
    val mensagem: String,
    val campos: Map<String, String>,
)

/** Check-in do cliente no app do quiosque. A barraca é sempre a de quem entrou (StaffPrincipal). */
@RestController
@RequestMapping("/api/staff")
class StaffClientesController(
    private val checkin: CheckinService,
    private val guardaSois: GuardaSolService,
    private val barracas: BarracaRepository,
    private val site: SiteProperties,
) {
    @GetMapping("/clientes")
    fun clientes(
        @AuthenticationPrincipal principal: StaffPrincipal,
    ): List<ComandaView> = checkin.ativos(principal.barracaId).map(::view)

    @GetMapping("/guarda-sois")
    fun guardaSois(
        @AuthenticationPrincipal principal: StaffPrincipal,
    ): List<GuardaSolOpcao> {
        val ocupacao =
            checkin
                .ativos(principal.barracaId)
                .mapNotNull { it.guardaSol?.id }
                .groupingBy { it }
                .eachCount()
        val barraca = barracas.findById(principal.barracaId).orElseThrow()
        return guardaSois
            .listar(barraca)
            .filter { it.ativo }
            .map { GuardaSolOpcao(it.id, it.numero, ocupacao[it.id] ?: 0) }
    }

    @PostMapping("/clientes")
    fun novo(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @RequestBody body: CheckinRequest,
    ): ResponseEntity<Any> {
        val quem = AbertaPor(principal.usuarioId, principal.atendenteId)
        val resultado =
            try {
                checkin.checkin(principal.barracaId, quem, body)
            } catch (e: DataIntegrityViolationException) {
                // Dois envios do mesmo check-in ao mesmo tempo: o segundo encontra o primeiro.
                if (body.id == null) throw e
                checkin.checkin(principal.barracaId, quem, body)
            }
        return when (resultado) {
            is ResultadoCheckin.Ok ->
                ResponseEntity.status(if (resultado.nova) HttpStatus.CREATED else HttpStatus.OK).body(view(resultado.detalhe))
            is ResultadoCheckin.Invalido ->
                ResponseEntity.badRequest().body(ErroValidacaoView("validacao", "Confira os campos marcados.", resultado.campos))
            ResultadoCheckin.Conflito ->
                ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ErroView("conflito", "Não deu para fazer esse check-in. Tente de novo."))
        }
    }

    private fun view(d: ComandaDetalhe) =
        ComandaView(
            id = d.comanda.id,
            numeroChegada = d.comanda.numeroChegada,
            nome = d.cliente.nome,
            guardaSol = d.guardaSol?.numero,
            whatsapp = d.cliente.whatsappE164?.removePrefix("+"),
            whatsappExibicao = d.cliente.whatsappE164?.let(BrazilianPhone::display),
            link = site.absoluteUrl("/c/${d.comanda.tokenAcesso}"),
            hora = HORA.format(d.comanda.abertaEm),
            status = d.comanda.status.name,
        )

    companion object {
        private val HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(Fuso.ZONA)
    }
}
