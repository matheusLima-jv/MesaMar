package br.com.mesamar.barraca

import br.com.mesamar.common.ChavePix
import br.com.mesamar.common.Money
import br.com.mesamar.common.Praia
import br.com.mesamar.common.TipoChavePix
import br.com.mesamar.common.toPixText
import br.com.mesamar.conta.Usuario
import br.com.mesamar.conta.UsuarioRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.Errors
import java.text.Normalizer
import java.time.Clock
import java.time.Instant

/** Nome e praia. É o primeiro passo depois de criar a conta. */
data class BarracaForm(
    var nome: String = "",
    var praia: String = "",
)

/** Regras de operação. Números chegam como texto para dar mensagens de erro em português. */
data class RegrasForm(
    var limiteGuardaSois: String = "30",
    var modoPagamento: String = ModoPagamento.FINAL.name,
    /** Em reais ("150" ou "150,00"). Vazio = sem limite. */
    var limiteSaldo: String = "",
    var tempoAlertaMin: String = "10",
) {
    companion object {
        fun of(b: Barraca) =
            RegrasForm(
                limiteGuardaSois = b.limiteGuardaSois.toString(),
                modoPagamento = b.modoPagamento.name,
                limiteSaldo = b.limiteSaldoAbertoCentavos?.let(Money::toInput).orEmpty(),
                tempoAlertaMin = b.tempoAlertaPedidoMin.toString(),
            )
    }
}

data class PixForm(
    var tipo: String = "",
    var chave: String = "",
    var nomeRecebedor: String = "",
    var cidade: String = "Guarujá",
) {
    companion object {
        fun of(b: Barraca) =
            if (b.chavePix == null) {
                PixForm(nomeRecebedor = b.nome.toPixText().take(PIX_NOME_MAX))
            } else {
                PixForm(
                    tipo = b.tipoChavePix!!.name,
                    chave = ChavePix.display(b.tipoChavePix!!, b.chavePix!!),
                    nomeRecebedor = b.nomeRecebedorPix.orEmpty(),
                    cidade = b.cidadePix.orEmpty(),
                )
            }

        /** Limites do BR Code (EMV): nome do recebedor até 25 caracteres, cidade até 15. */
        const val PIX_NOME_MAX = 25
        const val PIX_CIDADE_MAX = 15
    }
}

@Service
class BarracaService(
    private val barracas: BarracaRepository,
    private val usuarios: UsuarioRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun daUsuario(usuario: Usuario): Barraca? = usuario.barracaId?.let { barracas.findById(it).orElse(null) }

    @Transactional
    fun criar(
        usuario: Usuario,
        form: BarracaForm,
        errors: Errors,
    ): Barraca? {
        check(usuario.barracaId == null) { "usuário já tem barraca" }
        val praia = validarDados(form, errors) ?: return null
        val now = Instant.now(clock)
        val barraca =
            barracas.save(
                Barraca(
                    nome = form.nome.trim(),
                    slug = slugUnico(form.nome),
                    praia = praia,
                    criadaEm = now,
                    atualizadaEm = now,
                ),
            )
        usuario.barracaId = barraca.id
        usuarios.save(usuario)
        log.info("Barraca criada: barraca={} slug={} usuario={}", barraca.id, barraca.slug, usuario.id)
        return barraca
    }

    /** O slug (endereço público) não muda quando o nome muda: QR codes impressos continuam valendo. */
    @Transactional
    fun atualizarDados(
        barraca: Barraca,
        form: BarracaForm,
        errors: Errors,
    ): Boolean {
        val praia = validarDados(form, errors) ?: return false
        barraca.nome = form.nome.trim()
        barraca.praia = praia
        salvar(barraca)
        return true
    }

    @Transactional
    fun atualizarRegras(
        barraca: Barraca,
        form: RegrasForm,
        errors: Errors,
    ): Boolean {
        val limite = form.limiteGuardaSois.trim().toIntOrNull()
        if (limite == null || limite !in 1..LIMITE_GUARDA_SOIS_MAX) {
            errors.rejectValue("limiteGuardaSois", "faixa", "Informe um número de 1 a $LIMITE_GUARDA_SOIS_MAX.")
        }
        val modo = ModoPagamento.entries.firstOrNull { it.name == form.modoPagamento }
        if (modo == null) errors.rejectValue("modoPagamento", "obrigatorio", "Escolha quando o cliente paga.")

        val saldo = form.limiteSaldo.takeIf { it.isNotBlank() }?.let(Money::parseReais)
        if (form.limiteSaldo.isNotBlank() && (saldo == null || saldo <= 0)) {
            errors.rejectValue("limiteSaldo", "formato", "Use só números, como 150 ou 150,00. Deixe vazio para não ter limite.")
        } else if (saldo != null && saldo > LIMITE_SALDO_MAX_CENTAVOS) {
            errors.rejectValue("limiteSaldo", "faixa", "O limite pode ser de no máximo ${Money.format(LIMITE_SALDO_MAX_CENTAVOS)}.")
        }

        val alerta = form.tempoAlertaMin.trim().toIntOrNull()
        if (alerta == null || alerta !in 1..TEMPO_ALERTA_MAX) {
            errors.rejectValue("tempoAlertaMin", "faixa", "Informe de 1 a $TEMPO_ALERTA_MAX minutos.")
        }
        if (errors.hasErrors()) return false

        barraca.limiteGuardaSois = limite!!
        barraca.modoPagamento = modo!!
        barraca.limiteSaldoAbertoCentavos = saldo
        barraca.tempoAlertaPedidoMin = alerta!!
        salvar(barraca)
        return true
    }

    @Transactional
    fun atualizarPix(
        barraca: Barraca,
        form: PixForm,
        errors: Errors,
    ): Boolean {
        val tipo = TipoChavePix.fromCode(form.tipo)
        if (tipo == null) errors.rejectValue("tipo", "obrigatorio", "Escolha o tipo da chave.")

        val chave = tipo?.let { ChavePix.normalize(it, form.chave) }
        if (tipo != null && chave == null) {
            val msg =
                if (form.chave.isBlank()) {
                    "Digite a sua chave Pix."
                } else {
                    "Essa chave não parece um ${tipo.label} válido. Confira os números (exemplo: ${tipo.exemplo})."
                }
            errors.rejectValue("chave", "invalida", msg)
        }

        val nome = form.nomeRecebedor.toPixText().trim()
        when {
            nome.isEmpty() -> errors.rejectValue("nomeRecebedor", "obrigatorio", "Informe o nome que aparece no banco.")
            nome.length > PixForm.PIX_NOME_MAX ->
                errors.rejectValue("nomeRecebedor", "tamanho", "Use no máximo ${PixForm.PIX_NOME_MAX} letras (pode abreviar).")
        }
        val cidade = form.cidade.toPixText().trim()
        when {
            cidade.isEmpty() -> errors.rejectValue("cidade", "obrigatorio", "Informe a cidade.")
            cidade.length > PixForm.PIX_CIDADE_MAX ->
                errors.rejectValue("cidade", "tamanho", "Use no máximo ${PixForm.PIX_CIDADE_MAX} letras.")
        }
        if (errors.hasErrors()) return false

        barraca.tipoChavePix = tipo
        barraca.chavePix = chave
        barraca.nomeRecebedorPix = nome
        barraca.cidadePix = cidade
        salvar(barraca)
        log.info("Chave Pix atualizada: barraca={} tipo={}", barraca.id, tipo)
        return true
    }

    private fun validarDados(
        form: BarracaForm,
        errors: Errors,
    ): Praia? {
        val nome = form.nome.trim()
        when {
            nome.isEmpty() -> errors.rejectValue("nome", "obrigatorio", "Qual é o nome da barraca?")
            nome.length > NOME_MAX -> errors.rejectValue("nome", "tamanho", "O nome pode ter no máximo $NOME_MAX letras.")
        }
        val praia = Praia.fromCode(form.praia)
        if (praia == null) errors.rejectValue("praia", "obrigatorio", "Escolha a praia.")
        return praia.takeUnless { errors.hasErrors() }
    }

    private fun salvar(barraca: Barraca) {
        barraca.atualizadaEm = Instant.now(clock)
        barracas.save(barraca)
    }

    private fun slugUnico(nome: String): String {
        val base = Slug.from(nome)
        if (!barracas.existsBySlug(base)) return base
        return (2..Int.MAX_VALUE).asSequence().map { "$base-$it" }.first { !barracas.existsBySlug(it) }
    }

    companion object {
        const val NOME_MAX = 80
        const val LIMITE_GUARDA_SOIS_MAX = 200
        const val TEMPO_ALERTA_MAX = 120
        const val LIMITE_SALDO_MAX_CENTAVOS = 1_000_000L // R$ 10.000,00
    }
}

/** Endereço curto e legível para a barraca: "Barraca do Zé!" → "barraca-do-ze". */
object Slug {
    private const val MAX = 50

    fun from(text: String): String =
        Normalizer
            .normalize(text.trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(MAX)
            .trimEnd('-')
            .ifEmpty { "barraca" }
}
