package br.com.mesamar.problema

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** O que o app mandou: o texto da pessoa e o contexto que ele mesmo conhece. */
data class ProblemaRequest(
    val mensagem: String? = null,
    /** Em que tela estava (ex.: `fila`, `conta`). */
    val tela: String? = null,
    /** `online` ou `offline`, e o tipo de rede se o navegador souber. */
    val conexao: String? = null,
)

sealed interface ResultadoProblema {
    data object Ok : ResultadoProblema

    data class Invalido(
        val mensagem: String,
    ) : ResultadoProblema
}

data class ProblemaRow(
    val criadoEm: Instant,
    val barraca: String,
    val origem: String,
    val tela: String,
    val conexao: String,
    val mensagem: String,
    val agente: String?,
)

/** Avisa o resto do sistema que alguém reportou um problema (o push do dono escuta isto, depois do commit). */
data class ProblemaReportado(
    val barracaId: UUID,
    /** `QUIOSQUE` ou `CLIENTE`. */
    val origem: String,
    val mensagem: String,
)

/**
 * "Reportar problema" (Fase 7 · checkpoint 7.3). Grava o texto e o contexto (tela, horário do servidor, conexão, navegador)
 * para o projeto ver no /admin. A barraca vem sempre de quem reportou (o login ou o token da comanda), nunca do pedido.
 */
@Service
class ProblemaService(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    private val eventos: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun registrar(
        barracaId: UUID,
        origem: String,
        req: ProblemaRequest,
        agente: String?,
        usuarioId: UUID? = null,
        atendenteId: UUID? = null,
        comandaId: UUID? = null,
    ): ResultadoProblema {
        val mensagem = limpar(req.mensagem, MENSAGEM_MAX)
        if (mensagem.length < MENSAGEM_MIN) return ResultadoProblema.Invalido("Conte o que aconteceu, com pelo menos $MENSAGEM_MIN letras.")
        // Tela e conexão vão para o log: numa linha só, para ninguém forjar uma linha de log falsa
        val tela = limpar(req.tela, 40, umaLinha = true).ifEmpty { "desconhecida" }
        val conexao = limpar(req.conexao, 60, umaLinha = true).ifEmpty { "desconhecida" }
        jdbc.update(
            """
            INSERT INTO problema_reportado (id, barraca_id, origem, usuario_id, atendente_id, comanda_id, tela, mensagem, conexao, agente, criado_em)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            barracaId,
            origem,
            usuarioId,
            atendenteId,
            comandaId,
            tela,
            mensagem,
            conexao,
            agente?.let { limpar(it, 200, umaLinha = true) }?.ifEmpty { null },
            Timestamp.from(Instant.now(clock)),
        )
        log.warn("Problema reportado: origem={} barraca={} tela={} conexao={}", origem, barracaId, tela, conexao)
        eventos.publishEvent(ProblemaReportado(barracaId, origem, mensagem))
        return ResultadoProblema.Ok
    }

    fun recentes(limit: Int = 100): List<ProblemaRow> =
        jdbc.query(
            """
            SELECT p.criado_em, b.nome AS barraca, p.origem, p.tela, p.conexao, p.mensagem, p.agente
            FROM problema_reportado p JOIN barraca b ON b.id = p.barraca_id
            ORDER BY p.criado_em DESC LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                ProblemaRow(
                    rs.getTimestamp("criado_em").toInstant(),
                    rs.getString("barraca"),
                    rs.getString("origem"),
                    rs.getString("tela"),
                    rs.getString("conexao"),
                    rs.getString("mensagem"),
                    rs.getString("agente"),
                )
            },
            limit,
        )

    /** Os avisos **desta barraca** (o painel do dono): a barraca vem do login, nunca da URL. */
    fun daBarraca(
        barracaId: UUID,
        limit: Int = 100,
    ): List<ProblemaRow> =
        jdbc.query(
            """
            SELECT p.criado_em, b.nome AS barraca, p.origem, p.tela, p.conexao, p.mensagem, p.agente
            FROM problema_reportado p JOIN barraca b ON b.id = p.barraca_id
            WHERE p.barraca_id = ?
            ORDER BY p.criado_em DESC LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                ProblemaRow(
                    rs.getTimestamp("criado_em").toInstant(),
                    rs.getString("barraca"),
                    rs.getString("origem"),
                    rs.getString("tela"),
                    rs.getString("conexao"),
                    rs.getString("mensagem"),
                    rs.getString("agente"),
                )
            },
            barracaId,
            limit,
        )

    /**
     * Sem caractere de controle, espaços repetidos viram um, e cabe no tamanho do banco. Só a mensagem guarda as quebras
     * de linha (`umaLinha = false`); os outros campos viram uma linha só.
     */
    private fun limpar(
        texto: String?,
        max: Int,
        umaLinha: Boolean = false,
    ): String =
        (texto ?: "")
            // Uma linha: também os separadores de linha do Unicode (U+0085, U+2028, U+2029), que alguns leitores de log quebram
            .replace(if (umaLinha) Regex("[\\p{Cc}\\p{Zl}\\p{Zp}]") else Regex("[\\p{Cntrl}&&[^\\n]]"), " ")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
            .take(max)

    companion object {
        const val MENSAGEM_MIN = 3
        const val MENSAGEM_MAX = 500
    }
}
