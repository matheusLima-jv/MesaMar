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
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Transforma o que aconteceu no quiosque em aviso de push. Registra entregas **antes do commit**: ninguém recebe aviso de um
 * pedido que a transação desfez. O worker só enxerga registros confirmados e envia fora da transação.
 */
@Component
class NotificadorPush(
    private val push: PushService,
    private val comandas: ComandaRepository,
    private val guardaSois: GuardaSolRepository,
    private val itens: ItemPedidoRepository,
) {
    /** Pedido novo na fila: "Pedido nº 23 · Guarda-sol 12" e "3 itens". Pedido que aguarda pagamento não avisa ainda. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun aoCriarPedido(e: PedidoCriado) {
        if (!push.habilitado || e.status != StatusPedido.RECEBIDO) return
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
    }

    /** Pedido parado além do tempo configurado pela barraca. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun aoAtrasar(e: PedidoAtrasado) {
        if (!push.habilitado) return
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
    }

    /**
     * O cliente avisou que está indo pagar (dinheiro ou cartão) ou que já pagou o Pix. Como nos outros avisos, o texto não
     * leva o nome do cliente (o app aberto mostra o nome). No Pix, lembra de conferir no app do banco.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun aoAvisarPagamento(e: PagamentoAvisado) {
        if (!push.habilitado) return
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
    }

    /**
     * Alguém reportou um problema: o dono recebe o aviso no aparelho dele, **sem o texto**: o que a pessoa digitou pode
     * ter dado pessoal, e a notificação aparece na tela bloqueada. O texto fica na página Avisos do painel (com login).
     * A etiqueta é a mesma para todos os avisos, então vários seguidos viram uma notificação só.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun aoReportarProblema(e: ProblemaReportado) {
        if (!push.habilitado) return
        push.enviarParaDono(
            e.barracaId,
            MensagemPush(
                titulo = "Aviso de problema · ${if (e.origem == "CLIENTE") "Cliente" else "Equipe"}",
                corpo = CORPO_DO_AVISO_DE_PROBLEMA,
                url = URL_PROBLEMAS,
                tag = "problema",
                tipo = "PROBLEMA",
                validadeSegundos = 3600,
            ),
        )
    }

    private fun local(guardaSol: Int?) = if (guardaSol == null) "Balcão" else "Guarda-sol $guardaSol"

    companion object {
        /** Tocar na notificação abre a fila do app do quiosque. */
        const val URL_FILA = "/staff/#/fila"

        /** Tocar no aviso de "indo pagar" abre a aba Pagamentos. */
        const val URL_PAGAMENTOS = "/staff/#/pagamentos"

        /** Tocar no aviso de problema abre a página Avisos do painel do dono. */
        const val URL_PROBLEMAS = "/painel/problemas"

        /** O aviso de problema não leva o texto digitado (tela bloqueada): só diz que há um, e o painel mostra. */
        const val CORPO_DO_AVISO_DE_PROBLEMA = "Toque para ler o aviso no painel."
    }
}
