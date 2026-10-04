package br.com.mesamar.equipe

import br.com.mesamar.barraca.PainelPage
import br.com.mesamar.barraca.barraca
import br.com.mesamar.sincronia.SincroniaAparelho
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Uma linha da lista da equipe, já com o texto de situação. */
data class AtendenteView(
    val id: UUID,
    val nome: String,
    val ativo: Boolean,
    val bloqueadoAte: String?,
) {
    val situacao: String
        get() =
            when {
                bloqueadoAte != null -> "Bloqueado até $bloqueadoAte"
                ativo -> "Ativo"
                else -> "Desativado"
            }
}

/** Um aparelho da equipe na lista do dono: quando sincronizou pela última vez e o que ficou nele. */
data class AparelhoLinha(
    val quem: String,
    val tipo: String,
    val sincronizadoEm: String,
    val pendentes: Int,
    val conflitos: Int,
    val atencao: Boolean,
    val pendenteDesde: String?,
) {
    val situacao: String
        get() =
            listOfNotNull(
                if (pendentes == 0) "Tudo enviado" else "$pendentes ${if (pendentes == 1) "ação aguardando" else "ações aguardando"} envio",
                conflitos.takeIf { it > 0 }?.let { "$it ${if (it == 1) "recusada" else "recusadas"}: confira no aparelho" },
            ).joinToString(" · ")
}

/** Equipe da barraca: atendentes que entram no app do quiosque com PIN. */
@Controller
@PainelPage
class EquipeController(
    private val service: AtendenteService,
    private val sincronia: SincroniaAparelho,
    private val clock: Clock,
) {
    @GetMapping("/painel/equipe")
    fun equipe(model: Model): String {
        model.barraca() ?: return "redirect:/painel/barraca"
        if (!model.containsAttribute("atendenteForm")) model.addAttribute("atendenteForm", AtendenteForm())
        return render(model)
    }

    @PostMapping("/painel/equipe")
    fun criar(
        @ModelAttribute("atendenteForm") form: AtendenteForm,
        bindingResult: BindingResult,
        model: Model,
        response: HttpServletResponse,
        redirect: RedirectAttributes,
    ): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        val atendente = service.criar(barraca, form, bindingResult)
        if (atendente == null) {
            response.status = HttpStatus.UNPROCESSABLE_ENTITY.value()
            form.pin = ""
            return render(model)
        }
        redirect.addFlashAttribute("ok", "${atendente.nome} foi adicionado. Passe o PIN para ele pessoalmente.")
        return "redirect:/painel/equipe"
    }

    @PostMapping("/painel/equipe/{id}/pin")
    fun trocarPin(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "") pin: String,
        model: Model,
        redirect: RedirectAttributes,
    ): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        val errors = BeanPropertyBindingResult(AtendenteForm(pin = pin), "pin")
        if (service.trocarPin(barraca, id, pin, errors)) {
            redirect.addFlashAttribute("ok", "PIN trocado. Se o atendente estava bloqueado, já pode entrar de novo.")
        } else {
            redirect.addFlashAttribute("erro", errors.fieldErrors.firstOrNull()?.defaultMessage ?: "Atendente não encontrado.")
            redirect.addFlashAttribute("abrirPin", id)
        }
        return "redirect:/painel/equipe"
    }

    @PostMapping("/painel/equipe/{id}/ativo")
    fun ativo(
        @PathVariable id: UUID,
        @RequestParam ativo: Boolean,
        model: Model,
        redirect: RedirectAttributes,
    ): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        val atendente = service.definirAtivo(barraca, id, ativo)
        if (atendente == null) {
            redirect.addFlashAttribute("erro", "Atendente não encontrado.")
        } else {
            redirect.addFlashAttribute(
                "ok",
                if (ativo) "${atendente.nome} voltou para a equipe." else "${atendente.nome} não consegue mais entrar no app.",
            )
        }
        return "redirect:/painel/equipe"
    }

    @PostMapping("/painel/equipe/{id}/revogar-sessoes")
    fun revogarSessoes(
        @PathVariable id: UUID,
        model: Model,
        redirect: RedirectAttributes,
    ): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        val atendente = service.revogarSessoes(barraca, id)
        redirect.addFlashAttribute(
            if (atendente == null) "erro" else "ok",
            atendente?.let { "${it.nome} saiu de todos os aparelhos. Para entrar de novo, precisa digitar o PIN." }
                ?: "Atendente não encontrado.",
        )
        return "redirect:/painel/equipe"
    }

    @PostMapping("/painel/equipe/{id}/desbloquear")
    fun desbloquear(
        @PathVariable id: UUID,
        model: Model,
        redirect: RedirectAttributes,
    ): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        val atendente = service.desbloquear(barraca, id)
        redirect.addFlashAttribute(
            if (atendente == null) "erro" else "ok",
            atendente?.let { "${it.nome} foi desbloqueado." } ?: "Atendente não encontrado.",
        )
        return "redirect:/painel/equipe"
    }

    private fun render(model: Model): String {
        val barraca = model.barraca()!!
        val now = Instant.now(clock)
        model.addAttribute(
            "atendentes",
            service.listar(barraca).map {
                AtendenteView(it.id, it.nome, it.ativo, it.bloqueadoAte?.takeIf { _ -> it.bloqueado(now) }?.let(HORA::format))
            },
        )
        model.addAttribute(
            "aparelhos",
            sincronia.listar(barraca.id).map {
                AparelhoLinha(
                    it.quem,
                    it.tipo,
                    DIA_HORA.format(it.sincronizadoEm),
                    it.pendentes,
                    it.conflitos,
                    it.atencao,
                    it.pendenteDesde?.let(DIA_HORA::format),
                )
            },
        )
        return "painel/equipe"
    }

    companion object {
        private val HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("America/Sao_Paulo"))
        private val DIA_HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm").withZone(ZoneId.of("America/Sao_Paulo"))
    }
}
