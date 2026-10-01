package br.com.mesamar.conta

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

/** Dono de barraca (no MVP, uma barraca por dono). */
@Entity
@Table(name = "usuario")
class Usuario(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "barraca_id")
    var barracaId: UUID? = null,
    @Column(nullable = false, length = 80)
    var nome: String,
    @Column(nullable = false, length = 254)
    var email: String,
    @Column(name = "senha_hash", nullable = false, length = 100)
    var senhaHash: String,
    @Column(nullable = false, length = 10)
    val papel: String = "OWNER",
    @Column(name = "criado_em", nullable = false)
    val criadoEm: Instant,
    @Column(name = "ultimo_login_em")
    var ultimoLoginEm: Instant? = null,
)

interface UsuarioRepository : JpaRepository<Usuario, UUID> {
    @Query("select u from Usuario u where lower(u.email) = lower(:email)")
    fun findByEmailIgnoreCase(email: String): Usuario?
}

@Entity
@Table(name = "convite")
class Convite(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    val tokenHash: String,
    @Column(name = "lead_id")
    val leadId: UUID? = null,
    @Column(length = 80)
    val observacao: String? = null,
    @Column(name = "criado_em", nullable = false)
    val criadoEm: Instant,
    @Column(name = "expira_em", nullable = false)
    val expiraEm: Instant,
    @Column(name = "usado_em")
    var usadoEm: Instant? = null,
    @Column(name = "usado_por")
    var usadoPor: UUID? = null,
) {
    fun isValid(now: Instant): Boolean = usadoEm == null && now.isBefore(expiraEm)
}

interface ConviteRepository : JpaRepository<Convite, UUID> {
    fun findByTokenHash(tokenHash: String): Convite?

    fun findAllByOrderByCriadoEmDesc(): List<Convite>
}

@Entity
@Table(name = "reset_senha")
class ResetSenha(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "usuario_id", nullable = false)
    val usuarioId: UUID,
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    val tokenHash: String,
    @Column(name = "criado_em", nullable = false)
    val criadoEm: Instant,
    @Column(name = "expira_em", nullable = false)
    val expiraEm: Instant,
    @Column(name = "usado_em")
    var usadoEm: Instant? = null,
) {
    fun isValid(now: Instant): Boolean = usadoEm == null && now.isBefore(expiraEm)
}

interface ResetSenhaRepository : JpaRepository<ResetSenha, UUID> {
    fun findByTokenHash(tokenHash: String): ResetSenha?

    fun findAllByUsuarioIdAndUsadoEmIsNull(usuarioId: UUID): List<ResetSenha>
}
