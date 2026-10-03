package br.com.mesamar.pagamento

import br.com.mesamar.comanda.AbertaPor
import br.com.mesamar.eventos.PublicadorEventos
import br.com.mesamar.pedido.ItemPedidoRepository
import br.com.mesamar.pedido.Pedido
import br.com.mesamar.pedido.PedidoRepository
import br.com.mesamar.pedido.StatusPedido
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/** Só cancela o que nunca entrou na fila; exige a transação e a comanda travada pelo fechamento. */
@Service
class CancelamentoFinanceiro(
    private val pedidos: PedidoRepository,
    private val itens: ItemPedidoRepository,
    private val eventos: PublicadorEventos,
    private val em: EntityManager,
    private val clock: Clock,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun cancelar(
        pedido: Pedido,
        texto: String,
        quem: AbertaPor,
    ) {
        em.refresh(pedido, LockModeType.PESSIMISTIC_WRITE)
        if (pedido.status != StatusPedido.AGUARDANDO_PAGAMENTO) return
        val linhas = itens.findAllByPedidoIdOrderByLinha(pedido.id)
        linhas.filter { !it.cancelado }.forEach {
            it.cancelado = true
            it.motivoCancelamento = texto
        }
        itens.saveAll(linhas)
        pedido.status = StatusPedido.CANCELADO
        pedido.canceladoEm = Instant.now(clock)
        pedido.motivoCancelamento = texto
        pedido.atendidoPorUsuarioId = quem.usuarioId
        pedido.atendidoPorAtendenteId = quem.atendenteId
        pedidos.save(pedido)
        eventos.pedidoMudou(pedido.barracaId, pedido.comandaId, pedido.id)
    }
}
