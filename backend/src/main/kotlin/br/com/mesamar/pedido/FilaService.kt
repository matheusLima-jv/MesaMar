package br.com.mesamar.pedido

import br.com.mesamar.comanda.AbertaPor
import br.com.mesamar.comanda.ClienteRepository
import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.eventos.PublicadorEventos
import br.com.mesamar.guardasol.GuardaSolRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Por que o quiosque cancelou. O motivo é obrigatório e o cliente vê o texto na página dele. */
enum class MotivoCancelamento(
    val rotulo: String,
) {
    ESGOTOU("Esgotou"),
    CLIENTE_DESISTIU("Cliente desistiu"),
    OUTRO("Outro"),
    ;

    /** "Esgotou", "Cliente desistiu" ou "Outro: o detalhe", cabendo nos 80 caracteres do banco. */
    fun texto(detalhe: String?): String {
        val extra = detalhe?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        return if (this == OUTRO && extra.isNotEmpty()) "$rotulo: $extra".take(TEXTO_MAX) else rotulo
    }

    companion object {
        const val TEXTO_MAX = 80

        fun de(nome: String?): MotivoCancelamento? = entries.firstOrNull { it.name == nome }
    }
}

/** O pedido na fila, com o que o cartão precisa mostrar: guarda-sol, nome do cliente e as linhas. */
data class PedidoFila(
    val pedido: Pedido,
    val itens: List<ItemPedido>,
    val guardaSol: Int?,
    val cliente: String,
)

sealed interface ResultadoFila {
    data class Ok(
        val pedido: PedidoFila,
    ) : ResultadoFila

    /** Pedido ou item que não existe **nesta barraca** (um id de outra barraca é igual a um id que não existe). */
    data object NaoEncontrado : ResultadoFila

    /** O pedido não pode ir para onde se pediu (ex.: voltar de entregue). */
    data class Transicao(
        val mensagem: String,
    ) : ResultadoFila

    data class Invalido(
        val mensagem: String,
    ) : ResultadoFila
}

/**
 * A fila do quiosque: pedidos pendentes por ordem de chegada (horário do servidor) e as ações de 1 toque.
 * A barraca é sempre a de quem está logado. Cada ação trava o pedido, então dois atendentes ao mesmo tempo
 * entram um por vez e o status só anda para frente.
 */
@Service
class FilaService(
    private val pedidos: PedidoRepository,
    private val itens: ItemPedidoRepository,
    private val comandas: ComandaRepository,
    private val clientes: ClienteRepository,
    private val guardaSois: GuardaSolRepository,
    private val eventos: PublicadorEventos,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun listar(barracaId: UUID): List<PedidoFila> = montar(pedidos.findFila(barracaId, NA_FILA))

    /** Passa o pedido para "em preparo" ou "entregue". Repetir a mesma ação é inofensivo (idempotente). */
    @Transactional
    fun mudarStatus(
        barracaId: UUID,
        pedidoId: UUID,
        novo: StatusPedido,
        quem: AbertaPor,
    ): ResultadoFila {
        if (novo != StatusPedido.EM_PREPARO && novo != StatusPedido.ENTREGUE) {
            return ResultadoFila.Invalido("Use \"em preparo\" ou \"entregue\". Para desfazer um pedido, cancele.")
        }
        val pedido = pedidos.travar(pedidoId, barracaId) ?: return ResultadoFila.NaoEncontrado
        if (pedido.status == novo) return ResultadoFila.Ok(montar(pedido))
        if (!pedido.status.podeIrPara(novo)) return ResultadoFila.Transicao(mensagemTransicao(pedido.status, novo))
        val agora = Instant.now(clock)
        pedido.status = novo
        if (novo == StatusPedido.EM_PREPARO) pedido.emPreparoEm = agora else pedido.entregueEm = agora
        atender(pedido, quem)
        pedidos.save(pedido)
        eventos.pedidoMudou(pedido.barracaId, pedido.comandaId, pedido.id)
        return ResultadoFila.Ok(montar(pedido))
    }

    /** Cancela o pedido inteiro (e os itens dele). Cancelado é final. */
    @Transactional
    fun cancelarPedido(
        barracaId: UUID,
        pedidoId: UUID,
        motivo: String?,
        detalhe: String?,
        quem: AbertaPor,
    ): ResultadoFila {
        val m = MotivoCancelamento.de(motivo) ?: return ResultadoFila.Invalido("Escolha o motivo do cancelamento.")
        val pedido = pedidos.travar(pedidoId, barracaId) ?: return ResultadoFila.NaoEncontrado
        if (pedido.status == StatusPedido.CANCELADO) return ResultadoFila.Ok(montar(pedido))
        if (!pedido.status.podeIrPara(
                StatusPedido.CANCELADO,
            )
        ) {
            return ResultadoFila.Transicao(mensagemTransicao(pedido.status, StatusPedido.CANCELADO))
        }
        val texto = m.texto(detalhe)
        val linhas = itens.findAllByPedidoIdOrderByLinha(pedido.id)
        linhas.filter { !it.cancelado }.forEach {
            it.cancelado = true
            it.motivoCancelamento = texto
        }
        itens.saveAll(linhas)
        cancelar(pedido, texto, quem)
        pedidos.save(pedido)
        eventos.pedidoMudou(pedido.barracaId, pedido.comandaId, pedido.id)
        return ResultadoFila.Ok(montar(pedido))
    }

    /** Cancela só uma linha ("acabou o pastel"). Se era a última, o pedido todo fica cancelado. */
    @Transactional
    fun cancelarItem(
        barracaId: UUID,
        pedidoId: UUID,
        itemId: UUID,
        motivo: String?,
        detalhe: String?,
        quem: AbertaPor,
    ): ResultadoFila {
        val m = MotivoCancelamento.de(motivo) ?: return ResultadoFila.Invalido("Escolha o motivo do cancelamento.")
        val pedido = pedidos.travar(pedidoId, barracaId) ?: return ResultadoFila.NaoEncontrado
        val linhas = itens.findAllByPedidoIdOrderByLinha(pedido.id)
        val item = linhas.firstOrNull { it.id == itemId } ?: return ResultadoFila.NaoEncontrado
        if (item.cancelado) return ResultadoFila.Ok(montar(pedido, linhas))
        if (pedido.status.terminal) return ResultadoFila.Transicao(mensagemTransicao(pedido.status, StatusPedido.CANCELADO))
        val texto = m.texto(detalhe)
        item.cancelado = true
        item.motivoCancelamento = texto
        itens.save(item)
        if (linhas.all { it.cancelado }) cancelar(pedido, texto, quem) else atender(pedido, quem)
        pedidos.save(pedido)
        eventos.pedidoMudou(pedido.barracaId, pedido.comandaId, pedido.id)
        return ResultadoFila.Ok(montar(pedido, linhas))
    }

    private fun cancelar(
        pedido: Pedido,
        texto: String,
        quem: AbertaPor,
    ) {
        pedido.status = StatusPedido.CANCELADO
        pedido.canceladoEm = Instant.now(clock)
        pedido.motivoCancelamento = texto
        atender(pedido, quem)
    }

    private fun atender(
        pedido: Pedido,
        quem: AbertaPor,
    ) {
        pedido.atendidoPorUsuarioId = quem.usuarioId
        pedido.atendidoPorAtendenteId = quem.atendenteId
    }

    private fun mensagemTransicao(
        de: StatusPedido,
        para: StatusPedido,
    ): String =
        when (de) {
            StatusPedido.ENTREGUE -> "Esse pedido já foi entregue."
            StatusPedido.CANCELADO -> "Esse pedido foi cancelado."
            StatusPedido.AGUARDANDO_PAGAMENTO -> "Esse pedido ainda espera o pagamento do cliente."
            else -> "Não dá para passar este pedido de ${de.rotulo} para ${para.rotulo}."
        }

    private fun montar(
        pedido: Pedido,
        linhas: List<ItemPedido>? = null,
    ): PedidoFila = montar(listOf(pedido), linhas?.let { mapOf(pedido.id to it) }).single()

    private fun montar(
        lista: List<Pedido>,
        itensPorPedido: Map<UUID, List<ItemPedido>>? = null,
    ): List<PedidoFila> {
        if (lista.isEmpty()) return emptyList()
        val linhas = itensPorPedido ?: itens.findAllByPedidoIdInOrderByPedidoIdAscLinhaAsc(lista.map { it.id }).groupBy { it.pedidoId }
        val porComanda = comandas.findAllById(lista.map { it.comandaId }.distinct()).associateBy { it.id }
        val porCliente = clientes.findAllById(porComanda.values.map { it.clienteId }.distinct()).associateBy { it.id }
        val porGuardaSol = guardaSois.findAllById(porComanda.values.mapNotNull { it.guardaSolId }.distinct()).associateBy { it.id }
        return lista.map { p ->
            val comanda = porComanda.getValue(p.comandaId)
            PedidoFila(
                pedido = p,
                itens = linhas[p.id].orEmpty(),
                guardaSol = comanda.guardaSolId?.let { porGuardaSol[it]?.numero },
                cliente = porCliente.getValue(comanda.clienteId).nome,
            )
        }
    }

    companion object {
        /** A fila só mostra o que o quiosque ainda precisa atender. O que aguarda pagamento fica de fora. */
        val NA_FILA = listOf(StatusPedido.RECEBIDO, StatusPedido.EM_PREPARO)
    }
}
