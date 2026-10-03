package br.com.mesamar.relatorio

import br.com.mesamar.comanda.StatusComanda
import br.com.mesamar.pagamento.MetodoPagamento
import br.com.mesamar.pagamento.PagamentoService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/** Quanto entrou numa forma de pagamento no dia. */
data class FormaRecebida(
    val metodo: MetodoPagamento,
    val centavos: Long,
    val pagamentos: Int,
)

data class ItemVendido(
    val nome: String,
    val quantidade: Int,
    val centavos: Long,
)

/** Itens cancelados por um mesmo motivo ("Esgotou", "Cliente desistiu", "Outro"): quantas unidades e quanto valiam. */
data class CancelamentoPorMotivo(
    val motivo: String,
    val unidades: Int,
    val centavos: Long,
)

/** Uma comanda que ainda deve (hoje, não só no dia do relatório). */
data class ContaEmAberto(
    val cliente: String,
    val guardaSol: Int?,
    val status: StatusComanda,
    val saldoCentavos: Long,
)

/** Conta encerrada pelo dono sem receber o que faltava. */
data class NaoRecebido(
    val cliente: String,
    val guardaSol: Int?,
    val motivo: String,
    val centavos: Long,
)

data class RelatorioDia(
    val dia: LocalDate,
    /** Soma dos pagamentos confirmados no dia (o que entrou), em qualquer forma. */
    val recebidoCentavos: Long,
    val porForma: List<FormaRecebida>,
    /** Comandas abertas no dia. */
    val clientes: Int,
    val pedidosDoCliente: Int,
    val pedidosDoAtendente: Int,
    val pedidosCancelados: Int,
    /** Valor dos itens não cancelados dos pedidos do dia (o que foi vendido, tenha sido pago ou não). */
    val vendidoCentavos: Long,
    val entregues: Int,
    /** Média entre o pedido entrar na fila e ser entregue, em segundos. `null` se nada foi entregue no dia. */
    val tempoMedioEntregaSegundos: Long?,
    val maisVendidos: List<ItemVendido>,
    val cancelamentos: List<CancelamentoPorMotivo>,
    val emAberto: List<ContaEmAberto>,
    val naoRecebidos: List<NaoRecebido>,
    val saldoAbertoTotalCentavos: Long? = null,
    val naoRecebidoTotalCentavos: Long? = null,
) {
    val pedidos: Int get() = pedidosDoCliente + pedidosDoAtendente
    val emAbertoCentavos: Long get() = saldoAbertoTotalCentavos ?: emAberto.sumOf { it.saldoCentavos }
    val naoRecebidoCentavos: Long get() = naoRecebidoTotalCentavos ?: naoRecebidos.sumOf { it.centavos }
    val cancelamentosCentavos: Long get() = cancelamentos.sumOf { it.centavos }
}

/**
 * O relatório do dia de uma barraca (Fase 5 · checkpoint 5.3). Tudo é calculado em SQL direto das tabelas de pedido e
 * pagamento, para bater sempre com o que o quiosque viu: não há total guardado em lugar nenhum. O "dia" é o dia de
 * operação no fuso da barraca (America/Sao_Paulo). A barraca é sempre a de quem está logado.
 */
@Service
class RelatorioService(
    private val jdbc: JdbcTemplate,
    private val pagamentos: PagamentoService,
) {
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    fun dia(
        barracaId: UUID,
        dia: LocalDate,
    ): RelatorioDia {
        val inicio = java.sql.Timestamp.from(dia.atStartOfDay(br.com.mesamar.common.Fuso.ZONA).toInstant())
        val fim = java.sql.Timestamp.from(dia.plusDays(1).atStartOfDay(br.com.mesamar.common.Fuso.ZONA).toInstant())
        val formas =
            jdbc.query(
                """
                SELECT metodo, SUM(valor_centavos) AS centavos, COUNT(*) AS qtd
                FROM pagamento
                WHERE barraca_id = ? AND confirmado_em >= ? AND confirmado_em < ?
                GROUP BY metodo
                """.trimIndent(),
                { rs, _ -> FormaRecebida(MetodoPagamento.valueOf(rs.getString("metodo")), rs.getLong("centavos"), rs.getInt("qtd")) },
                barracaId,
                inicio,
                fim,
            )
        val porForma = MetodoPagamento.entries.map { m -> formas.firstOrNull { it.metodo == m } ?: FormaRecebida(m, 0, 0) }

        val clientes =
            jdbc.queryForObject("SELECT COUNT(*) FROM comanda WHERE barraca_id = ? AND dia = ?", Int::class.java, barracaId, dia) ?: 0

        // Pedidos do dia: do cliente e do atendente (cancelados à parte) e o valor vendido
        val pedidos =
            jdbc.queryForMap(
                """
                SELECT COUNT(*) FILTER (WHERE status <> 'CANCELADO' AND origem = 'CLIENTE') AS cliente,
                       COUNT(*) FILTER (WHERE status <> 'CANCELADO' AND origem = 'ATENDENTE') AS atendente,
                       COUNT(*) FILTER (WHERE status = 'CANCELADO') AS cancelados,
                       COUNT(*) FILTER (WHERE status = 'ENTREGUE') AS entregues,
                       AVG(EXTRACT(EPOCH FROM (entregue_em - COALESCE(na_fila_em, criado_em)))) FILTER (WHERE status = 'ENTREGUE') AS espera
                FROM pedido
                WHERE barraca_id = ? AND dia = ?
                """.trimIndent(),
                barracaId,
                dia,
            )
        val vendido =
            jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(i.preco_snapshot_centavos * i.quantidade), 0)
                FROM item_pedido i JOIN pedido p ON p.id = i.pedido_id
                WHERE p.barraca_id = ? AND p.dia = ? AND p.status <> 'CANCELADO' AND NOT i.cancelado
                """.trimIndent(),
                Long::class.java,
                barracaId,
                dia,
            ) ?: 0

        val maisVendidos =
            jdbc.query(
                """
                SELECT i.nome_snapshot AS nome, SUM(i.quantidade) AS qtd, SUM(i.preco_snapshot_centavos * i.quantidade) AS centavos
                FROM item_pedido i JOIN pedido p ON p.id = i.pedido_id
                WHERE p.barraca_id = ? AND p.dia = ? AND p.status <> 'CANCELADO' AND NOT i.cancelado
                GROUP BY i.nome_snapshot
                ORDER BY qtd DESC, centavos DESC, nome
                LIMIT $TOP_ITENS
                """.trimIndent(),
                { rs, _ -> ItemVendido(rs.getString("nome"), rs.getInt("qtd"), rs.getLong("centavos")) },
                barracaId,
                dia,
            )

        // O motivo de um pedido ou item cancelado é "Esgotou", "Cliente desistiu" ou "Outro: detalhe": agrupa pelo começo
        val cancelamentos =
            jdbc.query(
                """
                SELECT TRIM(split_part(COALESCE(i.motivo_cancelamento, 'Sem motivo'), ':', 1)) AS motivo,
                       SUM(i.quantidade) AS unidades, SUM(i.preco_snapshot_centavos * i.quantidade) AS centavos
                FROM item_pedido i JOIN pedido p ON p.id = i.pedido_id
                WHERE p.barraca_id = ? AND p.dia = ? AND i.cancelado
                GROUP BY 1
                ORDER BY centavos DESC, motivo
                """.trimIndent(),
                { rs, _ -> CancelamentoPorMotivo(rs.getString("motivo"), rs.getInt("unidades"), rs.getLong("centavos")) },
                barracaId,
                dia,
            )

        val naoRecebidos =
            jdbc.query(
                """
                SELECT cl.nome AS cliente, g.numero AS guarda_sol, c.fechamento_forcado_motivo AS motivo, c.saldo_perdoado_centavos AS centavos
                FROM comanda c
                JOIN cliente cl ON cl.id = c.cliente_id
                LEFT JOIN guarda_sol g ON g.id = c.guarda_sol_id
                WHERE c.barraca_id = ? AND c.saldo_perdoado_centavos IS NOT NULL
                  AND c.fechada_em >= ? AND c.fechada_em < ?
                ORDER BY c.fechada_em, c.id LIMIT 100
                """.trimIndent(),
                { rs, _ ->
                    NaoRecebido(rs.getString("cliente"), rs.getObject("guarda_sol") as Int?, rs.getString("motivo"), rs.getLong("centavos"))
                },
                barracaId,
                inicio,
                fim,
            )

        // Quem deve agora (não importa o dia escolhido): a mesma lista da aba Pagamentos do app
        val contas = pagamentos.listar(barracaId)
        val emAberto =
            (contas.aguardando + contas.abertas)
                .filter { it.saldoCentavos > 0 }
                .sortedByDescending { it.saldoCentavos }
                .map { ContaEmAberto(it.cliente, it.guardaSol, it.status, it.saldoCentavos) }

        return RelatorioDia(
            saldoAbertoTotalCentavos = contas.totalSaldoAbertoCentavos,
            naoRecebidoTotalCentavos =
                jdbc.queryForObject(
                    "SELECT COALESCE(SUM(saldo_perdoado_centavos), 0) FROM comanda WHERE barraca_id = ? AND fechada_em >= ? AND fechada_em < ?",
                    Long::class.java,
                    barracaId,
                    inicio,
                    fim,
                ),
            dia = dia,
            recebidoCentavos = porForma.sumOf { it.centavos },
            porForma = porForma,
            clientes = clientes,
            pedidosDoCliente = (pedidos["cliente"] as Number).toInt(),
            pedidosDoAtendente = (pedidos["atendente"] as Number).toInt(),
            pedidosCancelados = (pedidos["cancelados"] as Number).toInt(),
            vendidoCentavos = vendido,
            entregues = (pedidos["entregues"] as Number).toInt(),
            tempoMedioEntregaSegundos = (pedidos["espera"] as Number?)?.toDouble()?.let { Math.round(it) },
            maisVendidos = maisVendidos,
            cancelamentos = cancelamentos,
            emAberto = emAberto,
            naoRecebidos = naoRecebidos,
        )
    }

    companion object {
        const val TOP_ITENS = 10
    }
}
