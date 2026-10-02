package br.com.mesamar.staff

import br.com.mesamar.comanda.AbertaPor
import br.com.mesamar.pagamento.ConfirmacaoPagamento
import br.com.mesamar.pagamento.ContaAPagar
import br.com.mesamar.pagamento.MetodoPagamento
import br.com.mesamar.pagamento.Pagamento
import br.com.mesamar.pagamento.PagamentoService
import br.com.mesamar.pagamento.ResultadoQuiosque
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ContaAPagarView(
    val comandaId: UUID,
    val cliente: String,
    val guardaSol: Int?,
    /** `ABERTA`, `AGUARDANDO_PAGAMENTO`, `BLOQUEADA` ou `FECHADA`. */
    val status: String,
    val pedidoCentavos: Long,
    val pagoCentavos: Long,
    val saldoCentavos: Long,
    /** A forma que o cliente disse que vai usar (`PIX`, `DINHEIRO` ou `CARTAO`); `null` se ainda não fechou a conta. */
    val metodo: String?,
    /** Dinheiro: "vou pagar com R$ …", para separar o troco. */
    val trocoParaCentavos: Long?,
    /** Horário do servidor em que o cliente avisou (ISO). `null` se ainda não avisou. */
    val avisoEm: String?,
    val abertaEm: String,
    /** Pedidos que só entram na fila depois de pagos (modo "pagar a cada pedido"). */
    val pedidosAguardando: Int,
    val saldoPerdoadoCentavos: Long?,
)

data class PagamentosView(
    /** Horário do servidor agora: o app calcula "avisou há N min" com a diferença para o relógio dele. */
    val agora: String,
    val aguardando: List<ContaAPagarView>,
    val abertas: List<ContaAPagarView>,
)

data class ConfirmacaoRequest(
    /** Gerado no celular: reenviar a mesma confirmação não cobra duas vezes. */
    val id: UUID? = null,
    /** `PIX`, `DINHEIRO` ou `CARTAO`. */
    val metodo: String? = null,
    val valorCentavos: Long? = null,
    /** Dinheiro: quanto o cliente entregou (o troco é este valor menos o `valorCentavos`). */
    val valorRecebidoCentavos: Long? = null,
)

data class FechamentoForcadoRequest(
    val motivo: String? = null,
)

data class PagamentoFeitoView(
    val id: UUID,
    val metodo: String,
    val valorCentavos: Long,
    val valorRecebidoCentavos: Long?,
    /** Dinheiro: o que devolver ao cliente. `0` nas outras formas. */
    val trocoCentavos: Long,
)

data class ConfirmacaoView(
    val conta: ContaAPagarView,
    val pagamento: PagamentoFeitoView,
    /** A conta chegou a zero e se encerrou. */
    val fechada: Boolean,
)

/**
 * A aba Pagamentos do quiosque: quem está para pagar, receber (total, parcial ou dividido), bloquear e encerrar contas.
 * Nenhum endpoint recebe id de barraca: ela é sempre a de quem está logado. O id que vai na URL é o da comanda, e
 * uma comanda de outra barraca responde 404, igual a uma que não existe.
 */
@RestController
@RequestMapping("/api/staff")
class StaffPagamentosController(
    private val pagamentos: PagamentoService,
    private val clock: Clock,
) {
    @GetMapping("/pagamentos")
    fun listar(
        @AuthenticationPrincipal principal: StaffPrincipal,
    ): PagamentosView {
        val contas = pagamentos.listar(principal.barracaId)
        return PagamentosView(Instant.now(clock).toString(), contas.aguardando.map(::view), contas.abertas.map(::view))
    }

    /** Confirma o que o cliente pagou. Reenviar o mesmo `id` devolve o pagamento que já existe (200), sem cobrar de novo. */
    @PostMapping("/comandas/{id}/pagamentos")
    fun confirmar(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable id: UUID,
        @RequestBody body: ConfirmacaoRequest,
    ): ResponseEntity<Any> {
        val metodo = MetodoPagamento.fromCode(body.metodo)
        if (metodo == null && body.metodo != null) return validacao("metodo", "Forma de pagamento desconhecida.")
        val r =
            pagamentos.confirmar(
                principal.barracaId,
                id,
                ConfirmacaoPagamento(body.id, metodo, body.valorCentavos, body.valorRecebidoCentavos),
                AbertaPor(principal.usuarioId, principal.atendenteId),
            )
        if (r is ResultadoQuiosque.Ok && r.pagamento != null) {
            val corpo = ConfirmacaoView(view(r.conta), view(r.pagamento), fechada = r.conta.status.name == "FECHADA")
            return ResponseEntity.status(if (r.nova) HttpStatus.CREATED else HttpStatus.OK).body(corpo)
        }
        return responder(r)
    }

    @PostMapping("/comandas/{id}/bloquear")
    fun bloquear(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable id: UUID,
    ): ResponseEntity<Any> = responder(pagamentos.bloquear(principal.barracaId, id))

    @PostMapping("/comandas/{id}/desbloquear")
    fun desbloquear(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable id: UUID,
    ): ResponseEntity<Any> = responder(pagamentos.desbloquear(principal.barracaId, id))

    /** Encerra uma conta que não deve nada. Com saldo pendente, recusa (409). */
    @PostMapping("/comandas/{id}/encerrar")
    fun encerrar(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable id: UUID,
    ): ResponseEntity<Any> = responder(pagamentos.encerrar(principal.barracaId, id))

    /** Fechamento forçado com motivo (o cliente foi embora sem pagar). **Só o dono**: é abrir mão de dinheiro. */
    @PostMapping("/comandas/{id}/fechamento-forcado")
    fun fecharSemPagamento(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable id: UUID,
        @RequestBody body: FechamentoForcadoRequest,
    ): ResponseEntity<Any> {
        val dono = principal.usuarioId
        if (principal.papel != PapelStaff.DONO || dono == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErroView("papel", "Só o dono pode fechar uma conta sem receber."))
        }
        return responder(pagamentos.fecharSemPagamento(principal.barracaId, id, body.motivo, dono))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun jsonInvalido(): ResponseEntity<Any> = validacao("corpo", "Não entendemos essa ação. Atualize o app e tente de novo.")

    private fun responder(r: ResultadoQuiosque): ResponseEntity<Any> =
        when (r) {
            is ResultadoQuiosque.Ok -> ResponseEntity.ok(view(r.conta))
            ResultadoQuiosque.NaoEncontrada ->
                ResponseEntity
                    .status(
                        HttpStatus.NOT_FOUND,
                    ).body(ErroView("comanda", "Essa comanda não está na sua barraca."))
            is ResultadoQuiosque.Invalido -> validacao(r.campo, r.mensagem)
            is ResultadoQuiosque.Recusado -> ResponseEntity.status(HttpStatus.CONFLICT).body(ErroView(r.codigo, r.mensagem))
        }

    private fun validacao(
        campo: String,
        mensagem: String,
    ): ResponseEntity<Any> = ResponseEntity.badRequest().body(ErroValidacaoView("validacao", mensagem, mapOf(campo to mensagem)))

    private fun view(c: ContaAPagar) =
        ContaAPagarView(
            comandaId = c.comandaId,
            cliente = c.cliente,
            guardaSol = c.guardaSol,
            status = c.status.name,
            pedidoCentavos = c.pedidoCentavos,
            pagoCentavos = c.pagoCentavos,
            saldoCentavos = c.saldoCentavos,
            metodo = c.metodo?.name,
            trocoParaCentavos = c.trocoParaCentavos,
            avisoEm = c.avisoEm?.toString(),
            abertaEm = c.abertaEm.toString(),
            pedidosAguardando = c.pedidosAguardando,
            saldoPerdoadoCentavos = c.saldoPerdoadoCentavos,
        )

    private fun view(p: Pagamento) =
        PagamentoFeitoView(
            id = p.id,
            metodo = p.metodo.name,
            valorCentavos = p.valorCentavos,
            valorRecebidoCentavos = p.valorRecebidoCentavos,
            trocoCentavos = (p.valorRecebidoCentavos ?: p.valorCentavos) - p.valorCentavos,
        )
}
