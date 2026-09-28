package br.com.mesamar.common

/**
 * Normaliza telefones brasileiros para E.164 (+55DDNNNNNNNNN).
 *
 * Aceita o que as pessoas digitam de verdade: "(13) 99999-8888", "13 99999 8888",
 * "013999998888", "+55 13 99999-8888", "5513999998888".
 * Rejeita DDD inexistente e número com quantidade errada de dígitos.
 */
object BrazilianPhone {
    /** DDDs válidos no Brasil (Anatel). */
    private val validDdds: Set<Int> =
        """
        11 12 13 14 15 16 17 18 19
        21 22 24 27 28
        31 32 33 34 35 37 38
        41 42 43 44 45 46 47 48 49
        51 53 54 55
        61 62 63 64 65 66 67 68 69
        71 73 74 75 77 79
        81 82 83 84 85 86 87 88 89
        91 92 93 94 95 96 97 98 99
        """.trim()
            .split(Regex("\\s+"))
            .map(String::toInt)
            .toSet()

    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var digits = raw.filter(Char::isDigit)

        digits =
            when {
                digits.length in 12..13 && digits.startsWith("55") -> digits.drop(2)
                digits.length in 11..12 && digits.startsWith("0") -> digits.drop(1)
                else -> digits
            }

        if (digits.length !in 10..11) return null
        val ddd = digits.take(2).toInt()
        if (ddd !in validDdds) return null

        val number = digits.drop(2)
        val valid =
            when (number.length) {
                9 -> number.first() == '9' // celular
                8 -> number.first() in '2'..'5' // fixo
                else -> false
            }
        return if (valid) "+55$digits" else null
    }

    /** Formata para exibição: +5513999998888 → (13) 99999-8888. */
    fun display(e164: String): String {
        val digits = e164.removePrefix("+55")
        val ddd = digits.take(2)
        val number = digits.drop(2)
        val split = number.length - 4
        return "($ddd) ${number.take(split)}-${number.drop(split)}"
    }
}
