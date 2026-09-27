package br.com.mesamar.landing

import br.com.mesamar.config.SiteProperties
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute

/** Páginas públicas: landing e política de privacidade. */
@Controller
class LandingController(
    private val site: SiteProperties,
) {
    @ModelAttribute
    fun siteAttributes(model: Model) {
        model.addAttribute("site", site)
        if (site.hasWhatsapp) {
            model.addAttribute("whatsappLink", site.whatsappLink(WHATSAPP_MESSAGE))
        }
    }

    @GetMapping("/")
    fun landing(model: Model): String {
        model.addAttribute("canonicalUrl", site.absoluteUrl("/"))
        return "landing/index"
    }

    @GetMapping("/privacidade")
    fun privacy(model: Model): String {
        model.addAttribute("canonicalUrl", site.absoluteUrl("/privacidade"))
        return "landing/privacidade"
    }

    companion object {
        const val WHATSAPP_MESSAGE =
            "Olá! Vi o site do MesaMar e tenho interesse no piloto gratuito para a minha barraca."
    }
}
