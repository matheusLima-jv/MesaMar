package br.com.mesamar.lead

import br.com.mesamar.common.Praia
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/** Pessoa interessada no piloto, vinda do formulário da landing. */
@Entity
@Table(name = "lead")
class Lead(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 80)
    var nome: String,
    @Column(name = "nome_barraca", nullable = false, length = 80)
    var nomeBarraca: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var praia: Praia,
    /** Formato E.164, ex.: +5513999998888. Único: a mesma pessoa não vira dois leads. */
    @Column(nullable = false, length = 16, unique = true)
    val whatsapp: String,
    @Column(name = "consentimento_em", nullable = false)
    var consentimentoEm: Instant,
    @Column(nullable = false, length = 40)
    val origem: String,
    @Column(name = "criado_em", nullable = false)
    val criadoEm: Instant,
    @Column(name = "atualizado_em", nullable = false)
    var atualizadoEm: Instant,
)

interface LeadRepository : JpaRepository<Lead, UUID> {
    fun findByWhatsapp(whatsapp: String): Lead?
}
