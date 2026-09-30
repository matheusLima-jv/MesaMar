package br.com.mesamar.admin

import br.com.mesamar.common.BrazilianPhone
import br.com.mesamar.common.Praia
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class LeadRow(
    val nome: String,
    val nomeBarraca: String,
    val praia: String,
    val whatsappE164: String,
    val criadoEm: Instant,
) {
    val whatsapp: String get() = BrazilianPhone.display(whatsappE164)
    val whatsappLink: String get() = "https://wa.me/" + whatsappE164.removePrefix("+")
    val criadoEmLocal: String get() = AdminQueries.format(criadoEm)
}

data class MedicaoRow(
    val praia: String,
    val carregamentoMs: Int,
    val pingMs: Int,
    val tipoConexao: String?,
    val criadaEm: Instant,
) {
    val criadaEmLocal: String get() = AdminQueries.format(criadaEm)
}

/** Resumo do sinal por praia: é o dado que decide o Portão A (meta: abrir em até 5 s). */
data class ResumoSinal(
    val praia: String,
    val medicoes: Int,
    val medianaCarregamentoMs: Int,
    val medianaPingMs: Int,
    val percentualAte5s: Int,
) {
    val atingeMeta: Boolean get() = percentualAte5s >= 50
}

@Component
class AdminQueries(
    private val jdbc: JdbcTemplate,
) {
    fun leadCount(): Int = jdbc.queryForObject("SELECT count(*) FROM lead", Int::class.java) ?: 0

    fun leads(limit: Int = Int.MAX_VALUE): List<LeadRow> =
        jdbc.query(
            "SELECT nome, nome_barraca, praia, whatsapp, criado_em FROM lead ORDER BY criado_em DESC LIMIT ?",
            RowMapper { rs, _ ->
                LeadRow(
                    nome = rs.getString("nome"),
                    nomeBarraca = rs.getString("nome_barraca"),
                    praia = praiaLabel(rs.getString("praia")),
                    whatsappE164 = rs.getString("whatsapp"),
                    criadoEm = rs.getTimestamp("criado_em").toInstant(),
                )
            },
            limit,
        )

    fun medicoes(limit: Int = Int.MAX_VALUE): List<MedicaoRow> =
        jdbc.query(
            "SELECT praia, carregamento_ms, ping_ms, tipo_conexao, criada_em FROM medicao_sinal ORDER BY criada_em DESC LIMIT ?",
            RowMapper { rs, _ ->
                MedicaoRow(
                    praia = praiaLabel(rs.getString("praia")),
                    carregamentoMs = rs.getInt("carregamento_ms"),
                    pingMs = rs.getInt("ping_ms"),
                    tipoConexao = rs.getString("tipo_conexao"),
                    criadaEm = rs.getTimestamp("criada_em").toInstant(),
                )
            },
            limit,
        )

    fun resumoSinal(): List<ResumoSinal> =
        jdbc.query(
            """
            SELECT praia,
                   count(*) AS medicoes,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY carregamento_ms) AS mediana_carregamento,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY ping_ms) AS mediana_ping,
                   round(100.0 * avg(CASE WHEN carregamento_ms <= 5000 THEN 1 ELSE 0 END)) AS pct_ate_5s
            FROM medicao_sinal
            GROUP BY praia
            ORDER BY praia
            """.trimIndent(),
            RowMapper { rs, _ ->
                ResumoSinal(
                    praia = praiaLabel(rs.getString("praia")),
                    medicoes = rs.getInt("medicoes"),
                    medianaCarregamentoMs = rs.getDouble("mediana_carregamento").toInt(),
                    medianaPingMs = rs.getDouble("mediana_ping").toInt(),
                    percentualAte5s = rs.getInt("pct_ate_5s"),
                )
            },
        )

    private fun praiaLabel(code: String): String = Praia.fromCode(code)?.label ?: code

    companion object {
        val ZONE: ZoneId = ZoneId.of("America/Sao_Paulo")
        private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZONE)

        fun format(instant: Instant): String = FORMAT.format(instant)
    }
}
