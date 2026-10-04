package br.com.mesamar.common

import java.time.Clock
import java.time.Duration
import java.time.Instant

/** A fila preserva o instante original. Reenvio já confirmado é resolvido antes desta regra. */
object AcaoNoAparelho {
    /**
     * Com [enviadaEm], vale a idade medida no próprio aparelho (envio menos criação), aplicada ao relógio do servidor:
     * um celular adiantado ou com a data errada não recusa as próprias ações. Sem ela (app antigo), vale o instante
     * informado, com até cinco minutos de adiantamento.
     */
    fun noDiaAtual(
        criadaEm: Instant?,
        enviadaEm: Instant?,
        clock: Clock,
    ): Boolean {
        if (criadaEm == null) return true
        val agora = Instant.now(clock)
        val estimada =
            if (enviadaEm == null) {
                criadaEm
            } else {
                // Relógio acertado entre criar e enviar pode deixar a idade negativa: a ação é recente
                agora.minus(Duration.between(criadaEm, enviadaEm).coerceAtLeast(Duration.ZERO))
            }
        return estimada.atZone(Fuso.ZONA).toLocalDate() == Fuso.hoje(clock) && !estimada.isAfter(agora.plusSeconds(300))
    }

    const val RECADO =
        "Esta ação foi criada em outro dia ou com horário incorreto. " +
            "Confira a conta com a equipe antes de descartar a pendência; nada novo foi gravado."
}
