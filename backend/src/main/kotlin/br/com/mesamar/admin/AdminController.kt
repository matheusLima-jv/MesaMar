package br.com.mesamar.admin

import br.com.mesamar.barraca.DemoService
import br.com.mesamar.config.SiteProperties
import br.com.mesamar.conta.ConviteService
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.io.Serializable
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Mostrado uma única vez, logo depois de gerar o convite. */
data class ConviteGerado(
    val link: String,
    val paraQuem: String,
    val whatsappLink: String?,
    val expiraEm: String,
) : Serializable

/** Painel interno: lista de espera, convites, medições de sinal e exportações em CSV. */
@Controller
class AdminController(
    private val queries: AdminQueries,
    private val convites: ConviteService,
    private val demo: DemoService,
    private val site: SiteProperties,
    private val clock: Clock,
) {
    @GetMapping("/admin/login")
    fun login(
        @RequestParam(required = false) erro: String?,
        @RequestParam(required = false) saiu: String?,
        model: Model,
    ): String {
        model.addAttribute("erro", erro != null)
        model.addAttribute("saiu", saiu != null)
        return "admin/login"
    }

    @GetMapping("/admin")
    fun dashboard(model: Model): String {
        model.addAttribute("leadCount", queries.leadCount())
        model.addAttribute("leads", queries.leads(limit = 200))
        model.addAttribute("resumoSinal", queries.resumoSinal())
        model.addAttribute("medicoes", queries.medicoes(limit = 50))
        model.addAttribute("convites", queries.convites(Instant.now(clock)))
        model.addAttribute("demoExiste", demo.existe())
        return "admin/index"
    }

    /**
     * Gera um convite de cadastro (para um lead da lista ou avulso). O link só aparece uma vez,
     * nesta resposta: no banco fica apenas o hash do token.
     */
    @PostMapping("/admin/convites")
    fun gerarConvite(
        @RequestParam(required = false) leadId: UUID?,
        @RequestParam(required = false) observacao: String?,
        redirect: RedirectAttributes,
    ): String {
        val lead = leadId?.let { queries.lead(it) }
        if (leadId != null && lead == null) {
            redirect.addFlashAttribute("erroConvite", "Esse interessado não existe mais na lista.")
            return "redirect:/admin#convites"
        }
        val novo = convites.criar(lead?.id, observacao)
        val link = site.absoluteUrl("/painel/cadastro?convite=${novo.token}")
        redirect.addFlashAttribute(
            "conviteGerado",
            ConviteGerado(
                link = link,
                paraQuem = lead?.let { "${it.nome} · ${it.nomeBarraca}" } ?: observacao?.trim()?.ifBlank { null } ?: "Convite avulso",
                whatsappLink = lead?.let { whatsappLink(it, link) },
                expiraEm = AdminQueries.format(novo.convite.expiraEm),
            ),
        )
        return "redirect:/admin#convites"
    }

    /**
     * Cria a barraca de demonstração (treino) ou a reinicia: apaga o que o treino gerou e recria os dados de exemplo, com
     * senha e PIN novos. As credenciais aparecem uma vez, nesta resposta.
     */
    @PostMapping("/admin/demo")
    fun criarDemo(redirect: RedirectAttributes): String {
        redirect.addFlashAttribute("demoCredenciais", demo.criarOuReiniciar())
        return "redirect:/admin#demo"
    }

    @GetMapping("/admin/leads.csv")
    fun leadsCsv(): ResponseEntity<ByteArray> {
        val rows =
            queries.leads().map {
                listOf(it.criadoEmLocal, it.nome, it.nomeBarraca, it.praia, it.whatsapp)
            }
        val csv = Csv.write(listOf("Data", "Nome", "Barraca", "Praia", "WhatsApp"), rows)
        return csvResponse(csv, "mesamar-leads")
    }

    @GetMapping("/admin/medicoes.csv")
    fun medicoesCsv(): ResponseEntity<ByteArray> {
        val rows =
            queries.medicoes().map {
                listOf(it.criadaEmLocal, it.praia, it.carregamentoMs.toString(), it.pingMs.toString(), it.tipoConexao.orEmpty())
            }
        val csv = Csv.write(listOf("Data", "Praia", "Carregamento (ms)", "Resposta do servidor (ms)", "Conexão"), rows)
        return csvResponse(csv, "mesamar-medicoes-sinal")
    }

    private fun whatsappLink(
        lead: LeadRow,
        link: String,
    ): String {
        val texto =
            "Oi, ${lead.nome.substringBefore(' ')}! Aqui é do MesaMar. Seu acesso ao piloto está liberado. " +
                "Crie a sua conta por este link (vale por 14 dias): $link"
        return lead.whatsappLink + "?text=" + URLEncoder.encode(texto, Charsets.UTF_8).replace("+", "%20")
    }

    private fun csvResponse(
        csv: String,
        baseName: String,
    ): ResponseEntity<ByteArray> {
        val filename = "$baseName-${LocalDate.now(clock.withZone(AdminQueries.ZONE))}.csv"
        val headers =
            HttpHeaders().apply {
                contentType = MediaType("text", "csv", StandardCharsets.UTF_8)
                contentDisposition = ContentDisposition.attachment().filename(filename).build()
            }
        return ResponseEntity.ok().headers(headers).body(csv.toByteArray(StandardCharsets.UTF_8))
    }
}
