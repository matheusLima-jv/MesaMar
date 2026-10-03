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
    /**
     * Check-in feito sem sinal: o aparelho gera o token (128 bits, `crypto.getRandomValues`) para já mostrar o QR
     * e o servidor o adota quando o check-in chega. Sem este campo, o servidor gera o token.
     */
    val token: String? = null,
    /** A versão do texto de consentimento que o app mostrou (ex.: "2026-10-v1"). Ver docs/consentimento.md. */
    val consentimentoVersao: String? = null,
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
    private val autorHistorico: br.com.mesamar.common.AutorHistorico,
    private val jdbc: org.springframework.jdbc.core.JdbcTemplate,
    private val clientes: ClienteRepository,
    private val comandas: ComandaRepository,
    private val guardaSois: GuardaSolRepository,
    private val contador: ContadorDiario,
    private val hub: br.com.mesamar.eventos.HubEventos,
    private val clock: Clock,
) {
    @Transactional
    fun checkin(
        barracaId: UUID,
        quem: AbertaPor,
        req: CheckinRequest,
    ): ResultadoCheckin {
        autorHistorico.marcar()
        require((quem.usuarioId == null) != (quem.atendenteId == null)) { "check-in sem autor ou com dois autores" }
        req.id?.let { id ->
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", id.toString())
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
        if (req.token != null && !tokenDoAparelhoValido(req.token)) campos["token"] = "Código do QR inválido. Tente o check-in de novo."
        if (campos.isNotEmpty()) return ResultadoCheckin.Invalido(campos)
        // Token do aparelho que já é de outra comanda (UUID diferente): não adota, o aparelho tenta de novo com outro
        if (req.token != null && comandas.findByTokenAcesso(req.token) != null) return ResultadoCheckin.Conflito

        val agora = Instant.now(clock)
        val dia = Fuso.hoje(clock)
        val cliente =
            clientes.save(
                Cliente(
                    barracaId = barracaId,
                    nome = nome,
                    whatsappE164 = whatsapp,
                    consentimentoEm = agora,
                    consentimentoVersao = versaoDoConsentimento(req.consentimentoVersao),
                    criadoEm = agora,
                ),
            )
        val comanda =
            comandas.saveAndFlush(
                Comanda(
                    id = req.id ?: UUID.randomUUID(),
                    barracaId = barracaId,
                    clienteId = cliente.id,
                    guardaSolId = guardaSol?.id,
                    dia = dia,
                    numeroChegada = contador.proximo(barracaId, dia, TipoContador.CHEGADA),
                    tokenAcesso = req.token ?: SecureTokens.generate(TOKEN_BYTES),
                    abertaPorUsuarioId = quem.usuarioId,
                    abertaPorAtendenteId = quem.atendenteId,
                    abertaEm = agora,
                ),
            )
        return ResultadoCheckin.Ok(ComandaDetalhe(comanda, cliente, guardaSol), nova = true)
    }

    /**
     * "QR novo": o link da comanda é trocado (o cliente compartilhou ou perdeu o QR). A comanda, os pedidos e o saldo
     * ficam como estão; o link antigo deixa de abrir, e as conexões em tempo real dele caem. Só para comandas ativas.
     * O link antigo ainda ajuda a página antiga a descobrir que um pedido que ela reenviava já tinha chegado.
     */
    @Transactional
    fun novoQr(
        barracaId: UUID,
        comandaId: UUID,
    ): ComandaDetalhe? {
        autorHistorico.marcar()
        val comanda = comandas.travar(comandaId)?.takeIf { it.barracaId == barracaId && it.status in StatusComanda.ATIVAS } ?: return null
        comanda.tokenAnterior = comanda.tokenAcesso
        comanda.tokenAcesso = SecureTokens.generate(TOKEN_BYTES)
        comandas.save(comanda)
        hub.encerrarComanda(comanda.id)
        return detalhe(comanda)
    }

    /** Clientes com comanda ativa, por ordem de chegada. */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    fun ativos(
        barracaId: UUID,
        pagina: Int = 0,
        tamanho: Int = 100,
    ): List<ComandaDetalhe> {
        require(pagina >= 0 && tamanho in 1..200)
        val lista =
            comandas.findAtivas(
                barracaId,
                StatusComanda.ATIVAS,
                org.springframework.data.domain.PageRequest
                    .of(pagina, tamanho),
            )
        val porCliente = clientes.findAllById(lista.map { it.clienteId }).associateBy { it.id }
        val porGuardaSol = guardaSois.findAllById(lista.mapNotNull { it.guardaSolId }).associateBy { it.id }
        return lista.map { ComandaDetalhe(it, porCliente.getValue(it.clienteId), it.guardaSolId?.let(porGuardaSol::get)) }
    }

    fun ocupacao(barracaId: UUID): Map<UUID, Int> =
        jdbc
            .query(
                "SELECT guarda_sol_id, count(*) AS qtd FROM comanda WHERE barraca_id = ? AND status IN ('ABERTA','BLOQUEADA','AGUARDANDO_PAGAMENTO') AND guarda_sol_id IS NOT NULL GROUP BY guarda_sol_id",
                { rs, _ -> rs.getObject("guarda_sol_id", UUID::class.java) to rs.getInt("qtd") },
                barracaId,
            ).toMap()

    private fun detalhe(c: Comanda) =
        ComandaDetalhe(
            c,
            clientes.findById(c.clienteId).orElseThrow(),
            c.guardaSolId?.let { guardaSois.findById(it).orElse(null) },
        )

    companion object {
        /** A versão do texto de consentimento que o app atual mostra. O app manda a sua; o servidor só a registra. */
        const val CONSENTIMENTO_VERSAO_ATUAL = "2026-10-v1"

        /** Versão enviada pelo app, ou "desconhecida" se não veio (app antigo em cache) ou veio com formato estranho. */
        fun versaoDoConsentimento(enviada: String?): String =
            enviada?.trim()?.takeIf { Regex("[A-Za-z0-9._-]{1,30}").matches(it) } ?: "desconhecida"

        /**
         * 16 bytes em Base64 URL-safe sem preenchimento (22 letras). Não dá para provar a aleatoriedade de um token
         * que veio de fora, mas dá para recusar o que claramente não é: tamanho errado, letras fora do alfabeto,
         * bits finais sobrando e sequências repetidas (poucos símbolos diferentes).
         */
        fun tokenDoAparelhoValido(token: String): Boolean {
            if (!Regex("[A-Za-z0-9_-]{22}").matches(token)) return false
            // 22 letras carregam 132 bits; os 4 últimos devem ser zero para decodificar em exatamente 16 bytes
            if (token.last() !in "AQgw") return false
            return token.toSet().size >= TOKEN_SIMBOLOS_MIN
        }

        const val TOKEN_SIMBOLOS_MIN = 8
        const val NOME_MAX = 40

        /** 128 bits: impossível de adivinhar e curto o bastante para um QR fácil de ler no sol. */
        const val TOKEN_BYTES = 16
    }
}
