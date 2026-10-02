package br.com.mesamar.pedido

import br.com.mesamar.comanda.Comanda
import br.com.mesamar.eventos.PublicadorEventos
import br.com.mesamar.pagamento.PagamentoRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * "Pagar a cada pedido": coloca na fila os pedidos que esperavam pagamento e já estão cobertos pelo que foi pago, na
 * ordem em que foram feitos (um pedido entra quando o total pago cobre ele e todos os anteriores).
 *
 * Roda depois de um pagamento **e** depois de um cancelamento: cancelar um pedido que ainda esperava pagamento pode
 * deixar o seguinte coberto. Quem chama já travou a comanda; aqui cada pedido é travado e **relido do banco**
 * (`refresh`), porque a lista de pedidos foi carregada antes da trava e poderia estar velha.
 */
@Component
class LiberacaoPedidos(
    private val pedidos: PedidoService,
    private val pagamentos: PagamentoRepository,
    private val tempoReal: PublicadorEventos,
    private val spring: ApplicationEventPublisher,
    private val em: EntityManager,
) {
    fun liberar(
        comanda: Comanda,
        agora: Instant,
    ) {
        val pago = pagamentos.totalCentavos(comanda.id)
        if (pago <= 0) return
        var acumulado = 0L
        for (detalhe in pedidos.meusPedidos(comanda)) {
            if (detalhe.pedido.status == StatusPedido.CANCELADO) continue
            acumulado += detalhe.totalCentavos
            if (detalhe.pedido.status != StatusPedido.AGUARDANDO_PAGAMENTO || detalhe.totalCentavos <= 0 || acumulado > pago) continue
            val pedido = detalhe.pedido
            em.refresh(pedido, LockModeType.PESSIMISTIC_WRITE)
            if (pedido.status != StatusPedido.AGUARDANDO_PAGAMENTO) continue
            pedido.status = StatusPedido.RECEBIDO
            pedido.naFilaEm = agora
            tempoReal.pedidoMudou(comanda.barracaId, comanda.id, pedido.id)
            // O mesmo aviso de pedido novo, agora que ele entrou na fila (o push só sai depois do commit)
            spring.publishEvent(PedidoCriado(pedido.id, comanda.barracaId, comanda.id, pedido.numeroPedido, StatusPedido.RECEBIDO))
        }
    }
}
