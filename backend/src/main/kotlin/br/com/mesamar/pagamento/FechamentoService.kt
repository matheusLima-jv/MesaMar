package br.com.mesamar.pagamento

import br.com.mesamar.barraca.Barraca
import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.comanda.Comanda
import br.com.mesamar.comanda.ComandaRepository
import br.com.mesamar.comanda.StatusComanda
import br.com.mesamar.common.BrCodePix
import br.com.mesamar.common.Money
import br.com.mesamar.pedido.ItemPedidoRepository
import br.com.mesamar.pedido.PedidoDetalhe
import br.com.mesamar.pedido.PedidoService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** O Pix que o cliente paga no próprio celular: o código "copia e cola" (que também vira o QR) e quem recebe. */
data class PixParaPagar(
    val codigo: String,
    val chave: String,
    val nomeRecebedor: String,
    val cidade: String,
    val valorCentavos: Long,
)

/** A conta da comanda: o que foi pedido, o que já foi pago e o que falta. */
data class Extrato(
    val comanda: Comanda,
    val pedidos: List<PedidoDetalhe>,
    val pagamentos: List<Pagamento>,
    val pedidoCentavos: Long,
    val pagoCentavos: Long,
    val saldoCentavos: Long,
    /** Só quando a comanda aguarda um Pix e ainda há saldo. */
    val pix: PixParaPagar?,
)

sealed interface ResultadoConta {
    data class Ok(
        val extrato: Extrato,
    ) : ResultadoConta

    /** Dado do formulário errado (ex.: troco menor que a conta). */
    data class Invalido(
        val campo: String,
        val mensagem: String,
    ) : ResultadoConta

    /** A comanda ou a barraca não estão num estado que permita a ação. */
    data class Recusado(
        val codigo: String,
        val mensagem: String,
    ) : ResultadoConta
}

/**
 * O fechamento da conta pelo cliente (Fase 5 · checkpoint 5.1). A comanda anda `ABERTA → AGUARDANDO_PAGAMENTO` e pode
 * voltar a `ABERTA` enquanto nenhum pagamento foi confirmado; o `FECHADA` vem com a confirmação do quiosque (5.2).
 * Toda ação trava a linha da comanda, então um toque duplo ou o dono confirmando ao mesmo tempo nunca se atropelam.
 */
@Service
class FechamentoService(
    private val comandas: ComandaRepository,
    private val barracas: BarracaRepository,
    private val pedidos: PedidoService,
    private val pagamentos: PagamentoRepository,
    private val itens: ItemPedidoRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun extrato(comanda: Comanda): Extrato = montar(comanda, barracas.findById(comanda.barracaId).orElseThrow())

    @Transactional
    fun fechar(
        comandaLida: Comanda,
        metodo: MetodoPagamento?,
        trocoParaCentavos: Long?,
    ): ResultadoConta {
        val comanda = comandas.travar(comandaLida.id) ?: return ResultadoConta.Recusado("comanda", "Esse link não vale mais.")
        val barraca = barracas.findById(comanda.barracaId).orElseThrow()
        recusaPorEstado(barraca, comanda, aceitaAguardando = true)?.let { return it }
        if (metodo == null) return ResultadoConta.Invalido("metodo", "Escolha como vai pagar: Pix, dinheiro ou cartão.")

        val saldo = pedidos.saldoCentavos(comanda.id)
        if (saldo <= 0) return ResultadoConta.Recusado("conta-zerada", "Sua conta está zerada: não há nada para pagar.")
        if (metodo == MetodoPagamento.PIX && !barraca.temPix) {
            return ResultadoConta.Recusado("pix-indisponivel", "Este quiosque não recebe Pix pelo MesaMar. Escolha dinheiro ou cartão.")
        }
        val troco = if (metodo == MetodoPagamento.DINHEIRO) trocoParaCentavos else null
        if (troco != null && (troco < saldo || troco > TROCO_MAX_CENTAVOS)) {
            return ResultadoConta.Invalido(
                "trocoParaCentavos",
                "Para quanto é o troco? Informe um valor de ${Money.format(saldo)} a ${Money.format(TROCO_MAX_CENTAVOS)}.",
            )
        }

        comanda.status = StatusComanda.AGUARDANDO_PAGAMENTO
        comanda.metodoPagamentoPretendido = metodo
        comanda.trocoParaCentavos = troco
        // Dinheiro e cartão: o aviso é este toque. Pix: o aviso é o "já paguei", que vem depois.
        comanda.avisoPagamentoEm = if (metodo == MetodoPagamento.PIX) null else Instant.now(clock)
        return ResultadoConta.Ok(montar(comanda, barraca))
    }

    /** O cliente pagou o Pix no banco e avisa o quiosque. Repetir o aviso não muda a hora do primeiro. */
    @Transactional
    fun jaPaguei(comandaLida: Comanda): ResultadoConta {
        val comanda = comandas.travar(comandaLida.id) ?: return ResultadoConta.Recusado("comanda", "Esse link não vale mais.")
        val barraca = barracas.findById(comanda.barracaId).orElseThrow()
        if (comanda.status != StatusComanda.AGUARDANDO_PAGAMENTO || comanda.metodoPagamentoPretendido != MetodoPagamento.PIX) {
            return ResultadoConta.Recusado("sem-pix", "Não há um Pix aguardando. Feche a conta e escolha Pix primeiro.")
        }
        if (comanda.avisoPagamentoEm == null) comanda.avisoPagamentoEm = Instant.now(clock)
        return ResultadoConta.Ok(montar(comanda, barraca))
    }

    /** "Voltar e pedir mais": só enquanto nenhum pagamento foi confirmado. Já aberta, não faz nada. */
    @Transactional
    fun reabrir(comandaLida: Comanda): ResultadoConta {
        val comanda = comandas.travar(comandaLida.id) ?: return ResultadoConta.Recusado("comanda", "Esse link não vale mais.")
        val barraca = barracas.findById(comanda.barracaId).orElseThrow()
        when (comanda.status) {
            StatusComanda.ABERTA -> Unit
            StatusComanda.AGUARDANDO_PAGAMENTO -> {
                if (pagamentos.totalCentavos(comanda.id) > 0) {
                    return ResultadoConta.Recusado(
                        "pagamento-confirmado",
                        "O quiosque já confirmou um pagamento nesta conta. Fale com um atendente para pedir mais.",
                    )
                }
                comanda.status = StatusComanda.ABERTA
                comanda.metodoPagamentoPretendido = null
                comanda.trocoParaCentavos = null
                comanda.avisoPagamentoEm = null
            }
            else -> return recusaPorEstado(barraca, comanda, aceitaAguardando = false)!!
        }
        return ResultadoConta.Ok(montar(comanda, barraca))
    }

    /** `null` se a comanda pode seguir; senão, o motivo (a barraca desligada, comanda bloqueada, encerrada…). */
    private fun recusaPorEstado(
        barraca: Barraca,
        comanda: Comanda,
        aceitaAguardando: Boolean,
    ): ResultadoConta.Recusado? =
        when {
            !barraca.ativa ->
                ResultadoConta.Recusado(
                    "barraca-inativa",
                    "Esta barraca não está atendendo pelo MesaMar agora. Fale com um atendente.",
                )
            comanda.status == StatusComanda.ABERTA -> null
            comanda.status == StatusComanda.AGUARDANDO_PAGAMENTO && aceitaAguardando -> null
            comanda.status == StatusComanda.BLOQUEADA ->
                ResultadoConta.Recusado("comanda-bloqueada", "Sua comanda está bloqueada. Fale com um atendente.")
            comanda.status == StatusComanda.FECHADA ->
                ResultadoConta.Recusado("comanda-fechada", "Esta comanda foi encerrada. Obrigado pela visita!")
            comanda.status == StatusComanda.CANCELADA ->
                ResultadoConta.Recusado("comanda-cancelada", "Esta comanda foi cancelada. Fale com um atendente.")
            else -> ResultadoConta.Recusado("aguardando-pagamento", "Sua conta já está sendo fechada.")
        }

    private fun montar(
        comanda: Comanda,
        barraca: Barraca,
    ): Extrato {
        val listaPedidos = pedidos.meusPedidos(comanda)
        val pagos = pagamentos.findAllByComandaIdOrderByConfirmadoEmAsc(comanda.id)
        val pedido = itens.totalCentavos(comanda.id)
        val pago = pagos.sumOf { it.valorCentavos }
        val saldo = pedido - pago
        val pix =
            if (comanda.status == StatusComanda.AGUARDANDO_PAGAMENTO &&
                comanda.metodoPagamentoPretendido == MetodoPagamento.PIX &&
                saldo > 0
            ) {
                pixDa(barraca, comanda, saldo)
            } else {
                null
            }
        return Extrato(comanda, listaPedidos, pagos, pedido, pago, saldo, pix)
    }

    private fun pixDa(
        barraca: Barraca,
        comanda: Comanda,
        saldoCentavos: Long,
    ): PixParaPagar? {
        val chave = barraca.chavePix ?: return null
        val nome = barraca.nomeRecebedorPix ?: return null
        val cidade = barraca.cidadePix ?: return null
        return PixParaPagar(
            codigo = BrCodePix.gerar(chave, nome, cidade, saldoCentavos, txidDa(comanda.id)),
            chave = chave,
            nomeRecebedor = nome,
            cidade = cidade,
            valorCentavos = saldoCentavos,
        )
    }

    companion object {
        /** R$ 10.000,00: um teto só para recusar digitação errada no "vou pagar com". */
        const val TROCO_MAX_CENTAVOS = 1_000_000L

        /** O txid do Pix vem da comanda (letras e números, até 25): conciliar o extrato do banco com a conta fica possível. */
        fun txidDa(comandaId: UUID): String = comandaId.toString().replace("-", "").take(BrCodePix.TXID_MAX)
    }
}
