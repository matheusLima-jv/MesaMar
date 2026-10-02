package br.com.mesamar.pagamento

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/** Como o cliente paga no quiosque. O sistema não toca no dinheiro: só registra o que o quiosque confirmou. */
enum class MetodoPagamento(
    val rotulo: String,
) {
    PIX("Pix"),
    DINHEIRO("Dinheiro"),
    CARTAO("Cartão"),
    ;

    companion object {
        fun fromCode(code: String?): MetodoPagamento? = entries.firstOrNull { it.name == code }
    }
}

/** Um pagamento confirmado por quem está no quiosque. Abate da conta de uma comanda. */
@Entity
@Table(name = "pagamento")
class Pagamento(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "barraca_id", nullable = false)
    val barracaId: UUID,
    @Column(name = "comanda_id", nullable = false)
    val comandaId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val metodo: MetodoPagamento,
    @Column(name = "valor_centavos", nullable = false)
    val valorCentavos: Long,
    /** Dinheiro: quanto o cliente entregou (o troco é este valor menos `valorCentavos`). */
    @Column(name = "valor_recebido_centavos")
    val valorRecebidoCentavos: Long? = null,
    @Column(name = "confirmado_por_usuario_id")
    val confirmadoPorUsuarioId: UUID? = null,
    @Column(name = "confirmado_por_atendente_id")
    val confirmadoPorAtendenteId: UUID? = null,
    @Column(name = "confirmado_em", nullable = false)
    val confirmadoEm: Instant,
)

interface PagamentoRepository : JpaRepository<Pagamento, UUID> {
    fun findAllByComandaIdOrderByConfirmadoEmAsc(comandaId: UUID): List<Pagamento>

    /** Quanto a comanda já pagou, em centavos. */
    @Query("select coalesce(sum(p.valorCentavos), 0L) from Pagamento p where p.comandaId = :comandaId")
    fun totalCentavos(
        @Param("comandaId") comandaId: UUID,
    ): Long
}
