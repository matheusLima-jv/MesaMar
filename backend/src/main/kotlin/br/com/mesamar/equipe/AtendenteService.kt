package br.com.mesamar.equipe

import br.com.mesamar.barraca.Barraca
import br.com.mesamar.barraca.BarracaRepository
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.Errors
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** PIN de exatamente 6 números (quem já tem PIN de 4 continua entrando; só um PIN novo exige 6). Recusa os que qualquer um chuta primeiro (1111, 1234, 4321…). */
object PinRules {
    private val digits = Regex("^\\d{6}$")

    fun problema(pin: String): String? {
        val p = pin.trim()
        return when {
            !digits.matches(p) -> "O PIN precisa ter 6 números."
            p.all { it == p[0] } -> "PIN com todos os números iguais é fácil de adivinhar. Escolha outro."
            sequencia(p) -> "PIN em sequência (como 123456) é fácil de adivinhar. Escolha outro."
            else -> null
        }
    }

    private fun sequencia(p: String): Boolean {
        val steps = p.zipWithNext { a, b -> b - a }.toSet()
        return steps == setOf(1) || steps == setOf(-1)
    }
}

data class AtendenteForm(
    var nome: String = "",
    var pin: String = "",
)

sealed interface ResultadoPin {
    data class Ok(
        val atendente: Atendente,
    ) : ResultadoPin

    data class PinErrado(
        val tentativasRestantes: Int,
    ) : ResultadoPin

    data class Bloqueado(
        val ate: Instant,
    ) : ResultadoPin

    /** Atendente não existe nesta barraca ou foi desativado pelo dono. */
    data object Indisponivel : ResultadoPin
}

@Service
class AtendenteService(
    private val repository: AtendenteRepository,
    private val encoder: PasswordEncoder,
    private val barracas: BarracaRepository,
    private val pushSubs: br.com.mesamar.push.PushSubscriptionRepository,
    private val hub: br.com.mesamar.eventos.HubEventos,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun listar(barraca: Barraca): List<Atendente> = repository.findAllByBarracaIdOrderByNome(barraca.id)

    @Transactional
    fun criar(
        barraca: Barraca,
        form: AtendenteForm,
        errors: Errors,
    ): Atendente? {
        // Uma criação por vez na barraca: o limite e o nome repetido são conferidos sem corrida
        barracas.travar(barraca.id)
        val nome = validarNome(barraca, form.nome, exceto = null, errors)
        PinRules.problema(form.pin)?.let { errors.rejectValue("pin", "pin", it) }
        if (!errors.hasErrors() && repository.findAllByBarracaIdOrderByNome(barraca.id).size >= MAX_POR_BARRACA) {
            errors.reject("limite", "Limite de $MAX_POR_BARRACA atendentes por barraca.")
        }
        if (errors.hasErrors()) return null
        val now = Instant.now(clock)
        val atendente =
            repository.save(
                Atendente(
                    barracaId = barraca.id,
                    nome = nome!!,
                    pinHash = requireNotNull(encoder.encode(form.pin.trim())),
                    criadoEm = now,
                    atualizadoEm = now,
                ),
            )
        log.info("Atendente criado: barraca={} atendente={}", barraca.id, atendente.id)
        return atendente
    }

    /** Trocar o PIN também desbloqueia (é o que o dono faz quando o atendente esqueceu). */
    @Transactional
    fun trocarPin(
        barraca: Barraca,
        id: UUID,
        pin: String,
        errors: Errors,
    ): Boolean {
        val atendente = repository.findByIdAndBarracaId(id, barraca.id) ?: return false
        PinRules.problema(pin)?.let { errors.rejectValue("pin", "pin", it) }
        if (errors.hasErrors()) return false
        atendente.pinHash = requireNotNull(encoder.encode(pin.trim()))
        liberar(atendente)
        salvar(atendente)
        return true
    }

    @Transactional
    fun definirAtivo(
        barraca: Barraca,
        id: UUID,
        ativo: Boolean,
    ): Atendente? {
        val atendente = repository.findByIdAndBarracaId(id, barraca.id) ?: return null
        atendente.ativo = ativo
        // Desativar mata as sessões abertas para sempre: reativar depois não as faz voltar a valer (o atendente entra de novo)
        if (!ativo) revogar(atendente)
        salvar(atendente)
        revogarPush(atendente, ativo)
        return atendente
    }

    /**
     * "Sair de todos os aparelhos": celular perdido ou emprestado. As sessões abertas deste atendente caem na próxima
     * ação (e as conexões em tempo real, em instantes), e os avisos de pedido nos celulares dele são cancelados.
     * O PIN continua o mesmo; quem quiser trocar o PIN também usa "Trocar PIN".
     */
    @Transactional
    fun revogarSessoes(
        barraca: Barraca,
        id: UUID,
    ): Atendente? {
        val atendente = repository.findByIdAndBarracaId(id, barraca.id) ?: return null
        revogar(atendente)
        salvar(atendente)
        revogarPush(atendente, ativo = false)
        return atendente
    }

    private fun revogar(atendente: Atendente) {
        atendente.sessoesRevogadasEm = Instant.now(clock)
    }

    /** Desativado ou com sessões revogadas: os avisos de pedido (push) deixam de ir para os celulares dele. */
    private fun revogarPush(
        atendente: Atendente,
        ativo: Boolean,
    ) {
        if (!ativo) pushSubs.removerDoAtendente(atendente.id, atendente.barracaId)
        hub.revisarAcessosAposCommit()
    }

    @Transactional
    fun desbloquear(
        barraca: Barraca,
        id: UUID,
    ): Atendente? {
        val atendente = repository.findByIdAndBarracaId(id, barraca.id) ?: return null
        liberar(atendente)
        salvar(atendente)
        return atendente
    }

    /**
     * Confere o PIN (usado pelo login do app do quiosque).
     *
     * O bloqueio é **por origem** (o endereço de rede, o IP; vários aparelhos na mesma rede dividem a mesma origem): 5 PINs errados de um lugar bloqueiam aquele lugar por 15 minutos,
     * sem trancar a atendente no celular do quiosque. Antes, qualquer um que soubesse o código da barraca bloqueava a
     * equipe inteira errando o PIN de propósito. Para quem tenta de muitos lugares, há um teto da conta: 20 erros
     * (sem um acerto no meio) bloqueiam a atendente para todos, e o dono desbloqueia no painel. Cada origem soma no
     * máximo 5 erros a esse teto, então ele só é atingido de pelo menos 4 lugares diferentes.
     */
    @Transactional
    fun autenticar(
        barracaId: UUID,
        atendenteId: UUID,
        pin: String,
        origem: String = "",
    ): ResultadoPin {
        val atendente = repository.findForUpdate(atendenteId, barracaId)
        if (atendente == null || !atendente.ativo) return ResultadoPin.Indisponivel
        val now = Instant.now(clock)
        if (atendente.bloqueado(now)) return ResultadoPin.Bloqueado(atendente.bloqueadoAte!!)
        val chave = "${atendente.id}|$origem"
        falhasPorOrigem[chave]?.bloqueadoAte?.takeIf { it.isAfter(now) }?.let { return ResultadoPin.Bloqueado(it) }

        if (encoder.matches(pin.trim(), atendente.pinHash)) {
            liberar(atendente)
            salvar(atendente)
            return ResultadoPin.Ok(atendente)
        }

        // A linha do atendente está travada: as contas abaixo não correm com outra tentativa ao mesmo tempo
        val daOrigem =
            falhasPorOrigem.compute(chave) { _, atual ->
                when {
                    atual == null -> Falhas()
                    // O bloqueio desta origem venceu: nova rodada de 5, mas ela já contou para o teto da conta
                    atual.bloqueadoAte != null -> Falhas(jaBloqueada = true)
                    else -> atual
                }.also { it.quantidade++ }
            }!!
        // Cada origem soma no máximo 5 erros ao teto da conta: um lugar sozinho, insistindo, nunca tranca a atendente para todos
        if (!daOrigem.jaBloqueada) atendente.tentativasFalhas += 1
        val resultado =
            when {
                atendente.tentativasFalhas >= MAX_TENTATIVAS_CONTA -> {
                    atendente.tentativasFalhas = 0
                    atendente.bloqueadoAte = now.plus(BLOQUEIO)
                    esquecerOrigens(atendente)
                    log.warn("Atendente bloqueado por PIN errado (de vários lugares): barraca={} atendente={}", barracaId, atendente.id)
                    ResultadoPin.Bloqueado(atendente.bloqueadoAte!!)
                }
                daOrigem.quantidade >= MAX_TENTATIVAS -> {
                    daOrigem.bloqueadoAte = now.plus(BLOQUEIO)
                    log.warn("PIN errado demais de uma origem: barraca={} atendente={}", barracaId, atendente.id)
                    ResultadoPin.Bloqueado(daOrigem.bloqueadoAte!!)
                }
                else -> ResultadoPin.PinErrado(MAX_TENTATIVAS - daOrigem.quantidade)
            }
        salvar(atendente)
        return resultado
    }

    /** Erros de PIN por (atendente, origem), em memória: um servidor só no piloto; com dois, cada um conta os seus. */
    private class Falhas(
        var quantidade: Int = 0,
        var bloqueadoAte: Instant? = null,
        /** Esta origem já foi bloqueada uma vez: os erros dela não contam mais para o teto da conta. */
        val jaBloqueada: Boolean = false,
    )

    private val falhasPorOrigem = ConcurrentHashMap<String, Falhas>()

    private fun esquecerOrigens(atendente: Atendente) {
        falhasPorOrigem.keys.removeIf { it.startsWith("${atendente.id}|") }
    }

    private fun validarNome(
        barraca: Barraca,
        bruto: String,
        exceto: UUID?,
        errors: Errors,
    ): String? {
        val nome = bruto.trim().replace(Regex("\\s+"), " ")
        when {
            nome.isEmpty() -> errors.rejectValue("nome", "obrigatorio", "Como o atendente se chama?")
            nome.length > NOME_MAX -> errors.rejectValue("nome", "tamanho", "Use no máximo $NOME_MAX letras.")
            repository.existsNome(barraca.id, nome, exceto ?: NENHUM) ->
                errors.rejectValue("nome", "repetido", "Já existe um atendente com esse nome. Use um apelido ou o sobrenome.")
        }
        return nome.takeUnless { errors.hasFieldErrors("nome") }
    }

    private fun liberar(atendente: Atendente) {
        atendente.tentativasFalhas = 0
        atendente.bloqueadoAte = null
        esquecerOrigens(atendente)
    }

    private fun salvar(atendente: Atendente) {
        atendente.atualizadoEm = Instant.now(clock)
        repository.save(atendente)
    }

    companion object {
        const val NOME_MAX = 40
        const val MAX_TENTATIVAS = 5
        const val MAX_TENTATIVAS_CONTA = 20
        const val MAX_POR_BARRACA = 30
        val BLOQUEIO: Duration = Duration.ofMinutes(15)
        private val NENHUM = UUID(0, 0)
    }
}
