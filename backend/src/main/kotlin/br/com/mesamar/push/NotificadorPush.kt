package br.com.mesamar.push

import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.common.Money
import br.com.mesamar.guardasol.GuardaSolRepository
import br.com.mesamar.pagamento.MetodoPagamento
import br.com.mesamar.pagamento.PagamentoAvisado
import br.com.mesamar.pedido.ItemPedidoRepository
import br.com.mesamar.pedido.PedidoAtrasado
import br.com.mesamar.pedido.PedidoCriado
import br.com.mesamar.pedido.StatusPedido
import br.com.mesamar.problema.ProblemaReportado
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

    /**
     * O cliente avisou que está indo pagar (dinheiro ou cartão) ou que já pagou o Pix. Como nos outros avisos, o texto não
     * leva o nome do cliente (o app aberto mostra o nome). No Pix, lembra de conferir no app do banco.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun aoAvisarPagamento(e: PagamentoAvisado) {
        if (!push.habilitado) return
        executor.execute {
            runCatching {
                val guardaSol =
                    comandas
                        .findById(e.comandaId)
                        .orElse(null)
                        ?.guardaSolId
                        ?.let { guardaSois.findById(it).orElse(null) }
                        ?.numero
                val valor = Money.format(e.saldoCentavos)
                val pix = e.metodo == MetodoPagamento.PIX
                push.enviarParaBarraca(
                    e.barracaId,
                    MensagemPush(
                        titulo = if (pix) "${local(guardaSol)} · Pix enviado" else "${local(guardaSol)} · indo pagar",
                        corpo =
                            when {
                                pix -> "$valor · confira no app do banco"
                                e.trocoParaCentavos != null -> "$valor · ${e.metodo.rotulo} (troco para ${Money.format(
                                    e.trocoParaCentavos,
                                )})"
                                else -> "$valor · ${e.metodo.rotulo}"
                            },
                        url = URL_PAGAMENTOS,
                        tag = "pagar-${e.comandaId}",
                        tipo = "INDO_PAGAR",
                        validadeSegundos = 600,
                    ),
                )
            }.onFailure { log.warn("Não deu para avisar o pagamento da comanda {}: {}", e.comandaId, it.message) }
        }
    }

    /**
     * Alguém reportou um problema: o dono recebe o aviso no aparelho dele, com o começo do texto. A etiqueta é a mesma para
     * todos os avisos, então vários seguidos viram uma notificação só (a mais recente), e não enchem a tela.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun aoReportarProblema(e: ProblemaReportado) {
        if (!push.habilitado) return
        executor.execute {
            runCatching {
                push.enviarParaDono(
                    e.barracaId,
                    MensagemPush(
                        titulo = "Aviso de problema · ${if (e.origem == "CLIENTE") "Cliente" else "Equipe"}",
                        corpo = e.mensagem.replace(ESPACOS, " ").take(MENSAGEM_NO_AVISO),
                        url = URL_PROBLEMAS,
                        tag = "problema",
                        tipo = "PROBLEMA",
                        validadeSegundos = 3600,
                    ),
                )
            }.onFailure { log.warn("Não deu para avisar o problema da barraca {}: {}", e.barracaId, it.message) }
        }
    }

    private fun local(guardaSol: Int?) = if (guardaSol == null) "Balcão" else "Guarda-sol $guardaSol"

    companion object {
        /** Tocar na notificação abre a fila do app do quiosque. */
        const val URL_FILA = "/staff/#/fila"

        /** Tocar no aviso de "indo pagar" abre a aba Pagamentos. */
        const val URL_PAGAMENTOS = "/staff/#/pagamentos"

        /** Tocar no aviso de problema abre a página Avisos do painel do dono. */
        const val URL_PROBLEMAS = "/painel/problemas"

        /** O texto do problema aparece na tela de bloqueio: só o começo. */
        const val MENSAGEM_NO_AVISO = 100

        private val ESPACOS = Regex("\\s+")
    }
}
