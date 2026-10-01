package br.com.mesamar.pedido

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class OrigemPedido { CLIENTE, ATENDENTE }

/**
 * `AGUARDANDO_PAGAMENTO` só existe no modo "pagar a cada pedido": o pedido ainda não está na fila,
 * e passa a `RECEBIDO` quando o dono confirma o pagamento (Fase 5).
 */
enum class StatusPedido(
    val rotulo: String,
) {
    AGUARDANDO_PAGAMENTO("aguardando pagamento"),
    RECEBIDO("recebido"),
    EM_PREPARO("em preparo"),
    ENTREGUE("entregue"),
    CANCELADO("cancelado"),
    ;

    /** Pedido que o quiosque ainda precisa atender: é o que aparece na fila. */
    val naFila: Boolean get() = this == RECEBIDO || this == EM_PREPARO

    /** Entregue e cancelado são finais: dali o pedido não volta. */
    val terminal: Boolean get() = this == ENTREGUE || this == CANCELADO

    /** As únicas mudanças de status permitidas (a ordem do atendimento só anda para frente). */
    fun podeIrPara(novo: StatusPedido): Boolean =
        when (this) {
            AGUARDANDO_PAGAMENTO -> novo == RECEBIDO || novo == CANCELADO
            RECEBIDO -> novo == EM_PREPARO || novo == ENTREGUE || novo == CANCELADO
            EM_PREPARO -> novo == ENTREGUE || novo == CANCELADO
            ENTREGUE, CANCELADO -> false
        }
}

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
    @Column(nullable = false, length = 20)
    var status: StatusPedido = StatusPedido.RECEBIDO,
    /** Horário do servidor: define a ordem da fila. */
    @Column(name = "criado_em", nullable = false)
    val criadoEm: Instant,
    @Column(name = "em_preparo_em")
    var emPreparoEm: Instant? = null,
    @Column(name = "entregue_em")
    var entregueEm: Instant? = null,
    @Column(name = "cancelado_em")
    var canceladoEm: Instant? = null,
    /** "Esgotou", "Cliente desistiu" ou "Outro: …": o cliente vê este texto na página dele. */
    @Column(name = "motivo_cancelamento", length = 80)
    var motivoCancelamento: String? = null,
    /** Quem fez a última ação no pedido: o dono (usuário) ou um atendente, nunca os dois. */
    @Column(name = "atendido_por_usuario_id")
    var atendidoPorUsuarioId: UUID? = null,
    @Column(name = "atendido_por_atendente_id")
    var atendidoPorAtendenteId: UUID? = null,
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

    fun findByIdAndBarracaId(
        id: UUID,
        barracaId: UUID,
    ): Pedido?

    /** Trava o pedido até o fim da transação: duas ações ao mesmo tempo (dois atendentes) entram uma por vez. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pedido p where p.id = :id and p.barracaId = :barracaId")
    fun travar(
        @Param("id") id: UUID,
        @Param("barracaId") barracaId: UUID,
    ): Pedido?

    /** A fila: ordem de chegada pelo horário do SERVIDOR; no empate, o número do pedido. */
    @Query("select p from Pedido p where p.barracaId = :barracaId and p.status in :status order by p.criadoEm, p.numeroPedido")
    fun findFila(
        @Param("barracaId") barracaId: UUID,
        @Param("status") status: Collection<StatusPedido>,
    ): List<Pedido>

    fun findAllByComandaIdOrderByCriadoEmAscNumeroPedidoAsc(comandaId: UUID): List<Pedido>
}

interface ItemPedidoRepository : JpaRepository<ItemPedido, UUID> {
    fun findAllByPedidoIdOrderByLinha(pedidoId: UUID): List<ItemPedido>

    fun findAllByPedidoIdInOrderByPedidoIdAscLinhaAsc(pedidoIds: Collection<UUID>): List<ItemPedido>

    /** O que a comanda já pediu e ainda não pagou: soma dos itens não cancelados dos pedidos não cancelados. */
    @Query(
        "select coalesce(sum(i.precoSnapshotCentavos * i.quantidade), 0L) from ItemPedido i " +
            "where i.cancelado = false and i.pedidoId in " +
            "(select p.id from Pedido p where p.comandaId = :comandaId and p.status <> br.com.mesamar.pedido.StatusPedido.CANCELADO)",
    )
    fun totalCentavos(
        @Param("comandaId") comandaId: UUID,
    ): Long
}

/** Avisa o resto do sistema que entrou um pedido (a fila e as notificações da Fase 4 escutam isto). */
data class PedidoCriado(
    val pedidoId: UUID,
    val barracaId: UUID,
    val comandaId: UUID,
    val numeroPedido: Int,
    /** `AGUARDANDO_PAGAMENTO`: ainda não entra na fila nem avisa o dono. */
    val status: StatusPedido,
)
