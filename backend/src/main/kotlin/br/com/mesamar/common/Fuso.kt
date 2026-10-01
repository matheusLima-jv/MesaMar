package br.com.mesamar.common

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** Fuso das barracas (todas no litoral de SP). Define o "dia de operação" dos números sequenciais. */
object Fuso {
    val ZONA: ZoneId = ZoneId.of("America/Sao_Paulo")

    fun hoje(clock: Clock): LocalDate = LocalDate.now(clock.withZone(ZONA))
}
