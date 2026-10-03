package br.com.mesamar.guardasol

import br.com.mesamar.barraca.Barraca
import br.com.mesamar.barraca.BarracaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.Errors
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class LoteForm(
    var de: String = "1",
    var ate: String = "",
)

data class LoteResultado(
    val criados: Int,
    val reativados: Int,
    val jaExistiam: Int,
)

@Service
class GuardaSolService(
    private val repository: GuardaSolRepository,
    private val barracas: BarracaRepository,
    private val clock: Clock,
) {
    fun listar(barraca: Barraca): List<GuardaSol> = repository.findAllByBarracaIdOrderByNumero(barraca.id)

    fun ativos(barraca: Barraca): Int = repository.countByBarracaIdAndAtivoTrue(barraca.id)

    /**
     * "Do 1 ao 20": cria os números que faltam e reativa os desativados da faixa.
     * Os que já estão ativos ficam como estão. Nunca passa do limite da barraca.
     */
    @Transactional
    fun criarLote(
        barracaLida: Barraca,
        form: LoteForm,
        errors: Errors,
    ): LoteResultado? {
        // Relida e travada: dois lotes ao mesmo tempo (ou um lote e a troca do limite em Regras) entram um por vez
        val barraca = barracas.travar(barracaLida.id) ?: return null
        val de = form.de.trim().toIntOrNull()
        val ate = form.ate.trim().toIntOrNull()
        if (de == null || de !in NUMERO_MIN..NUMERO_MAX) errors.rejectValue("de", "faixa", "Número de $NUMERO_MIN a $NUMERO_MAX.")
        if (ate == null || ate !in NUMERO_MIN..NUMERO_MAX) errors.rejectValue("ate", "faixa", "Número de $NUMERO_MIN a $NUMERO_MAX.")
        if (errors.hasErrors()) return null
        if (ate!! < de!!) {
            errors.rejectValue("ate", "ordem", "O último número precisa ser maior ou igual ao primeiro.")
            return null
        }

        val existentes = repository.findAllByBarracaIdOrderByNumero(barraca.id).associateBy { it.numero }
        val faixa = de..ate
        val novos = faixa.filter { it !in existentes }
        val reativar = faixa.mapNotNull { existentes[it] }.filter { !it.ativo }
        val ativosDepois = existentes.values.count { it.ativo } + novos.size + reativar.size
        if (ativosDepois > barraca.limiteGuardaSois) {
            val sobra = (barraca.limiteGuardaSois - existentes.values.count { it.ativo }).coerceAtLeast(0)
            errors.reject(
                "limite",
                "O seu limite é de ${barraca.limiteGuardaSois} guarda-sóis ativos e " +
                    (if (sobra == 0) "ele já foi atingido." else "cabem só mais $sobra.") +
                    " Dá para aumentar o limite em Regras.",
            )
            return null
        }

        val now = Instant.now(clock)
        repository.saveAll(novos.map { GuardaSol(barracaId = barraca.id, numero = it, criadoEm = now) })
        reativar.forEach { it.ativo = true }
        repository.saveAll(reativar)
        return LoteResultado(novos.size, reativar.size, faixa.count() - novos.size - reativar.size)
    }

    /** Liga/desliga um guarda-sol. Religar respeita o limite. Devolve mensagem de erro ou null. */
    @Transactional
    fun alternar(
        barracaLida: Barraca,
        id: UUID,
    ): String? {
        val barraca = barracas.travar(barracaLida.id) ?: return "Guarda-sol não encontrado."
        val gs = repository.findByIdAndBarracaId(id, barraca.id) ?: return "Guarda-sol não encontrado."
        if (!gs.ativo && ativos(barraca) >= barraca.limiteGuardaSois) {
            return "Limite de ${barraca.limiteGuardaSois} guarda-sóis ativos atingido. Desative outro ou aumente o limite em Regras."
        }
        gs.ativo = !gs.ativo
        repository.save(gs)
        return null
    }

    companion object {
        const val NUMERO_MIN = 1
        const val NUMERO_MAX = 999
    }
}
