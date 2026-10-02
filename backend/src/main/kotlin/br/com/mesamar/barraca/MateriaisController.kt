package br.com.mesamar.barraca

import br.com.mesamar.config.SiteProperties
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * Materiais para imprimir (Fase 7 · checkpoint 7.2): o guia de 1 página para o dono e os atendentes e a plaquinha do
 * balcão. São páginas do painel, com o nome e o código da barraca de quem está logado, e uma folha de estilo de impressão
 * (A4): "Imprimir" no navegador (ou "Salvar como PDF") dá a folha pronta.
 */
@Controller
@PainelPage
class MateriaisController(
    private val site: SiteProperties,
) {
    @GetMapping("/painel/materiais")
    fun materiais(model: Model): String {
        model.barraca() ?: return "redirect:/painel/barraca"
        model.addAttribute("ativo", "materiais")
        return "painel/materiais"
    }

    @GetMapping("/painel/materiais/guia")
    fun guia(model: Model): String {
        model.barraca() ?: return "redirect:/painel/barraca"
        model.addAttribute("appUrl", site.absoluteUrl("/staff/"))
        return "painel/guia"
    }

    @GetMapping("/painel/materiais/plaquinha")
    fun plaquinha(model: Model): String {
        model.barraca() ?: return "redirect:/painel/barraca"
        return "painel/plaquinha"
    }
}
