package br.com.mesamar.conta

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.CredentialsContainer
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.io.Serializable
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Quem está logado no /painel. Guarda só o id: nome, e-mail e barraca são sempre
 * relidos do banco (assim uma troca de dados aparece na hora, sem sair e entrar).
 */
class OwnerPrincipal(
    val usuarioId: UUID,
    private val email: String,
    private var senhaHash: String,
) : UserDetails,
    CredentialsContainer,
    Serializable {
    override fun getUsername(): String = email

    override fun getPassword(): String = senhaHash

    override fun getAuthorities(): Collection<GrantedAuthority> = listOf(SimpleGrantedAuthority("ROLE_OWNER"))

    /** Chamado pelo Spring Security logo após o login: o hash não fica guardado na sessão. */
    override fun eraseCredentials() {
        senhaHash = ""
    }
}

@Service
class OwnerUserDetailsService(
    private val usuarios: UsuarioRepository,
) : UserDetailsService {
    override fun loadUserByUsername(username: String): UserDetails {
        val usuario =
            usuarios.findByEmailIgnoreCase(username.trim())
                ?: throw UsernameNotFoundException("conta não encontrada")
        return OwnerPrincipal(usuario.id, usuario.email, usuario.senhaHash)
    }
}

/** Depois do login: registra o último acesso e sempre leva para a home do painel. */
@Component
class OwnerLoginSuccessHandler(
    private val usuarios: UsuarioRepository,
    private val clock: Clock,
) : SimpleUrlAuthenticationSuccessHandler("/painel") {
    init {
        setAlwaysUseDefaultTargetUrl(true)
    }

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        val principal = authentication.principal as? OwnerPrincipal
        if (principal != null) {
            usuarios.findById(principal.usuarioId).ifPresent {
                it.ultimoLoginEm = Instant.now(clock)
                usuarios.save(it)
            }
        }
        super.onAuthenticationSuccess(request, response, authentication)
    }
}
