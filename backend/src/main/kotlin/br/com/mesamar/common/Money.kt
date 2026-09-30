package br.com.mesamar.common

/** Dinheiro sempre em centavos (Long). Conversões de/para o formato brasileiro "1.234,56". */
object Money {
    private val pattern = Regex("^\\d{1,3}(\\.?\\d{3})*(,\\d{1,2})?$")

    /** "150" → 15000, "150,5" → 15050, "1.234,56" → 123456. Null se inválido ou vazio. */
    fun parseReais(raw: String?): Long? {
        val input =
            raw
                ?.trim()
                ?.removePrefix("R$")
                ?.trim()
                .orEmpty()
        if (input.isEmpty() || !pattern.matches(input)) return null
        val (inteiro, decimal) = input.replace(".", "").split(",").let { it[0] to it.getOrElse(1) { "0" } }
        val centavos = decimal.padEnd(2, '0').take(2).toLong()
        return inteiro.toLong() * 100 + centavos
    }

    /** 15000 → "150,00" (sem "R$", para campos de formulário). */
    fun toInput(centavos: Long): String = "%d,%02d".format(centavos / 100, centavos % 100)

    /** 123456 → "R$ 1.234,56" */
    fun format(centavos: Long): String {
        val reais =
            (centavos / 100)
                .toString()
                .reversed()
                .chunked(3)
                .joinToString(".")
                .reversed()
        return "R$ $reais,%02d".format(centavos % 100)
    }
}
