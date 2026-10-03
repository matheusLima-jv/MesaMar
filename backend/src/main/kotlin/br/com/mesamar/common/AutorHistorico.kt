package br.com.mesamar.common

import br.com.mesamar.conta.OwnerPrincipal
import br.com.mesamar.staff.StaffPrincipal
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class AutorHistorico(
    private val jdbc: JdbcTemplate,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun marcar() {
        val principal = SecurityContextHolder.getContext().authentication?.principal
        val usuario =
            when (principal) {
                is OwnerPrincipal -> principal.usuarioId
                is StaffPrincipal -> principal.usuarioId
                else -> null
            }
        val atendente = (principal as? StaffPrincipal)?.atendenteId
        val origem =
            when {
                principal is OwnerPrincipal -> "PAINEL"
                principal is StaffPrincipal -> "EQUIPE"
                principal == "admin" -> "ADMIN"
                else -> "SISTEMA"
            }
        jdbc.queryForList(
            "SELECT set_config('mesamar.usuario', ?, true), set_config('mesamar.atendente', ?, true), set_config('mesamar.origem', ?, true)",
            usuario?.toString().orEmpty(),
            atendente?.toString().orEmpty(),
            origem,
        )
    }
}
