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
import org.springframework.web.bind.annotation.PathVariable
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
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") pagina: Int,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "100") tamanho: Int,
    ): List<ComandaView> {
        if (pagina < 0 || tamanho !in 1..200) throw IllegalArgumentException("Paginação inválida")
        return checkin.ativos(principal.barracaId, pagina, tamanho).map(::view)
    }

    @GetMapping("/guarda-sois")
    fun guardaSois(
        @AuthenticationPrincipal principal: StaffPrincipal,
    ): List<GuardaSolOpcao> {
        val ocupacao = checkin.ocupacao(principal.barracaId)
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
                if (!br.com.mesamar.common.Restricoes
                        .unica(e, "uk_comanda_token")
                ) {
                    throw e
                }
                ResultadoCheckin.Conflito
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

    /**
     * "QR novo": troca o link da comanda (o cliente compartilhou ou perdeu o QR). Os pedidos e o saldo ficam; o link
     * antigo deixa de abrir. O id da URL é o da comanda; comanda de outra barraca (ou já encerrada) responde 404.
     */
    @PostMapping("/comandas/{id}/novo-qr")
    fun novoQr(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable id: UUID,
    ): ResponseEntity<Any> {
        val detalhe =
            checkin.novoQr(principal.barracaId, id)
                ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErroView("comanda", "Não achamos essa comanda aberta."))
        return ResponseEntity.ok(view(detalhe))
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
