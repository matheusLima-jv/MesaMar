package br.com.mesamar.push

import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.guardasol.GuardaSolRepository
import br.com.mesamar.pedido.ItemPedidoRepository
import br.com.mesamar.pedido.PedidoAtrasado
import br.com.mesamar.pedido.PedidoCriado
import br.com.mesamar.pedido.StatusPedido
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.Executors

/**
 * Transforma o que aconteceu no quiosque em aviso de push. Só age **depois do commit**: ninguém recebe aviso de um
 * pedido que a transação desfez. Roda numa thread à parte, então o cliente que está pedindo não espera os avisos.
 */
@Component
class NotificadorPush(
    private val push: PushService,
    private val comandas: ComandaRepository,
    private val guardaSois: GuardaSolRepository,
    private val itens: ItemPedidoRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    /** Pedido novo na fila: "Pedido nº 23 · Guarda-sol 12" e "3 itens". Pedido que aguarda pagamento não avisa ainda. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun aoCriarPedido(e: PedidoCriado) {
        if (!push.habilitado || e.status != StatusPedido.RECEBIDO) return
        executor.execute {
            runCatching {
                val guardaSol =
                    comandas
                        .findById(
                            e.comandaId,
                        ).orElse(null)
                        ?.guardaSolId
                        ?.let { guardaSois.findById(it).orElse(null) }
                        ?.numero
                val unidades = itens.findAllByPedidoIdOrderByLinha(e.pedidoId).sumOf { it.quantidade }
                push.enviarParaBarraca(
                    e.barracaId,
                    MensagemPush(
                        titulo = "Pedido nº ${e.numeroPedido} · ${local(guardaSol)}",
                        corpo = if (unidades == 1) "1 item" else "$unidades itens",
                        url = URL_FILA,
                        tag = "pedido-${e.pedidoId}",
                        tipo = "NOVO_PEDIDO",
                        validadeSegundos = 300,
                    ),
                )
            }.onFailure { log.warn("Não deu para avisar o pedido {}: {}", e.pedidoId, it.message) }
        }
    }

    /** Pedido parado além do tempo configurado pela barraca. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun aoAtrasar(e: PedidoAtrasado) {
        if (!push.habilitado) return
        executor.execute {
            runCatching {
                push.enviarParaBarraca(
                    e.barracaId,
                    MensagemPush(
                        titulo = "Pedido nº ${e.numero} está parado",
                        corpo = "${local(e.guardaSol)} · esperando há ${e.minutos} min",
                        url = URL_FILA,
                        tag = "atraso-${e.pedidoId}",
                        tipo = "PEDIDO_ATRASADO",
                        validadeSegundos = 600,
                    ),
                )
            }.onFailure { log.warn("Não deu para avisar o atraso do pedido {}: {}", e.pedidoId, it.message) }
        }
    }

    private fun local(guardaSol: Int?) = if (guardaSol == null) "Balcão" else "Guarda-sol $guardaSol"

    companion object {
        /** Tocar na notificação abre a fila do app do quiosque. */
        const val URL_FILA = "/staff/#/fila"
    }
}
