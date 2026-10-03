package br.com.mesamar.pagamento

import br.com.mesamar.comanda.AbertaPor
import br.com.mesamar.comanda.Comanda
import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.comanda.StatusComanda
import br.com.mesamar.common.Money
import br.com.mesamar.eventos.PublicadorEventos
import br.com.mesamar.pedido.LiberacaoPedidos
import br.com.mesamar.pedido.PedidoService
import br.com.mesamar.pedido.StatusPedido
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Uma comanda vista pela aba Pagamentos do quiosque: quanto deve, como o cliente disse que vai pagar e desde quando. */
data class ContaAPagar(
    val comandaId: UUID,
    val cliente: String,
    val guardaSol: Int?,
    val status: StatusComanda,
    val pedidoCentavos: Long,
    val pagoCentavos: Long,
    val metodo: MetodoPagamento?,
    val trocoParaCentavos: Long?,
    /** Quando o cliente avisou que vai pagar (Pix: quando disse "já paguei"). `null` se ainda não avisou. */
    val avisoEm: Instant?,
    val abertaEm: Instant,
    /** Pedidos que só entram na fila depois de pagos (modo "pagar a cada pedido"). */
    val pedidosAguardando: Int,
    /** Só em comandas encerradas sem pagar: quanto ficou sem receber. */
    val saldoPerdoadoCentavos: Long?,
) {
    val saldoCentavos: Long get() = pedidoCentavos - pagoCentavos
}

data class ContasAPagar(
    /** Quem tocou em "fechar a conta", por ordem de aviso. */
    val aguardando: List<ContaAPagar>,
    /** Comandas abertas ou bloqueadas com saldo, as maiores primeiro (proteção contra calote). */
    val abertas: List<ContaAPagar>,
    val pagina: Int = 0,
    val temMais: Boolean = false,
    val totalAguardando: Long = aguardando.size.toLong(),
    val totalSaldoAbertoCentavos: Long = (aguardando + abertas).sumOf { it.saldoCentavos },
)

/** O que o quiosque confirma: quanto entrou, em que forma e (dinheiro) quanto o cliente entregou. */
data class ConfirmacaoPagamento(
    /** Gerado no celular de quem confirma: reenviar a mesma confirmação não cobra duas vezes. */
    val id: UUID? = null,
    val metodo: MetodoPagamento? = null,
    val valorCentavos: Long? = null,
    val valorRecebidoCentavos: Long? = null,
)

sealed interface ResultadoQuiosque {
    data class Ok(
        val conta: ContaAPagar,
        /** O pagamento que acabou de ser registrado (só ao confirmar). */
        val pagamento: Pagamento? = null,
        /** `false` quando era o reenvio de uma confirmação que já existia. */
        val nova: Boolean = true,
    ) : ResultadoQuiosque

    /** A comanda não existe **nesta barraca** (o id de outra barraca é igual a um id que não existe). */
    data object NaoEncontrada : ResultadoQuiosque

    data class Invalido(
        val campo: String,
        val mensagem: String,
    ) : ResultadoQuiosque

    /** A comanda não está num estado que permita a ação, ou a regra de negócio recusou. */
    data class Recusado(
        val codigo: String,
        val mensagem: String,
    ) : ResultadoQuiosque
}

/**
 * Pagamentos do lado do quiosque (Fase 5 · checkpoint 5.2): confirmar o que o cliente pagou (total, parcial ou dividido
 * em várias formas), bloquear a comanda, encerrar a conta e o fechamento forçado com motivo.
 *
 * A barraca é sempre a de quem está logado, e toda ação trava a linha da comanda: duas confirmações ao mesmo tempo, ou
 * o cliente fechando a conta no mesmo instante, entram uma por vez. O sistema nunca recebe dinheiro: aqui só se
 * registra o que a pessoa no quiosque conferiu.
 */
@Service
class PagamentoService(
    private val autorHistorico: br.com.mesamar.common.AutorHistorico,
    private val jdbc: JdbcTemplate,
    private val comandas: ComandaRepository,
    private val pagamentos: PagamentoRepository,
    private val pedidos: PedidoService,
    private val cancelamentoFinanceiro: CancelamentoFinanceiro,
    private val liberacao: LiberacaoPedidos,
    private val tempoReal: PublicadorEventos,
    private val spring: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    fun listar(
        barracaId: UUID,
        pagina: Int = 0,
        tamanho: Int = 100,
    ): ContasAPagar {
        require(pagina >= 0 && tamanho in 1..200)
        val linhas =
            consultar(
                barracaId,
                "c.status IN ('AGUARDANDO_PAGAMENTO', 'ABERTA', 'BLOQUEADA')",
                emptyArray(),
                tamanho + 1,
                pagina * tamanho,
                apenasPendentes = true,
            )
        val todas = linhas.take(tamanho)
        val resumo = resumo(barracaId)
        return ContasAPagar(
            pagina = pagina,
            temMais = linhas.size > tamanho,
            totalAguardando = (resumo["aguardando"] as Number).toLong(),
            totalSaldoAbertoCentavos = (resumo["saldo"] as Number).toLong(),
            aguardando =
                todas
                    .filter { it.status == StatusComanda.AGUARDANDO_PAGAMENTO }
                    .sortedWith(compareBy<ContaAPagar, Instant?>(nullsLast()) { it.avisoEm }.thenBy { it.abertaEm }),
            abertas =
                todas
                    // Bloqueada aparece mesmo sem saldo: é aqui que o quiosque desbloqueia ou encerra
                    .filter {
                        it.status != StatusComanda.AGUARDANDO_PAGAMENTO &&
                            (it.saldoCentavos > 0 || it.status == StatusComanda.BLOQUEADA)
                    }.sortedByDescending { it.saldoCentavos },
        )
    }

    /** Confirma um pagamento (total ou parcial). Quando a conta que estava fechando chega a zero, ela se encerra. */
    @Transactional
    fun confirmar(
        barracaId: UUID,
        comandaId: UUID,
        req: ConfirmacaoPagamento,
        quem: AbertaPor,
    ): ResultadoQuiosque {
        autorHistorico.marcar()
        val id = req.id ?: return ResultadoQuiosque.Invalido("id", "Não deu para identificar o pagamento. Atualize o app e tente de novo.")
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 1))", id.toString())
        val comanda = travar(barracaId, comandaId) ?: return ResultadoQuiosque.NaoEncontrada

        // Reenvio da mesma confirmação (resposta perdida, toque duplo): devolve o que já foi registrado, sem cobrar de novo
        pagamentos.findById(id).orElse(null)?.let { existente ->
            return if (existente.comandaId == comanda.id) {
                ResultadoQuiosque.Ok(conta(barracaId, comanda.id), existente, nova = false)
            } else {
                ResultadoQuiosque.Recusado("conflito", "Não deu para registrar esse pagamento. Tente de novo.")
            }
        }

        recusaPorEstado(comanda)?.let { return it }
        val metodo = req.metodo ?: return ResultadoQuiosque.Invalido("metodo", "Escolha a forma de pagamento.")
        val saldo = pedidos.saldoCentavos(comanda.id)
        if (saldo <= 0) {
            return ResultadoQuiosque.Recusado("conta-zerada", "Esta conta não tem nada a pagar. Se o cliente já saiu, encerre a conta.")
        }
        val valor = req.valorCentavos
        if (valor == null || valor <= 0) return ResultadoQuiosque.Invalido("valorCentavos", "Informe quanto o cliente pagou.")
        if (valor > saldo) {
            return ResultadoQuiosque.Invalido("valorCentavos", "O valor passa do que falta pagar (${Money.format(saldo)}).")
        }
        // Só o dinheiro tem "recebido" (e portanto troco): sem informar, o cliente deu o valor exato
        val recebido =
            if (metodo == MetodoPagamento.DINHEIRO) {
                val r = req.valorRecebidoCentavos ?: valor
                if (r < valor || r > RECEBIDO_MAX_CENTAVOS) {
                    return ResultadoQuiosque.Invalido(
                        "valorRecebidoCentavos",
                        "Quanto o cliente entregou? Informe de ${Money.format(valor)} a ${Money.format(RECEBIDO_MAX_CENTAVOS)}.",
                    )
                }
                r
            } else {
                null
            }

        val agora = Instant.now(clock)
        val pagamento =
            pagamentos.save(
                Pagamento(
                    id = id,
                    barracaId = barracaId,
                    comandaId = comanda.id,
                    metodo = metodo,
                    valorCentavos = valor,
                    valorRecebidoCentavos = recebido,
                    confirmadoPorUsuarioId = quem.usuarioId,
                    confirmadoPorAtendenteId = quem.atendenteId,
                    confirmadoEm = agora,
                ),
            )
        liberacao.liberar(comanda, agora)
        // Pagou tudo o que devia e estava fechando a conta: a comanda se encerra
        if (comanda.status == StatusComanda.AGUARDANDO_PAGAMENTO && saldo - valor == 0L) encerrarComanda(comanda, agora)
        tempoReal.contaMudou(barracaId, comanda.id)
        return ResultadoQuiosque.Ok(conta(barracaId, comanda.id), pagamento)
    }

    /**
     * Encerra, de madrugada, as comandas de dias anteriores que não devem nada (pagaram tudo sem tocar em "Fechar conta",
     * ou o modo "pagar a cada pedido", em que a conta nunca é fechada pelo cliente). Sem isso elas ficariam abertas para
     * sempre: o link antigo continuaria aceitando pedido e o cliente nunca seria anonimizado. Quem ainda deve continua
     * aberto (aparece em "contas abertas com saldo").
     */
    @Transactional
    fun encerrarSemSaldoDeDiasAnteriores(hoje: java.time.LocalDate): Int {
        autorHistorico.marcar()
        val ids =
            jdbc.queryForList(
                """SELECT c.id FROM comanda c WHERE c.status IN ('ABERTA', 'AGUARDANDO_PAGAMENTO', 'BLOQUEADA') AND c.dia < ?
                    AND COALESCE((SELECT SUM(i.preco_snapshot_centavos * i.quantidade)
                        FROM item_pedido i JOIN pedido p ON p.id = i.pedido_id
                        WHERE p.comanda_id = c.id AND p.status <> 'CANCELADO' AND NOT i.cancelado), 0)
                    <= COALESCE((SELECT SUM(valor_centavos) FROM pagamento WHERE comanda_id = c.id), 0)
                    ORDER BY c.id LIMIT 100 FOR UPDATE OF c SKIP LOCKED""",
                UUID::class.java,
                hoje,
            )
        var encerradas = 0
        val agora = Instant.now(clock)
        for (id in ids) {
            val comanda = comandas.travar(id) ?: continue
            if (comanda.status !in StatusComanda.ATIVAS) continue
            if (pedidos.saldoCentavos(comanda.id) > 0) continue
            encerrarComanda(comanda, agora)
            tempoReal.contaMudou(comanda.barracaId, comanda.id)
            encerradas++
        }
        return encerradas
    }

    /** Bloqueia a comanda (calote): o cliente não pede mais até o quiosque liberar. Repetir é inofensivo. */
    @Transactional
    fun bloquear(
        barracaId: UUID,
        comandaId: UUID,
    ): ResultadoQuiosque {
        autorHistorico.marcar()
        val comanda = travar(barracaId, comandaId) ?: return ResultadoQuiosque.NaoEncontrada
        when (comanda.status) {
            StatusComanda.BLOQUEADA -> Unit
            StatusComanda.ABERTA, StatusComanda.AGUARDANDO_PAGAMENTO -> {
                limparAviso(comanda)
                comanda.status = StatusComanda.BLOQUEADA
                tempoReal.contaMudou(barracaId, comanda.id)
            }
            else -> return recusaPorEstado(comanda)!!
        }
        return ResultadoQuiosque.Ok(conta(barracaId, comanda.id))
    }

    /** Libera uma comanda bloqueada. Repetir é inofensivo. */
    @Transactional
    fun desbloquear(
        barracaId: UUID,
        comandaId: UUID,
    ): ResultadoQuiosque {
        autorHistorico.marcar()
        val comanda = travar(barracaId, comandaId) ?: return ResultadoQuiosque.NaoEncontrada
        when (comanda.status) {
            StatusComanda.ABERTA -> Unit
            StatusComanda.BLOQUEADA -> {
                comanda.status = StatusComanda.ABERTA
                tempoReal.contaMudou(barracaId, comanda.id)
            }
            else -> return ResultadoQuiosque.Recusado("comanda-nao-bloqueada", "Esta comanda não está bloqueada.")
        }
        return ResultadoQuiosque.Ok(conta(barracaId, comanda.id))
    }

    /** Encerra uma comanda que não deve nada (já paga, ou que nunca pediu). Com saldo pendente, recusa. */
    @Transactional
    fun encerrar(
        barracaId: UUID,
        comandaId: UUID,
    ): ResultadoQuiosque {
        autorHistorico.marcar()
        val comanda = travar(barracaId, comandaId) ?: return ResultadoQuiosque.NaoEncontrada
        if (comanda.status == StatusComanda.FECHADA) return ResultadoQuiosque.Ok(conta(barracaId, comanda.id))
        recusaPorEstado(comanda)?.let { return it }
        val saldo = pedidos.saldoCentavos(comanda.id)
        if (saldo > 0) {
            return ResultadoQuiosque.Recusado(
                "saldo-pendente",
                "Ainda faltam ${Money.format(saldo)}. Receba o pagamento ou, se o cliente foi embora, feche a conta com um motivo.",
            )
        }
        encerrarComanda(comanda, Instant.now(clock))
        tempoReal.contaMudou(barracaId, comanda.id)
        return ResultadoQuiosque.Ok(conta(barracaId, comanda.id))
    }

    /**
     * Fechamento forçado: o cliente foi embora sem pagar. O motivo é obrigatório e fica gravado, com o valor que ficou
     * sem receber e quem fechou (auditoria). Os pedidos que ainda esperavam pagamento (nunca foram à fila) são cancelados.
     * Só o dono chega aqui (a regra está no controller).
     */
    @Transactional
    fun fecharSemPagamento(
        barracaId: UUID,
        comandaId: UUID,
        motivo: String?,
        usuarioId: UUID,
    ): ResultadoQuiosque {
        autorHistorico.marcar()
        val texto = motivo?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (texto.length !in MOTIVO_MIN..MOTIVO_MAX) {
            return ResultadoQuiosque.Invalido("motivo", "Explique o motivo, de $MOTIVO_MIN a $MOTIVO_MAX letras.")
        }
        val comanda = travar(barracaId, comandaId) ?: return ResultadoQuiosque.NaoEncontrada
        recusaPorEstado(comanda)?.let { return it }
        val quem = AbertaPor(usuarioId, null)
        // O que nunca chegou à fila não é calote: sai da conta antes de contar o que ficou sem pagar. Mas só se a conta não
        // ficar negativa: se o cliente já pagou parte desses pedidos, cancelá-los deixaria dinheiro sem destino (o sistema
        // não registra devolução), e encerrar com eles ainda esperando pagamento deixaria pedido pendurado numa conta fechada.
        val aguardando = pedidos.meusPedidos(comanda).filter { it.pedido.status == StatusPedido.AGUARDANDO_PAGAMENTO }
        val pagoPorEles = aguardando.sumOf { it.totalCentavos } - pedidos.saldoCentavos(comanda.id)
        if (pagoPorEles > 0) {
            return ResultadoQuiosque.Recusado(
                "pago-em-parte",
                "O cliente já pagou ${Money.format(pagoPorEles)} por pedidos que ainda esperam pagamento. Receba o que falta " +
                    "(os pedidos vão para a fila) e depois feche a conta.",
            )
        }
        aguardando.forEach { cancelamentoFinanceiro.cancelar(it.pedido, "Outro: Conta encerrada sem pagamento", quem) }
        val saldo = pedidos.saldoCentavos(comanda.id)
        val agora = Instant.now(clock)
        // Nada ficou sem pagar (a conta só tinha pedidos que nunca foram à fila): encerra sem registrar calote
        if (saldo <= 0) {
            encerrarComanda(comanda, agora)
            tempoReal.contaMudou(barracaId, comanda.id)
            return ResultadoQuiosque.Ok(conta(barracaId, comanda.id))
        }
        comanda.fechamentoForcadoMotivo = texto
        comanda.saldoPerdoadoCentavos = saldo
        comanda.fechadaPorUsuarioId = usuarioId
        encerrarComanda(comanda, agora)
        tempoReal.contaMudou(barracaId, comanda.id)
        return ResultadoQuiosque.Ok(conta(barracaId, comanda.id))
    }

    private fun encerrarComanda(
        comanda: Comanda,
        agora: Instant,
    ) {
        limparAviso(comanda)
        comanda.status = StatusComanda.FECHADA
        comanda.fechadaEm = agora
    }

    private fun limparAviso(comanda: Comanda) {
        comanda.metodoPagamentoPretendido = null
        comanda.trocoParaCentavos = null
        comanda.avisoPagamentoEm = null
    }

    /** Comanda desta barraca, já com a trava da linha. O id de outra barraca dá o mesmo resultado que um id que não existe. */
    private fun travar(
        barracaId: UUID,
        comandaId: UUID,
    ): Comanda? {
        // Trava primeiro e só então confere a barraca: ler antes deixaria uma cópia velha da comanda nesta transação
        return comandas.travar(comandaId)?.takeIf { it.barracaId == barracaId }
    }

    private fun recusaPorEstado(comanda: Comanda): ResultadoQuiosque.Recusado? =
        when (comanda.status) {
            StatusComanda.FECHADA -> ResultadoQuiosque.Recusado("comanda-fechada", "Esta comanda já foi encerrada.")
            StatusComanda.CANCELADA -> ResultadoQuiosque.Recusado("comanda-cancelada", "Esta comanda foi cancelada.")
            else -> null
        }

    private fun conta(
        barracaId: UUID,
        comandaId: UUID,
    ): ContaAPagar {
        // A consulta é SQL puro: antes dela, o que mudou na comanda, nos pedidos e nos pagamentos precisa estar no banco
        comandas.flush()
        return consultar(barracaId, "c.id = ?", arrayOf(comandaId)).single()
    }

    private fun consultar(
        barracaId: UUID,
        filtro: String,
        argumentos: Array<Any>,
        limite: Int? = null,
        offset: Int = 0,
        apenasPendentes: Boolean = false,
    ): List<ContaAPagar> =
        jdbc.query(
            """
            SELECT * FROM (
            SELECT c.id, c.status, c.metodo_pagamento_pretendido, c.troco_para_centavos, c.aviso_pagamento_em, c.aberta_em,
                   c.saldo_perdoado_centavos, cl.nome AS cliente, g.numero AS guarda_sol,
                   COALESCE((SELECT SUM(i.preco_snapshot_centavos * i.quantidade)
                             FROM item_pedido i JOIN pedido p ON p.id = i.pedido_id
                             WHERE p.comanda_id = c.id AND p.status <> 'CANCELADO' AND NOT i.cancelado), 0) AS pedido,
                   COALESCE((SELECT SUM(valor_centavos) FROM pagamento WHERE comanda_id = c.id), 0) AS pago,
                   (SELECT COUNT(*) FROM pedido p WHERE p.comanda_id = c.id AND p.status = 'AGUARDANDO_PAGAMENTO') AS aguardando
            FROM comanda c
            JOIN cliente cl ON cl.id = c.cliente_id
            LEFT JOIN guarda_sol g ON g.id = c.guarda_sol_id
            WHERE c.barraca_id = ? AND $filtro
            ) resultado
            ${if (apenasPendentes) "WHERE status IN ('AGUARDANDO_PAGAMENTO', 'BLOQUEADA') OR pedido > pago" else ""}
            ORDER BY CASE WHEN status = 'AGUARDANDO_PAGAMENTO' THEN 0 ELSE 1 END,
                CASE WHEN status = 'AGUARDANDO_PAGAMENTO' THEN aviso_pagamento_em END NULLS LAST,
                pedido - pago DESC, aberta_em, id
            ${if (limite != null) "LIMIT $limite OFFSET $offset" else ""}
            """.trimIndent(),
            { rs, _ ->
                ContaAPagar(
                    comandaId = rs.getObject("id", UUID::class.java),
                    cliente = rs.getString("cliente"),
                    guardaSol = rs.getObject("guarda_sol") as Int?,
                    status = StatusComanda.valueOf(rs.getString("status")),
                    pedidoCentavos = rs.getLong("pedido"),
                    pagoCentavos = rs.getLong("pago"),
                    metodo = MetodoPagamento.fromCode(rs.getString("metodo_pagamento_pretendido")),
                    trocoParaCentavos = rs.getObject("troco_para_centavos") as Long?,
                    avisoEm = rs.getTimestamp("aviso_pagamento_em")?.toInstant(),
                    abertaEm = rs.getTimestamp("aberta_em").toInstant(),
                    pedidosAguardando = rs.getInt("aguardando"),
                    saldoPerdoadoCentavos = rs.getObject("saldo_perdoado_centavos") as Long?,
                )
            },
            barracaId,
            *argumentos,
        )

    /** Totais completos da operação; a paginação nunca altera o saldo do relatório ou o contador da aba. */
    @Transactional(readOnly = true)
    fun resumo(barracaId: UUID): Map<String, Any?> =
        jdbc.queryForMap(
            """SELECT COUNT(*) FILTER (WHERE c.status = 'AGUARDANDO_PAGAMENTO') AS aguardando,
            COALESCE(SUM(GREATEST(0, COALESCE((SELECT SUM(i.preco_snapshot_centavos * i.quantidade)
                FROM item_pedido i JOIN pedido p ON p.id = i.pedido_id
                WHERE p.comanda_id = c.id AND p.status <> 'CANCELADO' AND NOT i.cancelado), 0)
                - COALESCE((SELECT SUM(valor_centavos) FROM pagamento WHERE comanda_id = c.id), 0))), 0) AS saldo
            FROM comanda c WHERE c.barraca_id = ? AND c.status IN ('ABERTA','BLOQUEADA','AGUARDANDO_PAGAMENTO')""",
            barracaId,
        )

    companion object {
        /** Quanto o cliente pode entregar em dinheiro de uma vez: R$ 10.000,00 (só para recusar digitação errada). */
        const val RECEBIDO_MAX_CENTAVOS = 1_000_000L
        const val MOTIVO_MIN = 3
        const val MOTIVO_MAX = 120
    }
}
