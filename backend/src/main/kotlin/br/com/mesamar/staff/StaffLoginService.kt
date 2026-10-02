package br.com.mesamar.staff

import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.conta.OwnerUserDetailsService
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.equipe.AtendenteRepository
import br.com.mesamar.equipe.AtendenteService
import br.com.mesamar.equipe.ResultadoPin
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.core.AuthenticationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

data class AtendenteOpcao(
    val id: UUID,
    val nome: String,
)

sealed interface ResultadoLogin {
    data class Ok(
        val principal: StaffPrincipal,
    ) : ResultadoLogin

    /** E-mail/senha ou PIN errados. Para o PIN, quantas tentativas ainda restam. */
    data class Recusado(
        val tentativasRestantes: Int? = null,
    ) : ResultadoLogin

    data class Bloqueado(
        val ate: Instant,
    ) : ResultadoLogin

    /** Dono sem barraca cadastrada: precisa passar pelo /painel antes. */
    data object SemBarraca : ResultadoLogin

    /** Código de barraca inexistente ou atendente que não está (mais) disponível. */
    data object NaoEncontrado : ResultadoLogin
}

@Service
class StaffLoginService(
    owners: OwnerUserDetailsService,
    encoder: PasswordEncoder,
    private val usuarios: UsuarioRepository,
    private val barracas: BarracaRepository,
    private val atendentes: AtendenteRepository,
    private val atendenteService: AtendenteService,
) {
    /** Mesma conferência de senha do /painel (BCrypt, com proteção contra medir tempo de resposta). */
    private val ownerAuth = ProviderManager(DaoAuthenticationProvider(owners).apply { setPasswordEncoder(encoder) })

    fun atendentesDaBarraca(codigo: String): List<AtendenteOpcao>? {
        val barraca = barracas.findBySlugAndAtivaTrue(codigo.trim().lowercase()) ?: return null
        return atendentes.findAllByBarracaIdOrderByNome(barraca.id).filter { it.ativo }.map { AtendenteOpcao(it.id, it.nome) }
    }

    fun entrarDono(
        email: String,
        senha: String,
    ): ResultadoLogin {
        try {
            ownerAuth.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email.trim(), senha))
        } catch (e: AuthenticationException) {
            return ResultadoLogin.Recusado()
        }
        val usuario = usuarios.findByEmailIgnoreCase(email.trim()) ?: throw BadCredentialsException("conta sumiu")
        val barraca = usuario.barracaId?.let { barracas.findById(it).orElse(null) }?.takeIf { it.ativa } ?: return ResultadoLogin.SemBarraca
        return ResultadoLogin.Ok(StaffPrincipal(barraca.id, PapelStaff.DONO, usuario.id, null, usuario.nome))
    }

    fun entrarAtendente(
        codigo: String,
        atendenteId: UUID,
        pin: String,
        origem: String = "",
    ): ResultadoLogin {
        val barraca = barracas.findBySlugAndAtivaTrue(codigo.trim().lowercase()) ?: return ResultadoLogin.NaoEncontrado
        return when (val r = atendenteService.autenticar(barraca.id, atendenteId, pin, origem)) {
            is ResultadoPin.Ok ->
                ResultadoLogin.Ok(
                    StaffPrincipal(barraca.id, PapelStaff.ATENDENTE, null, r.atendente.id, r.atendente.nome),
                )
            is ResultadoPin.PinErrado -> ResultadoLogin.Recusado(r.tentativasRestantes)
            is ResultadoPin.Bloqueado -> ResultadoLogin.Bloqueado(r.ate)
            ResultadoPin.Indisponivel -> ResultadoLogin.NaoEncontrado
        }
    }
}
