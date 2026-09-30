package br.com.mesamar.equipe

import br.com.mesamar.barraca.Barraca
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.Errors
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** PIN de 4 a 6 números. Recusa os que qualquer um chuta primeiro (1111, 1234, 4321…). */
object PinRules {
    private val digits = Regex("^\\d{4,6}$")

    fun problema(pin: String): String? {
        val p = pin.trim()
        return when {
            !digits.matches(p) -> "O PIN precisa ter de 4 a 6 números."
            p.all { it == p[0] } -> "PIN com todos os números iguais é fácil de adivinhar. Escolha outro."
            sequencia(p) -> "PIN em sequência (como 1234) é fácil de adivinhar. Escolha outro."
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
        salvar(atendente)
        return atendente
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
     * Confere o PIN (usado pelo login do app do quiosque). Com o bloqueio ativo, nem olha o PIN:
     * assim não dá para continuar testando PINs durante os 15 minutos.
     */
    @Transactional
    fun autenticar(
        barracaId: UUID,
        atendenteId: UUID,
        pin: String,
    ): ResultadoPin {
        val atendente = repository.findForUpdate(atendenteId, barracaId)
        if (atendente == null || !atendente.ativo) return ResultadoPin.Indisponivel
        val now = Instant.now(clock)
        if (atendente.bloqueado(now)) return ResultadoPin.Bloqueado(atendente.bloqueadoAte!!)

        if (encoder.matches(pin.trim(), atendente.pinHash)) {
            liberar(atendente)
            salvar(atendente)
            return ResultadoPin.Ok(atendente)
        }

        atendente.tentativasFalhas += 1
        val resultado =
            if (atendente.tentativasFalhas >= MAX_TENTATIVAS) {
                atendente.tentativasFalhas = 0
                atendente.bloqueadoAte = now.plus(BLOQUEIO)
                log.warn("Atendente bloqueado por PIN errado: barraca={} atendente={}", barracaId, atendente.id)
                ResultadoPin.Bloqueado(atendente.bloqueadoAte!!)
            } else {
                ResultadoPin.PinErrado(MAX_TENTATIVAS - atendente.tentativasFalhas)
            }
        salvar(atendente)
        return resultado
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
    }

    private fun salvar(atendente: Atendente) {
        atendente.atualizadoEm = Instant.now(clock)
        repository.save(atendente)
    }

    companion object {
        const val NOME_MAX = 40
        const val MAX_TENTATIVAS = 5
        const val MAX_POR_BARRACA = 30
        val BLOQUEIO: Duration = Duration.ofMinutes(15)
        private val NENHUM = UUID(0, 0)
    }
}
