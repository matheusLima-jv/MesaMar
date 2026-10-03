package br.com.mesamar.comanda

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * LGPD: depois de [dias] dias, o nome do cliente vira "Cliente" e o WhatsApp é apagado. O **texto livre** que a equipe ou
 * o cliente digitou (observação do item, detalhe do motivo de cancelamento, motivo do fechamento sem receber) também é
 * limpo, porque pode citar pessoas ("sem gelo, é para o filho do Marcos"): fica só a categoria do motivo ("Esgotou",
 * "Cliente desistiu", "Outro"), que é o que o relatório usa. Os pedidos, os pagamentos e os
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
    /**
     * Limpa o texto livre das comandas dos clientes já anonimizados e dos que vão ser agora. Idempotente: roda todo dia
     * e só encontra o que ainda tem texto (inclusive o que ficou de antes desta regra existir).
     */
    private fun limparTextoLivre() {
        val dosClientes =
            """
            SELECT c.id FROM comanda c JOIN cliente cl ON cl.id = c.cliente_id
            WHERE cl.anonimizado_em IS NOT NULL OR cl.criado_em < now() - make_interval(days => ?)
            """.trimIndent()
        val doPedido = "SELECT p.id FROM pedido p WHERE p.comanda_id IN ($dosClientes)"
        jdbc.update("UPDATE item_pedido SET observacao = NULL WHERE observacao IS NOT NULL AND pedido_id IN ($doPedido)", dias)
        // "Outro: cliente foi embora" → "Outro". O relatório agrupa pelo que vem antes dos dois-pontos
        jdbc.update(
            "UPDATE item_pedido SET motivo_cancelamento = trim(split_part(motivo_cancelamento, ':', 1)) " +
                "WHERE motivo_cancelamento LIKE '%:%' AND pedido_id IN ($doPedido)",
            dias,
        )
        jdbc.update(
            "UPDATE pedido SET motivo_cancelamento = trim(split_part(motivo_cancelamento, ':', 1)) " +
                "WHERE motivo_cancelamento LIKE '%:%' AND comanda_id IN ($dosClientes)",
            dias,
        )
        jdbc.update(
            "UPDATE comanda SET fechamento_forcado_motivo = 'Removido (LGPD)' " +
                "WHERE fechamento_forcado_motivo IS NOT NULL AND fechamento_forcado_motivo <> 'Removido (LGPD)' " +
                "AND id IN ($dosClientes)",
            dias,
        )
    }

    /** Devolve quantos clientes foram anonimizados agora. Os avisos de "reportar problema" com mais de [dias] dias são apagados. */
    @Transactional
    fun anonimizar(): Int {
        jdbc.update("DELETE FROM problema_reportado WHERE criado_em < now() - make_interval(days => ?)", dias)
        limparTextoLivre()
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
