package br.com.mesamar.barraca

import br.com.mesamar.common.ChavePix
import br.com.mesamar.common.Money
import br.com.mesamar.common.Praia
import br.com.mesamar.common.TipoChavePix
import br.com.mesamar.conta.OwnerPrincipal
import br.com.mesamar.conta.Usuario
import br.com.mesamar.conta.UsuarioRepository
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/** Um item da lista "o que falta para abrir" na home do painel. */
data class PassoConfig(
    val titulo: String,
    val detalhe: String,
    val feito: Boolean,
    val link: String?,
)

/**
 * Painel do dono da barraca. Toda página parte do usuário logado: não existe id de barraca
 * na URL, então um dono nunca consegue abrir (nem tentar abrir) a barraca de outro.
 */
@Controller
class PainelController(
    private val usuarios: UsuarioRepository,
    private val service: BarracaService,
) {
    @ModelAttribute
    fun comum(
        @AuthenticationPrincipal principal: OwnerPrincipal?,
        model: Model,
    ) {
        val usuario = principal?.let { usuarios.findById(it.usuarioId).orElse(null) } ?: return
        model.addAttribute("usuario", usuario)
        model.addAttribute("barraca", service.daUsuario(usuario))
        model.addAttribute("praias", Praia.entries)
    }

    @GetMapping("/painel")
    fun home(model: Model): String {
        val barraca = model.getAttribute("barraca") as Barraca? ?: return "redirect:/painel/barraca"
        model.addAttribute("passos", passos(barraca))
        model.addAttribute("resumoRegras", resumoRegras(barraca))
        model.addAttribute("pixDisplay", barraca.chavePix?.let { ChavePix.display(barraca.tipoChavePix!!, it) })
        return "painel/home"
    }

    @GetMapping("/painel/barraca")
    fun barraca(model: Model): String {
        val barraca = model.getAttribute("barraca") as Barraca?
        model.addAttribute("barracaForm", barraca?.let { BarracaForm(it.nome, it.praia.name) } ?: BarracaForm())
        return "painel/barraca"
    }

    @PostMapping("/painel/barraca")
    fun salvarBarraca(
        @ModelAttribute("barracaForm") form: BarracaForm,
        bindingResult: BindingResult,
        model: Model,
        response: HttpServletResponse,
        redirect: RedirectAttributes,
    ): String {
        val usuario = model.getAttribute("usuario") as Usuario
        val barraca = model.getAttribute("barraca") as Barraca?
        val ok =
            if (barraca == null) {
                service.criar(usuario, form, bindingResult) != null
            } else {
                service.atualizarDados(barraca, form, bindingResult)
            }
        if (!ok) {
            response.status = HttpStatus.UNPROCESSABLE_ENTITY.value()
            return "painel/barraca"
        }
        redirect.addFlashAttribute("ok", if (barraca == null) "Barraca criada. Agora falta a chave Pix." else "Dados salvos.")
        return "redirect:/painel"
    }

    @GetMapping("/painel/regras")
    fun regras(model: Model): String {
        val barraca = model.getAttribute("barraca") as Barraca? ?: return "redirect:/painel/barraca"
        model.addAttribute("regrasForm", RegrasForm.of(barraca))
        model.addAttribute("modos", ModoPagamento.entries)
        return "painel/regras"
    }

    @PostMapping("/painel/regras")
    fun salvarRegras(
        @ModelAttribute("regrasForm") form: RegrasForm,
        bindingResult: BindingResult,
        model: Model,
        response: HttpServletResponse,
        redirect: RedirectAttributes,
    ): String {
        val barraca = model.getAttribute("barraca") as Barraca? ?: return "redirect:/painel/barraca"
        if (!service.atualizarRegras(barraca, form, bindingResult)) {
            response.status = HttpStatus.UNPROCESSABLE_ENTITY.value()
            model.addAttribute("modos", ModoPagamento.entries)
            return "painel/regras"
        }
        redirect.addFlashAttribute("ok", "Regras salvas.")
        return "redirect:/painel"
    }

    @GetMapping("/painel/pix")
    fun pix(model: Model): String {
        val barraca = model.getAttribute("barraca") as Barraca? ?: return "redirect:/painel/barraca"
        model.addAttribute("pixForm", PixForm.of(barraca))
        model.addAttribute("tiposPix", TipoChavePix.entries)
        return "painel/pix"
    }

    @PostMapping("/painel/pix")
    fun salvarPix(
        @ModelAttribute("pixForm") form: PixForm,
        bindingResult: BindingResult,
        model: Model,
        response: HttpServletResponse,
        redirect: RedirectAttributes,
    ): String {
        val barraca = model.getAttribute("barraca") as Barraca? ?: return "redirect:/painel/barraca"
        if (!service.atualizarPix(barraca, form, bindingResult)) {
            response.status = HttpStatus.UNPROCESSABLE_ENTITY.value()
            model.addAttribute("tiposPix", TipoChavePix.entries)
            return "painel/pix"
        }
        redirect.addFlashAttribute("ok", "Chave Pix salva. Confira abaixo se está igual à do seu banco.")
        return "redirect:/painel"
    }

    private fun passos(b: Barraca): List<PassoConfig> =
        listOf(
            PassoConfig("Dados da barraca", "${b.nome} · ${b.praia.label}", true, "/painel/barraca"),
            PassoConfig(
                "Chave Pix",
                if (b.temPix) "Recebendo em ${b.nomeRecebedorPix}" else "Sem ela, o cliente não consegue pagar pelo celular.",
                b.temPix,
                "/painel/pix",
            ),
            PassoConfig("Regras de operação", "Já vêm preenchidas com o padrão. Revise quando quiser.", true, "/painel/regras"),
            PassoConfig("Atendentes", "Chega na próxima etapa do piloto.", false, null),
            PassoConfig("Guarda-sóis e QR codes", "Chega na próxima etapa do piloto.", false, null),
            PassoConfig("Cardápio", "Chega na próxima etapa do piloto.", false, null),
        )

    private fun resumoRegras(b: Barraca): List<Pair<String, String>> =
        listOf(
            "Guarda-sóis" to "até ${b.limiteGuardaSois}",
            "Pagamento" to b.modoPagamento.label,
            "Limite de conta aberta" to (b.limiteSaldoAbertoCentavos?.let(Money::format) ?: "sem limite"),
            "Alerta de pedido parado" to "${b.tempoAlertaPedidoMin} min",
        )
}
