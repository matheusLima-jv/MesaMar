package br.com.mesamar.staff

import br.com.mesamar.cliente.ErroIndisponivelView
import br.com.mesamar.cliente.PedidoView
import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.pedido.LancadoPor
import br.com.mesamar.pedido.PedidoRequest
import br.com.mesamar.pedido.PedidoService
import br.com.mesamar.pedido.ResultadoPedido
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * "Lançar pedido para cliente": a equipe pede pela comanda (cliente sem celular, sem bateria ou sem sinal).
 * O pedido entra na mesma fila, com `origem = ATENDENTE`. O id que vai na URL é o da comanda, e uma comanda de outra
 * barraca responde 404, igual a uma que não existe. O `id` do pedido nasce no aparelho: reenviar devolve o mesmo pedido.
 */
@RestController
@RequestMapping("/api/staff")
class StaffPedidosController(
    private val pedidos: PedidoService,
    private val comandas: ComandaRepository,
) {
    @PostMapping("/comandas/{id}/pedidos")
    fun lancar(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable id: UUID,
        @RequestBody body: PedidoRequest,
    ): ResponseEntity<Any> {
        val comanda =
            comandas.findByIdAndBarracaId(id, principal.barracaId)
                ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErroView("comanda", "Não achamos essa comanda."))
        val equipe = LancadoPor(principal.usuarioId, principal.atendenteId)
        val resultado =
            try {
                pedidos.criar(comanda, body, equipe)
            } catch (e: DataIntegrityViolationException) {
                // Dois envios do mesmo pedido ao mesmo tempo: o segundo encontra o primeiro.
                if (body.id == null) throw e
                pedidos.criar(comanda, body, equipe)
            }
        return when (resultado) {
            is ResultadoPedido.Ok ->
                ResponseEntity.status(if (resultado.nova) HttpStatus.CREATED else HttpStatus.OK).body(PedidoView.of(resultado.detalhe))
            is ResultadoPedido.Invalido ->
                ResponseEntity.badRequest().body(ErroValidacaoView("validacao", resultado.mensagem, resultado.campos))
            is ResultadoPedido.Bloqueado ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(ErroView(resultado.bloqueio.codigo, resultado.bloqueio.mensagem))
            is ResultadoPedido.PrecoMudou ->
                ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ErroIndisponivelView("preco-mudou", resultado.mensagem, resultado.produtoIds))
            is ResultadoPedido.Indisponivel ->
                ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ErroIndisponivelView("indisponivel", resultado.mensagem, resultado.produtoIds))
            ResultadoPedido.Conflito ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(ErroView("conflito", "Não deu para lançar esse pedido. Tente de novo."))
        }
    }

    /** JSON malformado ou de tipo errado (ex.: quantidade "abc"): 400 no mesmo formato dos outros erros. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun jsonInvalido(): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(ErroView("validacao", "Não entendemos esse pedido. Atualize o app e tente de novo."))
}
