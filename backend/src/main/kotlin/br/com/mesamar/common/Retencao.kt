package br.com.mesamar.common

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class Retencao(
    private val jdbc: JdbcTemplate,
    @Value("\${mesamar.retencao.historico-dias:90}") private val historicoDias: Int,
) {
    /** Limites por tabela: cada execução conclui em uma transação curta. */
    @Scheduled(fixedDelayString = "\${mesamar.retencao.intervalo:PT5M}")
    @Transactional
    fun limpar(): Int {
        require(historicoDias in 1..365)
        var removidos = 0
        listOf("reset_senha", "convite", "entrega_pendente").forEach { tabela ->
            removidos +=
                jdbc.update(
                    "DELETE FROM $tabela WHERE id IN (SELECT id FROM $tabela WHERE expira_em <= now() ORDER BY expira_em LIMIT 500)",
                )
        }
        removidos +=
            jdbc.update(
                "DELETE FROM historico_acao WHERE id IN (SELECT id FROM historico_acao WHERE criado_em < now() - make_interval(days => ?) ORDER BY criado_em LIMIT 500)",
                historicoDias,
            )
        return removidos
    }
}
