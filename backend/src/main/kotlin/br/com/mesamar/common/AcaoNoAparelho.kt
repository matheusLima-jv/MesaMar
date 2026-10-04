package br.com.mesamar.common

import java.time.Clock
import java.time.Instant

/** A fila preserva o instante original. Reenvio já confirmado é resolvido antes desta regra. */
object AcaoNoAparelho {
    fun noDiaAtual(
        criadaEm: Instant?,
        clock: Clock,
    ): Boolean =
        criadaEm == null ||
            (criadaEm.atZone(Fuso.ZONA).toLocalDate() == Fuso.hoje(clock) && !criadaEm.isAfter(Instant.now(clock).plusSeconds(300)))

    const val RECADO =
        "Esta ação foi criada em outro dia ou com horário incorreto. " +
            "Confira a conta com a equipe antes de descartar a pendência; nada novo foi gravado."
}
