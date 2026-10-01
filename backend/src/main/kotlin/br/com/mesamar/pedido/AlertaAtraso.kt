package br.com.mesamar.pedido

import br.com.mesamar.eventos.PublicadorEventos
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Um pedido passou do tempo de espera que a barraca configurou. Quem escuta (o push) age depois do commit. */
data class PedidoAtrasado(
    val barracaId: UUID,
    val pedidoId: UUID,
    val numero: Int,
    val guardaSol: Int?,
    val minutos: Int,
)

/**
 * Procura os pedidos parados além do tempo de alerta da barraca (`tempo_alerta_pedido_min`) e avisa **uma vez só**
 * por pedido: a hora do aviso fica gravada em `alerta_atraso_em`.
 *
 * Com mais de um servidor rodando, `FOR UPDATE SKIP LOCKED` faz cada pedido ser pego por um servidor só, então ninguém
 * recebe o aviso em dobro, e sem precisar de biblioteca de trava de tarefa agendada.
 */
@Service
class AlertaAtrasoService(
    private val jdbc: JdbcTemplate,
    private val eventos: PublicadorEventos,
    private val spring: ApplicationEventPublisher,
) {
    /** Devolve quantos pedidos foram avisados agora. */
    @Transactional
    fun verificar(): Int {
        val atrasados =
            jdbc.query(
                """
                SELECT p.id, p.barraca_id, p.numero_pedido, g.numero AS guarda_sol,
                       floor(extract(epoch FROM (now() - COALESCE(p.na_fila_em, p.criado_em))) / 60)::int AS minutos
                FROM pedido p
                JOIN barraca b ON b.id = p.barraca_id
                JOIN comanda c ON c.id = p.comanda_id
                LEFT JOIN guarda_sol g ON g.id = c.guarda_sol_id
                WHERE p.status IN ('RECEBIDO', 'EM_PREPARO')
                  AND p.alerta_atraso_em IS NULL
                  AND b.ativa
                  AND COALESCE(p.na_fila_em, p.criado_em) <= now() - make_interval(mins => b.tempo_alerta_pedido_min)
                ORDER BY COALESCE(p.na_fila_em, p.criado_em)
                LIMIT 100
                FOR UPDATE OF p SKIP LOCKED
                """.trimIndent(),
                { rs, _ ->
                    PedidoAtrasado(
                        barracaId = rs.getObject("barraca_id", UUID::class.java),
                        pedidoId = rs.getObject("id", UUID::class.java),
                        numero = rs.getInt("numero_pedido"),
                        guardaSol = rs.getObject("guarda_sol") as Int?,
                        minutos = rs.getInt("minutos"),
                    )
                },
            )
        atrasados.forEach {
            jdbc.update("UPDATE pedido SET alerta_atraso_em = now() WHERE id = ?", it.pedidoId)
            eventos.pedidoAtrasado(it.barracaId, it.pedidoId, it.numero)
            spring.publishEvent(it)
        }
        return atrasados.size
    }
}

/** Roda a verificação de tempos em tempos. */
@Component
class AgendadorAlertaAtraso(
    private val alertas: AlertaAtrasoService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${mesamar.alerta-atraso.intervalo:PT30S}", initialDelayString = "PT30S")
    fun rodar() {
        runCatching { alertas.verificar() }.onFailure { log.warn("Falha ao verificar pedidos atrasados: {}", it.message) }
    }
}
