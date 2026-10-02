package br.com.mesamar.pedido

import br.com.mesamar.barraca.Barraca
import br.com.mesamar.barraca.BarracaRepository
import br.com.mesamar.eventos.PublicadorEventos
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * "Pausar pedidos": no pico, ou quando o estoque está acabando, o quiosque para de receber pedidos pelo celular dos
 * clientes, com um recado opcional ("Voltamos em 10 minutos"). Vale na hora para todas as comandas da barraca, que são
 * avisadas em tempo real. Repetir a mesma ação não faz nada (e não avisa ninguém de novo).
 */
@Service
class PausaService(
    private val barracas: BarracaRepository,
    private val eventos: PublicadorEventos,
    private val clock: Clock,
) {
    @Transactional
    fun definir(
        barracaId: UUID,
        pausado: Boolean,
        mensagem: String?,
    ): Barraca {
        val barraca = barracas.findById(barracaId).orElseThrow()
        val recado = if (pausado) limpar(mensagem) else null
        if (barraca.pedidosPausados == pausado && barraca.mensagemPausa == recado) return barraca
        barraca.pedidosPausados = pausado
        barraca.mensagemPausa = recado
        barraca.atualizadaEm = Instant.now(clock)
        barracas.save(barraca)
        eventos.pausaMudou(barraca.id)
        return barraca
    }

    private fun limpar(texto: String?): String? =
        texto
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(MENSAGEM_MAX)
            ?.ifEmpty { null }

    companion object {
        const val MENSAGEM_MAX = 80
    }
}
