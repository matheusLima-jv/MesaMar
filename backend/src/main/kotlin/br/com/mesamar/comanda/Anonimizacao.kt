package br.com.mesamar.comanda

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * LGPD: depois de [dias] dias, o nome do cliente vira "Cliente" e o WhatsApp é apagado. Os pedidos, os pagamentos e os
 * valores ficam como estão (a barraca precisa deles para as contas e os relatórios), só que sem identificar a pessoa.
 *
 * É um único `UPDATE`: com mais de um servidor rodando, o primeiro anonimiza e os outros não encontram mais nada, então
 * ninguém precisa de trava de tarefa agendada. A regra é seca: passou o prazo, anonimiza, **mesmo que a comanda ainda
 * esteja aberta ou com conta a pagar** (decisão do dono do projeto no 5.3; quem deve é cobrado antes disso).
 */
@Service
class AnonimizacaoService(
    private val jdbc: JdbcTemplate,
    @Value("\${mesamar.anonimizacao.dias:90}") private val dias: Int,
) {
    /** Devolve quantos clientes foram anonimizados agora. Os avisos de "reportar problema" com mais de [dias] dias são apagados. */
    @Transactional
    fun anonimizar(): Int {
        jdbc.update("DELETE FROM problema_reportado WHERE criado_em < now() - make_interval(days => ?)", dias)
        return jdbc.update(
            """
            UPDATE cliente
            SET nome = 'Cliente', whatsapp_e164 = NULL, anonimizado_em = now()
            WHERE anonimizado_em IS NULL
              AND criado_em < now() - make_interval(days => ?)
            """.trimIndent(),
            dias,
        )
    }
}

/** Roda a anonimização todo dia, de madrugada (fuso das barracas). */
@Component
class AgendadorAnonimizacao(
    private val anonimizacao: AnonimizacaoService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${mesamar.anonimizacao.cron:0 30 3 * * *}", zone = "America/Sao_Paulo")
    fun rodar() {
        runCatching { anonimizacao.anonimizar() }
            .onSuccess { if (it > 0) log.info("LGPD: {} clientes anonimizados", it) }
            .onFailure { log.warn("Falha ao anonimizar clientes antigos: {}", it.message) }
    }
}
