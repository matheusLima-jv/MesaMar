package br.com.mesamar.guardasol

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "guarda_sol")
class GuardaSol(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "barraca_id", nullable = false)
    val barracaId: UUID,
    @Column(nullable = false)
    val numero: Int,
    @Column(nullable = false)
    var ativo: Boolean = true,
    @Column(name = "criado_em", nullable = false)
    val criadoEm: Instant,
)

interface GuardaSolRepository : JpaRepository<GuardaSol, UUID> {
    fun findAllByBarracaIdOrderByNumero(barracaId: UUID): List<GuardaSol>

    fun findByIdAndBarracaId(
        id: UUID,
        barracaId: UUID,
    ): GuardaSol?

    fun countByBarracaIdAndAtivoTrue(barracaId: UUID): Int
}
