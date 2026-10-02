package br.com.mesamar.problema

import br.com.mesamar.admin.AdminQueries
import br.com.mesamar.barraca.PainelPage
import br.com.mesamar.barraca.barraca
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/** Um aviso já formatado para a tela do dono. */
data class AvisoLinha(
    val quando: String,
    val origem: String,
    val tela: String,
    val conexao: String,
    val mensagem: String,
)

/**
 * Os avisos de "reportar problema" da própria barraca, no painel do dono (Fase 7 · pedido do 7.3). A barraca é sempre a de
 * quem está logado; o navegador de quem avisou (`agente`) fica só no /admin.
 */
@Controller
@PainelPage
class ProblemasPainelController(
    private val problemas: ProblemaService,
) {
    @GetMapping("/painel/problemas")
    fun pagina(model: Model): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        model.addAttribute(
            "avisos",
            problemas.daBarraca(barraca.id).map {
                AvisoLinha(
                    AdminQueries.format(it.criadoEm),
                    if (it.origem == "CLIENTE") "Cliente" else "Equipe",
                    it.tela,
                    it.conexao,
                    it.mensagem,
                )
            },
        )
        return "painel/problemas"
    }
}
