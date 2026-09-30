package br.com.mesamar.barraca

import br.com.mesamar.common.Praia
import br.com.mesamar.conta.OwnerPrincipal
import br.com.mesamar.conta.UsuarioRepository
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * Marca os controllers das páginas logadas do /painel. Todos recebem, pelo PainelAdvice,
 * o usuário e a barraca de quem está logado: a barraca nunca vem da URL nem do formulário.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class PainelPage

@ControllerAdvice(annotations = [PainelPage::class])
class PainelAdvice(
    private val usuarios: UsuarioRepository,
    private val barracas: BarracaService,
) {
    @ModelAttribute
    fun comum(
        @AuthenticationPrincipal principal: OwnerPrincipal?,
        model: Model,
    ) {
        val usuario = principal?.let { usuarios.findById(it.usuarioId).orElse(null) } ?: return
        model.addAttribute("usuario", usuario)
        model.addAttribute("barraca", barracas.daUsuario(usuario))
        model.addAttribute("praias", Praia.entries)
    }
}

/** A barraca do dono logado (null no primeiro acesso, antes de cadastrar). */
fun Model.barraca(): Barraca? = getAttribute("barraca") as Barraca?
