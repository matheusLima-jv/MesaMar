package br.com.mesamar.pedido

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class OrigemPedido { CLIENTE, ATENDENTE }

enum class StatusPedido { RECEBIDO, EM_PREPARO, ENTREGUE, CANCELADO }

/**
 * Um pedido da comanda. O `id` nasce no celular de quem pede: é isso que torna o envio idempotente
 * (o mesmo id enviado de novo devolve este pedido, sem criar outro).
 */
@Entity
@Table(name = "pedido")
class Pedido(
    @Id
    val id: UUID,
    @Column(name = "barraca_id", nullable = false)
    val barracaId: UUID,
    @Column(name = "comanda_id", nullable = false)
    val comandaId: UUID,
    @Column(nullable = false)
    val dia: LocalDate,
    @Column(name = "numero_pedido", nullable = false)
    val numeroPedido: Int,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val origem: OrigemPedido = OrigemPedido.CLIENTE,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    var status: StatusPedido = StatusPedido.RECEBIDO,
    /** Horário do servidor: define a ordem da fila. */
    @Column(name = "criado_em", nullable = false)
    val criadoEm: Instant,
)

/** Linha do pedido, com o nome e o preço do produto congelados na hora (snapshot). */
@Entity
@Table(name = "item_pedido")
class ItemPedido(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "pedido_id", nullable = false)
    val pedidoId: UUID,
    @Column(name = "barraca_id", nullable = false)
    val barracaId: UUID,
    @Column(name = "produto_id", nullable = false)
    val produtoId: UUID,
    @Column(nullable = false)
    val linha: Int,
    @Column(name = "nome_snapshot", nullable = false, length = 60)
    val nomeSnapshot: String,
    @Column(name = "preco_snapshot_centavos", nullable = false)
    val precoSnapshotCentavos: Long,
    @Column(nullable = false)
    val quantidade: Int,
    @Column(length = 80)
    val observacao: String? = null,
    @Column(nullable = false)
    var cancelado: Boolean = false,
    @Column(name = "motivo_cancelamento", length = 80)
    var motivoCancelamento: String? = null,
) {
    val subtotalCentavos: Long get() = precoSnapshotCentavos * quantidade
}

interface PedidoRepository : JpaRepository<Pedido, UUID> {
    fun countByBarracaId(barracaId: UUID): Long

    fun findAllByComandaIdOrderByCriadoEmAscNumeroPedidoAsc(comandaId: UUID): List<Pedido>
}

interface ItemPedidoRepository : JpaRepository<ItemPedido, UUID> {
    fun findAllByPedidoIdOrderByLinha(pedidoId: UUID): List<ItemPedido>
}

/** Avisa o resto do sistema que entrou um pedido (a fila e as notificações da Fase 4 escutam isto). */
data class PedidoCriado(
    val pedidoId: UUID,
    val barracaId: UUID,
    val comandaId: UUID,
    val numeroPedido: Int,
)
