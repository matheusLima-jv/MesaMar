package br.com.mesamar.staff

import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.conta.UsuarioRepository
import br.com.mesamar.equipe.AtendenteRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.Serializable
import java.util.UUID

enum class PapelStaff { DONO, ATENDENTE }

/**
 * Quem está usando o app do quiosque. A barraca vem SEMPRE daqui (da sessão), nunca da URL:
 * todo endpoint de /api/staff trabalha só com a barraca de quem entrou.
 */
data class StaffPrincipal(
    val barracaId: UUID,
    val papel: PapelStaff,
    val usuarioId: UUID?,
    val atendenteId: UUID?,
    val nome: String,
    /** Quando o login aconteceu: se a senha do dono mudou depois, a sessão deixa de valer. */
    val logadoEm: java.time.Instant = java.time.Instant.now(),
) : Serializable {
    val authorities: List<GrantedAuthority>
        get() = listOf(SimpleGrantedAuthority("ROLE_STAFF"), SimpleGrantedAuthority("ROLE_STAFF_$papel"))
}

/**
 * Sessão do app: chave própria na sessão HTTP (MESAMAR_STAFF), separada do /painel e do /admin.
 * É a mesma instância usada pela cadeia de segurança (para ler) e pelo login (para gravar).
 */
@Component
class StaffSessao {
    val repository =
        HttpSessionSecurityContextRepository().apply { setSpringSecurityContextKey(CHAVE) }

    companion object {
        const val CHAVE = "MESAMAR_STAFF"
    }
}

/**
 * A cada requisição, confere se quem está logado ainda pode estar: atendente desativado pelo dono
 * (ou conta/barraca que mudou) perde o acesso na hora, sem esperar a sessão expirar.
 *
 * NÃO é um @Component de propósito: o Spring Boot registraria qualquer Filter como filtro global
 * do servlet, e aí o OncePerRequestFilter seria pulado dentro da cadeia de segurança.
 */
class StaffPrincipalCheckFilter(
    private val acesso: StaffAcesso,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? StaffPrincipal
        if (principal != null && !acesso.valido(principal)) {
            SecurityContextHolder.clearContext()
            request.getSession(false)?.removeAttribute(StaffSessao.CHAVE)
        }
        filterChain.doFilter(request, response)
    }
}

/**
 * Quem está logado no app ainda pode estar? Usado a cada requisição ([StaffPrincipalCheckFilter]) e também pelas
 * conexões de tempo real já abertas (o `HubEventos` confere de tempos em tempos e derruba quem perdeu o acesso).
 */
@Component
class StaffAcesso(
    private val atendentes: AtendenteRepository,
    private val usuarios: UsuarioRepository,
    private val barracas: BarracaRepository,
) {
    fun valido(p: StaffPrincipal): Boolean {
        val barracaAtiva = barracas.findById(p.barracaId).map { it.ativa }.orElse(false)
        if (!barracaAtiva) return false
        return when (p.papel) {
            PapelStaff.DONO -> {
                val usuario = p.usuarioId?.let { usuarios.findById(it).orElse(null) }
                val credenciais = usuario?.credenciaisEm
                usuario?.barracaId == p.barracaId && (credenciais == null || !credenciais.isAfter(p.logadoEm))
            }
            PapelStaff.ATENDENTE -> {
                val atendente = p.atendenteId?.let { atendentes.findByIdAndBarracaId(it, p.barracaId) }
                val revogadas = atendente?.sessoesRevogadasEm
                // Desativar ou "sair de todos os aparelhos" mata as sessões de antes, mesmo que o atendente volte depois
                atendente?.ativo == true && (revogadas == null || !revogadas.isAfter(p.logadoEm))
            }
        }
    }
}
