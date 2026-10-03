package br.com.mesamar.common

import br.com.mesamar.push.MensagemPush
import br.com.mesamar.push.PushService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

data class EmailPendente(
    val to: String,
    val subject: String,
    val body: String,
)

data class Entrega(
    val id: UUID,
    val tipo: String,
    val destino: UUID?,
    val conteudo: String,
    val reserva: UUID,
    val expiraEm: Instant,
)

@Service
class Entregas(
    private val jdbc: JdbcTemplate,
    private val json: ObjectMapper,
) {
    @Transactional(propagation = Propagation.REQUIRED)
    fun registrar(
        tipo: String,
        destino: UUID?,
        conteudo: Any,
        expira: Instant,
    ) {
        jdbc.update(
            "INSERT INTO entrega_pendente (id, tipo, destino, conteudo, expira_em) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(),
            tipo,
            destino,
            json.writeValueAsString(conteudo),
            java.sql.Timestamp.from(expira),
        )
    }

    /** A reserva é curta e recuperável após crash. Nenhum socket fica dentro da transação. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun reservar(): List<Entrega> {
        val reserva = UUID.randomUUID()
        return jdbc.query(
            """
            UPDATE entrega_pendente SET reserva = ?, reservada_ate = now() + interval '10 minutes', tentativas = tentativas + 1
            WHERE id IN (SELECT id FROM entrega_pendente
                WHERE proxima_tentativa_em <= now() AND expira_em > now()
                  AND (reservada_ate IS NULL OR reservada_ate < now())
                ORDER BY criada_em, id LIMIT 20 FOR UPDATE SKIP LOCKED)
            RETURNING id, tipo, destino, conteudo, expira_em
            """.trimIndent(),
            { rs, _ ->
                Entrega(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("tipo"),
                    rs.getObject("destino", UUID::class.java),
                    rs.getString("conteudo"),
                    reserva,
                    rs.getTimestamp("expira_em").toInstant(),
                )
            },
            reserva,
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun concluir(e: Entrega) {
        jdbc.update("DELETE FROM entrega_pendente WHERE id = ? AND reserva = ?", e.id, e.reserva)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun repetir(e: Entrega) {
        jdbc.update(
            """UPDATE entrega_pendente SET reserva = NULL, reservada_ate = NULL,
                proxima_tentativa_em = now() + make_interval(secs => LEAST(300, 5 * tentativas))
                WHERE id = ? AND reserva = ?""",
            e.id,
            e.reserva,
        )
    }
}

/** Um lote por instância; a reserva no banco permite várias instâncias sem duplicar o trabalho normal. */
@Component
class Entregador(
    private val entregas: Entregas,
    private val email: EmailGateway,
    private val push: PushService,
    private val json: ObjectMapper,
    private val saude: SaudeOperacional? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${mesamar.entregas.intervalo:PT1S}")
    fun rodar() {
        val lote = entregas.reservar()
        saude?.registrar("notificacoes-varredura", true)
        lote.forEach { e ->
            try {
                val segundos =
                    java.time.Duration
                        .between(Instant.now(), e.expiraEm)
                        .seconds
                if (segundos <= 0) {
                    entregas.concluir(e)
                    return@forEach
                }
                when (e.tipo) {
                    "EMAIL" -> json.readValue(e.conteudo, EmailPendente::class.java).let { email.send(it.to, it.subject, it.body) }
                    "PUSH" ->
                        push.entregarPendente(
                            requireNotNull(e.destino),
                            json
                                .readValue(
                                    e.conteudo,
                                    MensagemPush::class.java,
                                ).copy(validadeSegundos = segundos.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
                        )
                    else -> error("Tipo de entrega desconhecido")
                }
                entregas.concluir(e)
                saude?.registrar("notificacoes-envio", true)
            } catch (erro: Exception) {
                // Nunca registra destinatário, link de recuperação ou corpo.
                log.warn("Entrega {} ({}) será repetida: {}", e.id, e.tipo, erro.javaClass.simpleName)
                saude?.registrar("notificacoes-envio", false)
                entregas.repetir(e)
            }
        }
    }
}
