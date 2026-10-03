package br.com.mesamar.pagamento

import br.com.mesamar.common.Fuso
import br.com.mesamar.common.SaudeOperacional
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/** Todo dia às 4h (Brasília), antes da anonimização do dia seguinte e longe do horário de praia. */
@Component
class EncerramentoDiario(
    private val pagamentos: PagamentoService,
    private val clock: Clock,
    private val saude: SaudeOperacional? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${mesamar.encerramento.cron:0 0 4 * * *}", zone = "America/Sao_Paulo")
    fun rodar() {
        runCatching {
            var total = 0
            do {
                val lote = pagamentos.encerrarSemSaldoDeDiasAnteriores(Fuso.hoje(clock))
                total += lote
            } while (lote == 100 && total < 10_000)
            total
        }.onSuccess {
            saude?.registrar("encerramento-diario", true)
            if (it >
                0
            ) {
                log.info("{} comandas de dias anteriores, sem saldo, foram encerradas", it)
            }
        }.onFailure {
            saude?.registrar("encerramento-diario", false)
            log.warn("Falha ao encerrar comandas antigas: {}", it.message)
        }
    }
}
