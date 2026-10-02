package br.com.mesamar.pagamento

import br.com.mesamar.common.Fuso
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/** Todo dia às 4h (Brasília), antes da anonimização do dia seguinte e longe do horário de praia. */
@Component
class EncerramentoDiario(
    private val pagamentos: PagamentoService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${mesamar.encerramento.cron:0 0 4 * * *}", zone = "America/Sao_Paulo")
    fun rodar() {
        runCatching { pagamentos.encerrarSemSaldoDeDiasAnteriores(Fuso.hoje(clock)) }
            .onSuccess { if (it > 0) log.info("{} comandas de dias anteriores, sem saldo, foram encerradas", it) }
            .onFailure { log.warn("Falha ao encerrar comandas antigas: {}", it.message) }
    }
}
