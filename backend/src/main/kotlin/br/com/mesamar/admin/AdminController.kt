package br.com.mesamar.admin

import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDate

/** Painel interno: lista de espera, medições de sinal e exportações em CSV. */
@Controller
class AdminController(
    private val queries: AdminQueries,
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
        return "admin/index"
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
