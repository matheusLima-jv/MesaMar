package br.com.mesamar.guardasol

import br.com.mesamar.barraca.PainelPage
import br.com.mesamar.barraca.barraca
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.util.UUID

@Controller
@PainelPage
class GuardaSolController(
    private val service: GuardaSolService,
) {
    @GetMapping("/painel/guarda-sois")
    fun lista(model: Model): String {
        model.barraca() ?: return "redirect:/painel/barraca"
        if (!model.containsAttribute("loteForm")) model.addAttribute("loteForm", sugestao(model))
        return render(model)
    }

    @PostMapping("/painel/guarda-sois")
    fun criarLote(
        @ModelAttribute("loteForm") form: LoteForm,
        bindingResult: BindingResult,
        model: Model,
        response: HttpServletResponse,
        redirect: RedirectAttributes,
    ): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        val resultado = service.criarLote(barraca, form, bindingResult)
        if (resultado == null) {
            response.status = HttpStatus.UNPROCESSABLE_ENTITY.value()
            return render(model)
        }
        val partes =
            listOfNotNull(
                resultado.criados.takeIf { it > 0 }?.let { "$it ${plural(it, "criado", "criados")}" },
                resultado.reativados.takeIf { it > 0 }?.let { "$it ${plural(it, "reativado", "reativados")}" },
                resultado.jaExistiam.takeIf { it > 0 }?.let { "$it já ${plural(it, "existia", "existiam")}" },
            )
        redirect.addFlashAttribute("ok", "Pronto: " + partes.joinToString(", ") + ".")
        return "redirect:/painel/guarda-sois"
    }

    @PostMapping("/painel/guarda-sois/{id}/alternar")
    fun alternar(
        @PathVariable id: UUID,
        model: Model,
        redirect: RedirectAttributes,
    ): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        service.alternar(barraca, id)?.let { redirect.addFlashAttribute("erro", it) }
        return "redirect:/painel/guarda-sois"
    }

    private fun render(model: Model): String {
        val barraca = model.barraca()!!
        val lista = service.listar(barraca)
        model.addAttribute("guardaSois", lista)
        model.addAttribute("ativos", lista.count { it.ativo })
        return "painel/guarda-sois"
    }

    /** Sugere continuar de onde parou: se já existem 1..20, propõe 21..limite. */
    private fun sugestao(model: Model): LoteForm {
        val barraca = model.barraca()!!
        val maior = service.listar(barraca).maxOfOrNull { it.numero } ?: 0
        val de = maior + 1
        val ate = (maior + barraca.limiteGuardaSois - service.ativos(barraca)).coerceAtLeast(de)
        return LoteForm(de = de.toString(), ate = ate.coerceAtMost(GuardaSolService.NUMERO_MAX).toString())
    }

    private fun plural(
        n: Int,
        um: String,
        varios: String,
    ) = if (n == 1) um else varios
}
