package br.com.mesamar.relatorio

import br.com.mesamar.admin.Csv
import br.com.mesamar.barraca.PainelPage
import br.com.mesamar.barraca.barraca
import br.com.mesamar.comanda.StatusComanda
import br.com.mesamar.common.Fuso
import br.com.mesamar.common.Money
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
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Uma linha de tabela do relatório já formatada para a tela. */
data class LinhaRelatorio(
    val rotulo: String,
    val detalhe: String,
    val valor: String,
)

/**
 * O relatório do dia (Fase 5 · checkpoint 5.3): página no painel do dono e o mesmo conteúdo em CSV. A barraca é sempre a
 * de quem está logado, e a data vem de `?dia=AAAA-MM-DD` (hoje, se faltar ou for inválida; nunca no futuro).
 */
@Controller
@PainelPage
class RelatorioController(
    private val service: RelatorioService,
    private val clock: Clock,
) {
    @GetMapping("/painel/relatorio")
    fun pagina(
        @RequestParam(required = false) dia: String?,
        model: Model,
    ): String {
        val barraca = model.barraca() ?: return "redirect:/painel/barraca"
        val hoje = Fuso.hoje(clock)
        val data = escolher(dia, hoje)
        val r = service.dia(barraca.id, data)
        model.addAttribute("diaTexto", BR.format(data))
        model.addAttribute("diaIso", data.toString())
        model.addAttribute("hojeIso", hoje.toString())
        model.addAttribute("anteriorIso", data.minusDays(1).toString())
        model.addAttribute("proximoIso", if (data < hoje) data.plusDays(1).toString() else null)
        model.addAttribute("ehHoje", data == hoje)
        model.addAttribute("dataInvalida", dia != null && dia.isNotBlank() && parse(dia) == null)
        model.addAttribute("r", r)
        model.addAttribute("resumo", resumo(r))
        model.addAttribute(
            "formas",
            r.porForma.map {
                LinhaRelatorio(it.metodo.rotulo, "${it.pagamentos} pagamento(s)", Money.format(it.centavos))
            },
        )
        model.addAttribute(
            "maisVendidos",
            r.maisVendidos.map { LinhaRelatorio(it.nome, "${it.quantidade} un.", Money.format(it.centavos)) },
        )
        model.addAttribute(
            "cancelamentos",
            r.cancelamentos.map { LinhaRelatorio(it.motivo, "${it.unidades} un.", Money.format(it.centavos)) },
        )
        model.addAttribute(
            "emAberto",
            r.emAberto.map {
                LinhaRelatorio(local(it.cliente, it.guardaSol), situacao(it.status), Money.format(it.saldoCentavos))
            },
        )
        model.addAttribute(
            "naoRecebidos",
            r.naoRecebidos.map {
                LinhaRelatorio(local(it.cliente, it.guardaSol), it.motivo, Money.format(it.centavos))
            },
        )
        model.addAttribute("totalRecebido", Money.format(r.recebidoCentavos))
        model.addAttribute("totalEmAberto", Money.format(r.emAbertoCentavos))
        model.addAttribute("ativo", "relatorio")
        return "painel/relatorio"
    }

    @GetMapping("/painel/relatorio.csv")
    fun csv(
        @RequestParam(required = false) dia: String?,
        model: Model,
    ): ResponseEntity<ByteArray> {
        val barraca = model.barraca() ?: return ResponseEntity.notFound().build()
        val data = escolher(dia, Fuso.hoje(clock))
        val r = service.dia(barraca.id, data)
        val csv = Csv.write(listOf("Seção", "Item", "Quantidade", "Valor (R$)"), linhasCsv(r))
        val headers =
            HttpHeaders().apply {
                contentType = MediaType("text", "csv", StandardCharsets.UTF_8)
                contentDisposition = ContentDisposition.attachment().filename("mesamar-relatorio-$data.csv").build()
            }
        return ResponseEntity.ok().headers(headers).body(csv.toByteArray(StandardCharsets.UTF_8))
    }

    private fun escolher(
        texto: String?,
        hoje: LocalDate,
    ): LocalDate {
        val data = parse(texto) ?: return hoje
        return if (data > hoje) hoje else data
    }

    private fun parse(texto: String?): LocalDate? =
        try {
            if (texto.isNullOrBlank()) null else LocalDate.parse(texto.trim())
        } catch (e: DateTimeParseException) {
            null
        }

    private fun resumo(r: RelatorioDia): List<Pair<String, String>> =
        listOf(
            "Recebido no dia" to Money.format(r.recebidoCentavos),
            "Vendido no dia" to Money.format(r.vendidoCentavos),
            "Clientes (comandas abertas)" to r.clientes.toString(),
            "Pedidos feitos pelo cliente" to r.pedidosDoCliente.toString(),
            "Pedidos lançados pelo atendente" to r.pedidosDoAtendente.toString(),
            "Pedidos cancelados" to r.pedidosCancelados.toString(),
            "Pedidos entregues" to r.entregues.toString(),
            "Tempo médio do pedido à entrega" to (r.tempoMedioEntregaSegundos?.let(::duracao) ?: "sem entregas"),
        )

    private fun linhasCsv(r: RelatorioDia): List<List<String>> {
        val linhas = mutableListOf<List<String>>()

        fun linha(
            secao: String,
            item: String,
            quantidade: String,
            centavos: Long?,
        ) = linhas.add(listOf(secao, item, quantidade, centavos?.let(Money::toInput).orEmpty()))
        linha("Resumo", "Recebido no dia", "", r.recebidoCentavos)
        linha("Resumo", "Vendido no dia", "", r.vendidoCentavos)
        linha("Resumo", "Total em aberto agora (todas as contas)", "", r.emAbertoCentavos)
        linha("Resumo", "Total não recebido (todas as contas)", "", r.naoRecebidoCentavos)
        linha("Observação", "Listas detalhadas limitadas a 100 contas por seção; totais completos", "100", null)
        linha("Resumo", "Clientes (comandas abertas)", r.clientes.toString(), null)
        linha("Resumo", "Pedidos feitos pelo cliente", r.pedidosDoCliente.toString(), null)
        linha("Resumo", "Pedidos lançados pelo atendente", r.pedidosDoAtendente.toString(), null)
        linha("Resumo", "Pedidos cancelados", r.pedidosCancelados.toString(), null)
        linha("Resumo", "Pedidos entregues", r.entregues.toString(), null)
        linha(
            "Resumo",
            "Tempo médio do pedido à entrega (min)",
            r.tempoMedioEntregaSegundos?.let { "%.1f".format(it / 60.0).replace('.', ',') } ?: "",
            null,
        )
        r.porForma.forEach { linha("Recebido por forma", it.metodo.rotulo, it.pagamentos.toString(), it.centavos) }
        r.maisVendidos.forEach { linha("Mais vendidos", it.nome, it.quantidade.toString(), it.centavos) }
        r.cancelamentos.forEach { linha("Cancelamentos", it.motivo, it.unidades.toString(), it.centavos) }
        r.emAberto.forEach { linha("Em aberto agora", "${local(it.cliente, it.guardaSol)} (${situacao(it.status)})", "", it.saldoCentavos) }
        r.naoRecebidos.forEach { linha("Não recebido", "${local(it.cliente, it.guardaSol)}: ${it.motivo}", "", it.centavos) }
        return linhas
    }

    private fun local(
        cliente: String,
        guardaSol: Int?,
    ) = "${if (guardaSol == null) "Balcão" else "Guarda-sol $guardaSol"} · $cliente"

    private fun situacao(status: StatusComanda) =
        when (status) {
            StatusComanda.AGUARDANDO_PAGAMENTO -> "fechando a conta"
            StatusComanda.BLOQUEADA -> "bloqueada"
            else -> "aberta"
        }

    private fun duracao(segundos: Long): String {
        val min = segundos / 60
        val resto = segundos % 60
        return if (min == 0L) "$resto s" else "$min min ${"%02d".format(resto)} s"
    }

    companion object {
        private val BR: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}
