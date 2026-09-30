package br.com.mesamar.equipe

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "atendente")
class Atendente(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "barraca_id", nullable = false)
    val barracaId: UUID,
    @Column(nullable = false, length = 40)
    var nome: String,
    @Column(name = "pin_hash", nullable = false, length = 100)
    var pinHash: String,
    @Column(nullable = false)
    var ativo: Boolean = true,
    @Column(name = "tentativas_falhas", nullable = false)
    var tentativasFalhas: Int = 0,
    @Column(name = "bloqueado_ate")
    var bloqueadoAte: Instant? = null,
    @Column(name = "criado_em", nullable = false)
    val criadoEm: Instant,
    @Column(name = "atualizado_em", nullable = false)
    var atualizadoEm: Instant,
) {
    fun bloqueado(now: Instant): Boolean = bloqueadoAte?.isAfter(now) == true
}

/** Toda consulta leva o barracaId: não existe busca de atendente "solta", sem a barraca. */
interface AtendenteRepository : JpaRepository<Atendente, UUID> {
    fun findAllByBarracaIdOrderByNome(barracaId: UUID): List<Atendente>

    fun findByIdAndBarracaId(
        id: UUID,
        barracaId: UUID,
    ): Atendente?

    @Query("select count(a) > 0 from Atendente a where a.barracaId = :barracaId and lower(a.nome) = lower(:nome) and a.id <> :exceto")
    fun existsNome(
        barracaId: UUID,
        nome: String,
        exceto: UUID,
    ): Boolean

    /** Trava a linha durante a conferência do PIN: tentativas em paralelo não furam o bloqueio. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Atendente a where a.id = :id and a.barracaId = :barracaId")
    fun findForUpdate(
        id: UUID,
        barracaId: UUID,
    ): Atendente?
}
