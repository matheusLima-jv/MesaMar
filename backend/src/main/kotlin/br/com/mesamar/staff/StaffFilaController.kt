package br.com.mesamar.staff

import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.comanda.AbertaPor
import br.com.mesamar.eventos.HubEventos
import br.com.mesamar.pedido.FilaService
import br.com.mesamar.pedido.PausaService
import br.com.mesamar.pedido.PedidoFila
import br.com.mesamar.pedido.ResultadoFila
import br.com.mesamar.pedido.StatusPedido
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class StatusRequest(
    /** `EM_PREPARO` ou `ENTREGUE`. */
    val status: String? = null,
)

data class CancelamentoRequest(
    /** `ESGOTOU`, `CLIENTE_DESISTIU` ou `OUTRO`. Obrigatório. */
    val motivo: String? = null,
    /** Texto livre, usado quando o motivo é `OUTRO`. */
    val detalhe: String? = null,
)

data class ItemFilaView(
    val id: UUID,
    val nome: String,
    val quantidade: Int,
    val observacao: String?,
    val cancelado: Boolean,
    val motivoCancelamento: String?,
)

data class PedidoFilaView(
    val id: UUID,
    val numero: Int,
    val status: String,
    /** Horário do servidor (ISO). O tempo de espera sai da diferença para `FilaView.agora`, nunca do relógio do celular. */
    val criadoEm: String,
    val guardaSol: Int?,
    val cliente: String,
    val itens: List<ItemFilaView>,
)

data class FilaView(
    /** Horário do servidor agora: o app calcula a diferença para o relógio dele e a usa nos tempos de espera. */
    val agora: String,
    val tempoAlertaMin: Int,
    /** O quiosque está sem receber pedidos pelo celular dos clientes. */
    val pedidosPausados: Boolean,
    val mensagemPausa: String?,
    val pedidos: List<PedidoFilaView>,
)

data class PausaRequest(
    val pausado: Boolean? = null,
    /** Recado para os clientes (até 80 letras). Só vale ao pausar. */
    val mensagem: String? = null,
)

data class PausaView(
    val pedidosPausados: Boolean,
    val mensagemPausa: String?,
)

/**
 * A fila do quiosque (pedidos pendentes por ordem de chegada), as ações de 1 toque e o tempo real (SSE).
 * Nenhum endpoint recebe id de barraca: ela é sempre a de quem entrou (StaffPrincipal).
 */
@RestController
@RequestMapping("/api/staff")
class StaffFilaController(
    private val fila: FilaService,
    private val barracas: BarracaRepository,
    private val hub: HubEventos,
    private val pausa: PausaService,
    private val clock: Clock,
) {
    @GetMapping("/fila")
    fun fila(
        @AuthenticationPrincipal principal: StaffPrincipal,
    ): FilaView {
        val barraca = barracas.findById(principal.barracaId).orElseThrow()
        return FilaView(
            agora = Instant.now(clock).toString(),
            tempoAlertaMin = barraca.tempoAlertaPedidoMin,
            pedidosPausados = barraca.pedidosPausados,
            mensagemPausa = barraca.mensagemPausa,
            pedidos = fila.listar(principal.barracaId).map(::view),
        )
    }

    /**
     * "Pausar pedidos" (no pico, ou quando o estoque acaba) e "Voltar a receber". Vale na hora para todos os clientes
     * da barraca, que são avisados em tempo real. Dono e atendentes podem usar. Repetir a mesma ação não faz mal.
     */
    @PostMapping("/pausa")
    fun pausar(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @RequestBody body: PausaRequest,
    ): ResponseEntity<Any> {
        val pausado = body.pausado ?: return erro(HttpStatus.BAD_REQUEST, "validacao", "Diga se é para pausar ou voltar a receber pedidos.")
        val barraca = pausa.definir(principal.barracaId, pausado, body.mensagem)
        return ResponseEntity.ok(PausaView(barraca.pedidosPausados, barraca.mensagemPausa))
    }

    /** "Em preparo" e "Entregue". Repetir a mesma ação não faz mal. */
    @PostMapping("/pedidos/{id}/status")
    fun status(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable id: UUID,
        @RequestBody body: StatusRequest,
    ): ResponseEntity<Any> {
        val novo =
            StatusPedido.entries.firstOrNull { it.name == body.status }
                ?: return erro(HttpStatus.BAD_REQUEST, "validacao", "Use \"em preparo\" ou \"entregue\".")
        return responder(fila.mudarStatus(principal.barracaId, id, novo, quem(principal)))
    }

    @PostMapping("/pedidos/{id}/cancelar")
    fun cancelar(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable id: UUID,
        @RequestBody body: CancelamentoRequest,
    ): ResponseEntity<Any> = responder(fila.cancelarPedido(principal.barracaId, id, body.motivo, body.detalhe, quem(principal)))

    @PostMapping("/pedidos/{id}/itens/{itemId}/cancelar")
    fun cancelarItem(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable id: UUID,
        @PathVariable itemId: UUID,
        @RequestBody body: CancelamentoRequest,
    ): ResponseEntity<Any> = responder(fila.cancelarItem(principal.barracaId, id, itemId, body.motivo, body.detalhe, quem(principal)))

    /**
     * Tempo real: avisa (evento `fila`) quando um pedido da barraca nasce ou muda, e o app busca a fila de novo.
     * Só a barraca de quem está logado. Um comentário a cada 25 s mantém a conexão viva atrás do proxy.
     */
    @GetMapping("/eventos", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun eventos(
        @AuthenticationPrincipal principal: StaffPrincipal,
        response: HttpServletResponse,
    ): SseEmitter {
        response.setHeader("X-Accel-Buffering", "no")
        response.setHeader("Cache-Control", "no-cache, no-store")
        return hub.assinarBarraca(principal.barracaId)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun jsonInvalido(): ResponseEntity<Any> =
        erro(HttpStatus.BAD_REQUEST, "validacao", "Não entendemos essa ação. Atualize o app e tente de novo.")

    private fun responder(r: ResultadoFila): ResponseEntity<Any> =
        when (r) {
            is ResultadoFila.Ok -> ResponseEntity.ok(view(r.pedido))
            ResultadoFila.NaoEncontrado -> erro(HttpStatus.NOT_FOUND, "pedido", "Esse pedido não está na sua fila.")
            is ResultadoFila.Transicao -> erro(HttpStatus.CONFLICT, "transicao", r.mensagem)
            is ResultadoFila.Invalido -> erro(HttpStatus.BAD_REQUEST, "validacao", r.mensagem)
        }

    private fun quem(principal: StaffPrincipal) = AbertaPor(principal.usuarioId, principal.atendenteId)

    private fun erro(
        status: HttpStatus,
        codigo: String,
        mensagem: String,
    ): ResponseEntity<Any> = ResponseEntity.status(status).body(ErroView(codigo, mensagem))

    private fun view(p: PedidoFila) =
        PedidoFilaView(
            id = p.pedido.id,
            numero = p.pedido.numeroPedido,
            status = p.pedido.status.name,
            criadoEm = p.pedido.criadoEm.toString(),
            guardaSol = p.guardaSol,
            cliente = p.cliente,
            itens = p.itens.map { ItemFilaView(it.id, it.nomeSnapshot, it.quantidade, it.observacao, it.cancelado, it.motivoCancelamento) },
        )
}
