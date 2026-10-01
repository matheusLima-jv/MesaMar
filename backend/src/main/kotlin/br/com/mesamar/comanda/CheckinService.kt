package br.com.mesamar.comanda

import br.com.mesamar.common.BrazilianPhone
import br.com.mesamar.common.Fuso
import br.com.mesamar.common.SecureTokens
import br.com.mesamar.guardasol.GuardaSol
import br.com.mesamar.guardasol.GuardaSolRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class CheckinRequest(
    /** Gerado no celular: repetir o mesmo check-in (sinal ruim, toque duplo) devolve a mesma comanda. */
    val id: UUID? = null,
    val nome: String = "",
    val guardaSolId: UUID? = null,
    val whatsapp: String = "",
    val consentimento: Boolean = false,
)

/** Quem está no quiosque fazendo o check-in: o dono ou um atendente (exatamente um dos dois). */
data class AbertaPor(
    val usuarioId: UUID?,
    val atendenteId: UUID?,
)

/** A comanda com o que a tela precisa mostrar dela. */
data class ComandaDetalhe(
    val comanda: Comanda,
    val cliente: Cliente,
    val guardaSol: GuardaSol?,
)

sealed interface ResultadoCheckin {
    data class Ok(
        val detalhe: ComandaDetalhe,
        val nova: Boolean,
    ) : ResultadoCheckin

    data class Invalido(
        val campos: Map<String, String>,
    ) : ResultadoCheckin

    /** O id enviado já é de uma comanda de outra barraca (na prática, nunca acontece com UUID aleatório). */
    data object Conflito : ResultadoCheckin
}

@Service
class CheckinService(
    private val clientes: ClienteRepository,
    private val comandas: ComandaRepository,
    private val guardaSois: GuardaSolRepository,
    private val contador: ContadorDiario,
    private val clock: Clock,
) {
    @Transactional
    fun checkin(
        barracaId: UUID,
        quem: AbertaPor,
        req: CheckinRequest,
    ): ResultadoCheckin {
        require((quem.usuarioId == null) != (quem.atendenteId == null)) { "check-in sem autor ou com dois autores" }
        req.id?.let { id ->
            comandas.findById(id).orElse(null)?.let { existente ->
                return if (existente.barracaId ==
                    barracaId
                ) {
                    ResultadoCheckin.Ok(detalhe(existente), nova = false)
                } else {
                    ResultadoCheckin.Conflito
                }
            }
        }

        val campos = linkedMapOf<String, String>()
        val nome = req.nome.trim().replace(Regex("\\s+"), " ")
        when {
            nome.isEmpty() -> campos["nome"] = "Digite o nome do cliente."
            nome.length > NOME_MAX -> campos["nome"] = "Use até $NOME_MAX letras."
        }
        val whatsapp = BrazilianPhone.normalize(req.whatsapp)
        if (req.whatsapp.isNotBlank() && whatsapp == null) campos["whatsapp"] = "WhatsApp inválido. Exemplo: (13) 99999-8888."
        val guardaSol = req.guardaSolId?.let { guardaSois.findByIdAndBarracaId(it, barracaId) }
        if (req.guardaSolId != null &&
            guardaSol?.ativo != true
        ) {
            campos["guardaSolId"] = "Esse guarda-sol não está disponível. Escolha outro."
        }
        if (!req.consentimento) campos["consentimento"] = "Confirme que o cliente concordou."
        if (campos.isNotEmpty()) return ResultadoCheckin.Invalido(campos)

        val agora = Instant.now(clock)
        val dia = Fuso.hoje(clock)
        val cliente =
            clientes.save(
                Cliente(barracaId = barracaId, nome = nome, whatsappE164 = whatsapp, consentimentoEm = agora, criadoEm = agora),
            )
        val comanda =
            comandas.save(
                Comanda(
                    id = req.id ?: UUID.randomUUID(),
                    barracaId = barracaId,
                    clienteId = cliente.id,
                    guardaSolId = guardaSol?.id,
                    dia = dia,
                    numeroChegada = contador.proximo(barracaId, dia, TipoContador.CHEGADA),
                    tokenAcesso = SecureTokens.generate(TOKEN_BYTES),
                    abertaPorUsuarioId = quem.usuarioId,
                    abertaPorAtendenteId = quem.atendenteId,
                    abertaEm = agora,
                ),
            )
        return ResultadoCheckin.Ok(ComandaDetalhe(comanda, cliente, guardaSol), nova = true)
    }

    /** Clientes com comanda ativa, por ordem de chegada. */
    @Transactional(readOnly = true)
    fun ativos(barracaId: UUID): List<ComandaDetalhe> {
        val lista = comandas.findAtivas(barracaId, StatusComanda.ATIVAS)
        val porCliente = clientes.findAllById(lista.map { it.clienteId }).associateBy { it.id }
        val porGuardaSol = guardaSois.findAllById(lista.mapNotNull { it.guardaSolId }).associateBy { it.id }
        return lista.map { ComandaDetalhe(it, porCliente.getValue(it.clienteId), it.guardaSolId?.let(porGuardaSol::get)) }
    }

    private fun detalhe(c: Comanda) =
        ComandaDetalhe(
            c,
            clientes.findById(c.clienteId).orElseThrow(),
            c.guardaSolId?.let { guardaSois.findById(it).orElse(null) },
        )

    companion object {
        const val NOME_MAX = 40

        /** 128 bits: impossível de adivinhar e curto o bastante para um QR fácil de ler no sol. */
        const val TOKEN_BYTES = 16
    }
}
