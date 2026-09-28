package br.com.mesamar.landing

import br.com.mesamar.common.Praia
import br.com.mesamar.common.RateLimiter
import br.com.mesamar.common.TurnstileProperties
import br.com.mesamar.common.TurnstileVerifier
import br.com.mesamar.config.SiteProperties
import br.com.mesamar.lead.LeadForm
import br.com.mesamar.lead.LeadService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Duration

/** Páginas públicas: landing, lista de espera e política de privacidade. */
@Controller
class LandingController(
    private val site: SiteProperties,
    private val turnstile: TurnstileProperties,
    private val turnstileVerifier: TurnstileVerifier,
    private val leadService: LeadService,
    private val rateLimiter: RateLimiter,
) {
    @ModelAttribute
    fun siteAttributes(model: Model) {
        model.addAttribute("site", site)
        model.addAttribute("turnstile", turnstile)
        model.addAttribute("praias", Praia.entries)
        if (site.hasWhatsapp) {
            model.addAttribute("whatsappLink", site.whatsappLink(WHATSAPP_MESSAGE))
        }
    }

    @GetMapping("/")
    fun landing(model: Model): String {
        if (!model.containsAttribute("leadForm")) model.addAttribute("leadForm", LeadForm())
        return renderLanding(model)
    }

    @PostMapping("/lista-de-espera")
    fun joinWaitlist(
        @Valid @ModelAttribute("leadForm") form: LeadForm,
        bindingResult: BindingResult,
        @RequestParam("cf-turnstile-response", required = false) turnstileToken: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
        model: Model,
    ): String {
        val ip = request.remoteAddr
        if (!rateLimiter.tryAcquire("lead:$ip", LEAD_LIMIT, LEAD_WINDOW)) {
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            return "landing/limite"
        }

        // Robô preencheu o campo invisível: finge que deu certo e não salva nada.
        if (form.isBot) return "redirect:/lista-de-espera/obrigado"

        leadService.validate(form, bindingResult)
        if (!bindingResult.hasErrors() && !turnstileVerifier.verify(turnstileToken, ip)) {
            bindingResult.reject("turnstile", "Não conseguimos confirmar que você não é um robô. Tente enviar de novo.")
        }
        if (bindingResult.hasErrors()) {
            response.status = HttpStatus.UNPROCESSABLE_ENTITY.value()
            return renderLanding(model)
        }

        leadService.register(form, origem = "landing")
        return "redirect:/lista-de-espera/obrigado"
    }

    @GetMapping("/lista-de-espera/obrigado")
    fun thanks(model: Model): String {
        model.addAttribute("canonicalUrl", site.absoluteUrl("/lista-de-espera/obrigado"))
        return "landing/obrigado"
    }

    @GetMapping("/privacidade")
    fun privacy(model: Model): String {
        model.addAttribute("canonicalUrl", site.absoluteUrl("/privacidade"))
        return "landing/privacidade"
    }

    private fun renderLanding(model: Model): String {
        model.addAttribute("canonicalUrl", site.absoluteUrl("/"))
        return "landing/index"
    }

    companion object {
        const val WHATSAPP_MESSAGE =
            "Olá! Vi o site do MesaMar e tenho interesse no piloto gratuito para a minha barraca."
        const val LEAD_LIMIT = 8
        val LEAD_WINDOW: Duration = Duration.ofMinutes(10)
    }
}
