package br.com.mesamar.common

import java.text.Normalizer

/** Tipos de chave Pix aceitos pelo Banco Central. */
enum class TipoChavePix(
    val label: String,
    val exemplo: String,
) {
    CPF("CPF", "123.456.789-09"),
    CNPJ("CNPJ", "12.345.678/0001-95"),
    TELEFONE("Celular", "(13) 99999-8888"),
    EMAIL("E-mail", "voce@exemplo.com"),
    ALEATORIA("Chave aleatória", "7d9f0335-8dcc-4054-9bf9-0dbd61d36906"),
    ;

    companion object {
        fun fromCode(code: String?): TipoChavePix? = entries.firstOrNull { it.name == code }
    }
}

/**
 * Validação e normalização das chaves Pix, no formato em que vão para o QR (BR Code).
 * Devolve null quando a chave é inválida para o tipo escolhido.
 */
object ChavePix {
    private val emailRegex = Regex("^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9-]+(\\.[a-z0-9-]+)+$")
    private val evpRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun normalize(
        tipo: TipoChavePix,
        raw: String?,
    ): String? {
        val input = raw?.trim().orEmpty()
        if (input.isEmpty()) return null
        return when (tipo) {
            TipoChavePix.CPF -> input.filter(Char::isDigit).takeIf(::isValidCpf)
            TipoChavePix.CNPJ -> input.uppercase().filter { it.isLetterOrDigit() }.takeIf(::isValidCnpj)
            TipoChavePix.TELEFONE -> BrazilianPhone.normalize(input)?.takeIf { it.length == 14 } // só celular
            TipoChavePix.EMAIL -> input.lowercase().takeIf { it.length <= 77 && emailRegex.matches(it) }
            TipoChavePix.ALEATORIA -> input.lowercase().takeIf { evpRegex.matches(it) }
        }
    }

    /** Formata para mostrar na tela (a chave guardada fica sem máscara). */
    fun display(
        tipo: TipoChavePix,
        chave: String,
    ): String =
        when (tipo) {
            TipoChavePix.CPF -> "${chave.substring(0, 3)}.${chave.substring(3, 6)}.${chave.substring(6, 9)}-${chave.substring(9)}"
            TipoChavePix.CNPJ ->
                "${chave.substring(0, 2)}.${chave.substring(2, 5)}.${chave.substring(5, 8)}/" +
                    "${chave.substring(8, 12)}-${chave.substring(12)}"
            TipoChavePix.TELEFONE -> BrazilianPhone.display(chave)
            else -> chave
        }

    fun isValidCpf(digits: String): Boolean {
        if (digits.length != 11 || digits.all { it == digits[0] }) return false
        val nums = digits.map { it - '0' }
        val d1 = checkDigit(nums.take(9), (10 downTo 2).toList())
        val d2 = checkDigit(nums.take(9) + d1, (11 downTo 2).toList())
        return nums[9] == d1 && nums[10] == d2
    }

    /**
     * CNPJ numérico e o novo CNPJ alfanumérico (Receita Federal, a partir de julho/2026):
     * 12 posições [0-9A-Z] + 2 dígitos verificadores. Cada caractere vale (código ASCII − 48).
     */
    fun isValidCnpj(value: String): Boolean {
        if (value.length != 14) return false
        val base = value.take(12)
        val dv = value.takeLast(2)
        if (!base.all { it.isDigit() || it in 'A'..'Z' } || !dv.all(Char::isDigit)) return false
        if (value.all { it == value[0] }) return false
        val nums = base.map { it.code - 48 }
        val weights1 = listOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
        val weights2 = listOf(6) + weights1
        val d1 = checkDigit(nums, weights1)
        val d2 = checkDigit(nums + d1, weights2)
        return dv == "$d1$d2"
    }

    private fun checkDigit(
        nums: List<Int>,
        weights: List<Int>,
    ): Int {
        val rest = nums.zip(weights).sumOf { (n, w) -> n * w } % 11
        return if (rest < 2) 0 else 11 - rest
    }
}

/** Texto como o BR Code do Pix exige para nome e cidade: sem acento, maiúsculo, só ASCII. */
fun String.toPixText(): String =
    Normalizer
        .normalize(this.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^A-Za-z0-9 .\\-]"), "")
        .uppercase()
