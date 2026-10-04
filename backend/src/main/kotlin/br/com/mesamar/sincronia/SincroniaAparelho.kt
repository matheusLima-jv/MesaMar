package br.com.mesamar.sincronia

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Quem informa: o dono (usuário) ou um atendente. Exatamente um dos dois. */
data class AutorAparelho(
    val usuarioId: UUID?,
    val atendenteId: UUID?,
)

/** O que o aparelho conta de si a cada rodada de sincronia: quantas ações ainda estão nele. */
data class EstadoAparelho(
    val aparelhoId: UUID,
    val pendentes: Int,
    val conflitos: Int,
    val pendenteDesde: Instant? = null,
)

/** Uma linha da lista que o dono vê. */
data class AparelhoView(
    val quem: String,
    val tipo: String,
    val sincronizadoEm: Instant,
    val pendentes: Int,
    val conflitos: Int,
    /** Tem ação recusada, ou ação parada no aparelho há mais que [SincroniaAparelho.ATRASO]: o dono precisa ir atrás desse celular. */
    val atencao: Boolean,
    val pendenteDesde: Instant?,
)

enum class TipoAparelho(
    val rotulo: String,
) {
    ANDROID("Android"),
    IPHONE("iPhone/iPad"),
    COMPUTADOR("Computador"),
    OUTRO("Outro aparelho"),
    ;

    companion object {
        /** Só a família do aparelho, para o dono reconhecer qual é; nada que identifique a pessoa. */
        fun de(userAgent: String?): TipoAparelho {
            val ua = userAgent.orEmpty()
            return when {
                ua.contains("Android", ignoreCase = true) -> ANDROID
                ua.contains("iPhone", ignoreCase = true) || ua.contains("iPad", ignoreCase = true) -> IPHONE
                listOf("Windows", "Macintosh", "Linux", "CrOS").any { ua.contains(it, ignoreCase = true) } -> COMPUTADOR
                else -> OUTRO
            }
        }
    }
}

/**
 * Última sincronização de cada aparelho da equipe (Fase 6.3, ADR 0021). O app informa depois de cada rodada de envio
 * da fila offline; um aparelho sem sinal simplesmente para de informar, e o dono vê a hora parada e as pendências.
 * Tudo fica no escopo da barraca da sessão: o id do aparelho vem do próprio aparelho e não dá acesso a outra barraca.
 */
@Service
class SincroniaAparelho(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    @Transactional
    fun registrar(
        barracaId: UUID,
        autor: AutorAparelho,
        estado: EstadoAparelho,
        tipo: TipoAparelho,
    ) {
        require((autor.usuarioId == null) != (autor.atendenteId == null))
        require(estado.pendentes in 0..MAX_ACOES && estado.conflitos in 0..MAX_ACOES)
        val agora = Instant.now(clock)
        val informado = estado.pendenteDesde?.coerceAtMost(agora)
        val desde = if (estado.pendentes > 0) informado ?: agora else null
        jdbc.update(
            """INSERT INTO sincronizacao_aparelho
                (barraca_id, aparelho_id, usuario_id, atendente_id, tipo_aparelho, pendentes, conflitos, sincronizado_em, pendente_desde)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (barraca_id, aparelho_id, autor_id) DO UPDATE SET
                    tipo_aparelho = EXCLUDED.tipo_aparelho, pendentes = EXCLUDED.pendentes,
                    conflitos = EXCLUDED.conflitos, sincronizado_em = EXCLUDED.sincronizado_em,
                    pendente_desde = CASE WHEN EXCLUDED.pendentes = 0 THEN NULL
                        ELSE coalesce(?, sincronizacao_aparelho.pendente_desde, EXCLUDED.pendente_desde) END""",
            barracaId,
            estado.aparelhoId,
            autor.usuarioId,
            autor.atendenteId,
            tipo.name,
            estado.pendentes,
            estado.conflitos,
            Timestamp.from(agora),
            desde?.let(Timestamp::from),
            informado?.let(Timestamp::from),
        )
    }

    /** Aparelhos da barraca: os que precisam de atenção primeiro, depois os que sincronizaram há mais tempo. */
    @Transactional(readOnly = true)
    fun listar(barracaId: UUID): List<AparelhoView> {
        val agora = Instant.now(clock)
        return jdbc
            .query(
                """SELECT coalesce(a.nome, u.nome) AS quem, s.tipo_aparelho, s.sincronizado_em, s.pendentes, s.conflitos, s.pendente_desde
                    FROM sincronizacao_aparelho s
                    LEFT JOIN atendente a ON a.id = s.atendente_id AND a.barraca_id = s.barraca_id
                    LEFT JOIN usuario u ON u.id = s.usuario_id AND u.barraca_id = s.barraca_id
                    WHERE s.barraca_id = ?
                    ORDER BY (s.conflitos > 0 OR (s.pendente_desde IS NOT NULL AND s.pendente_desde < ?)) DESC, s.sincronizado_em
                    LIMIT ?""",
                { rs, _ ->
                    val em = rs.getTimestamp("sincronizado_em").toInstant()
                    val pendentes = rs.getInt("pendentes")
                    val conflitos = rs.getInt("conflitos")
                    val desde = rs.getTimestamp("pendente_desde")?.toInstant()
                    AparelhoView(
                        quem = rs.getString("quem") ?: "Equipe",
                        tipo = TipoAparelho.valueOf(rs.getString("tipo_aparelho")).rotulo,
                        sincronizadoEm = em,
                        pendentes = pendentes,
                        conflitos = conflitos,
                        atencao = conflitos > 0 || (desde != null && Duration.between(desde, agora) > ATRASO),
                        pendenteDesde = desde,
                    )
                },
                barracaId,
                Timestamp.from(agora.minus(ATRASO)),
                MAX_LISTA,
            ).sortedWith(compareByDescending<AparelhoView> { it.atencao }.thenBy { it.sincronizadoEm })
    }

    companion object {
        /** O mesmo teto da fila offline do aparelho, por barraca/autor. */
        const val MAX_ACOES = 200
        const val MAX_LISTA = 100

        /** Ação parada no aparelho por mais que isso merece destaque para o dono. */
        val ATRASO: Duration = Duration.ofMinutes(15)

        /** Aparelho que não sincroniza há mais que isso sai da lista (retenção). */
        const val RETENCAO_DIAS = 30
    }
}
