package br.com.mesamar.admin

import br.com.mesamar.common.BrazilianPhone
import br.com.mesamar.common.Praia
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class LeadRow(
    val id: UUID,
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
    val carregamentoMs: Int?,
    val pingMs: Int?,
    val tipoConexao: String?,
    val criadaEm: Instant,
) {
    val criadaEmLocal: String get() = AdminQueries.format(criadaEm)
}

enum class StatusConvite(
    val label: String,
) {
    ABERTO("Aguardando cadastro"),
    USADO("Conta criada"),
    EXPIRADO("Expirado"),
}

data class ConviteRow(
    val leadNome: String?,
    val leadBarraca: String?,
    val observacao: String?,
    val emailConta: String?,
    val criadoEm: Instant,
    val expiraEm: Instant,
    val usadoEm: Instant?,
    val status: StatusConvite,
) {
    val paraQuem: String get() = listOfNotNull(leadNome?.let { "$it · $leadBarraca" }, observacao).joinToString(" — ").ifEmpty { "Avulso" }
    val criadoEmLocal: String get() = AdminQueries.format(criadoEm)
    val expiraEmLocal: String get() = AdminQueries.format(expiraEm)
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

    fun leads(limit: Int = Int.MAX_VALUE): List<LeadRow> = jdbc.query("$LEAD_SELECT ORDER BY criado_em DESC LIMIT ?", leadMapper, limit)

    fun lead(id: UUID): LeadRow? = jdbc.query("$LEAD_SELECT WHERE id = ?", leadMapper, id).firstOrNull()

    private val leadMapper =
        RowMapper<LeadRow> { rs, _ ->
            LeadRow(
                id = rs.getObject("id", UUID::class.java),
                nome = rs.getString("nome"),
                nomeBarraca = rs.getString("nome_barraca"),
                praia = praiaLabel(rs.getString("praia")),
                whatsappE164 = rs.getString("whatsapp"),
                criadoEm = rs.getTimestamp("criado_em").toInstant(),
            )
        }

    fun medicoes(limit: Int = Int.MAX_VALUE): List<MedicaoRow> =
        jdbc.query(
            "SELECT praia, carregamento_ms, ping_ms, tipo_conexao, criada_em FROM medicao_sinal ORDER BY criada_em DESC LIMIT ?",
            RowMapper { rs, _ ->
                MedicaoRow(
                    praia = praiaLabel(rs.getString("praia")),
                    carregamentoMs = rs.getObject("carregamento_ms", Int::class.javaObjectType),
                    pingMs = rs.getObject("ping_ms", Int::class.javaObjectType),
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

    fun convites(
        now: Instant,
        limit: Int = 100,
    ): List<ConviteRow> =
        jdbc.query(
            """
            SELECT c.observacao, c.criado_em, c.expira_em, c.usado_em,
                   l.nome AS lead_nome, l.nome_barraca AS lead_barraca, u.email AS email_conta
            FROM convite c
            LEFT JOIN lead l ON l.id = c.lead_id
            LEFT JOIN usuario u ON u.id = c.usado_por
            ORDER BY c.criado_em DESC
            LIMIT ?
            """.trimIndent(),
            RowMapper<ConviteRow> { rs, _ ->
                val expiraEm = rs.getTimestamp("expira_em").toInstant()
                val usadoEm = rs.getTimestamp("usado_em")?.toInstant()
                ConviteRow(
                    leadNome = rs.getString("lead_nome"),
                    leadBarraca = rs.getString("lead_barraca"),
                    observacao = rs.getString("observacao"),
                    emailConta = rs.getString("email_conta"),
                    criadoEm = rs.getTimestamp("criado_em").toInstant(),
                    expiraEm = expiraEm,
                    usadoEm = usadoEm,
                    status =
                        when {
                            usadoEm != null -> StatusConvite.USADO
                            !now.isBefore(expiraEm) -> StatusConvite.EXPIRADO
                            else -> StatusConvite.ABERTO
                        },
                )
            },
            limit,
        )

    private fun praiaLabel(code: String): String = Praia.fromCode(code)?.label ?: code

    companion object {
        private const val LEAD_SELECT = "SELECT id, nome, nome_barraca, praia, whatsapp, criado_em FROM lead"
        val ZONE: ZoneId = ZoneId.of("America/Sao_Paulo")
        private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZONE)

        fun format(instant: Instant): String = FORMAT.format(instant)
    }
}
