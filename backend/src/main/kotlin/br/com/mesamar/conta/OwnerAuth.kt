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
    /**
     * A última troca de senha (`credenciais_em`) lida junto com o hash que o login conferiu. A sessão vale enquanto o
     * banco tiver este mesmo valor: uma troca confirmada durante o login (depois de ler o hash antigo) também derruba a
     * sessão, o que uma comparação com a hora do login não garante.
     */
    val versaoCredencial: Instant? = null,
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
        return OwnerPrincipal(usuario.id, usuario.email, usuario.senhaHash, usuario.credenciaisEm)
    }
}

/**
 * A cada requisição do painel, confere se a sessão ainda vale: a conta existe e a senha é a mesma que o login conferiu.
 * NÃO é um @Component de propósito (ver StaffPrincipalCheckFilter).
 */
class OwnerPrincipalCheckFilter(
    private val usuarios: UsuarioRepository,
) : org.springframework.web.filter.OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: jakarta.servlet.FilterChain,
    ) {
        val principal =
            org.springframework.security.core.context.SecurityContextHolder
                .getContext()
                .authentication
                ?.principal as? OwnerPrincipal
        if (principal != null) {
            val usuario = usuarios.findById(principal.usuarioId).orElse(null)
            if (usuario == null || usuario.credenciaisEm != principal.versaoCredencial) {
                org.springframework.security.core.context.SecurityContextHolder
                    .clearContext()
                request.getSession(false)?.removeAttribute(CHAVE_SESSAO_PAINEL)
            }
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        const val CHAVE_SESSAO_PAINEL = "MESAMAR_PAINEL"
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
        if (principal != null) usuarios.registrarLogin(principal.usuarioId, Instant.now(clock))
        super.onAuthenticationSuccess(request, response, authentication)
    }
}
