package br.com.mesamar.common

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrazilianPhoneTest {
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        "'(13) 99999-8888', +5513999998888",
        "'13 99999 8888', +5513999998888",
        "13999998888, +5513999998888",
        "013999998888, +5513999998888",
        "'+55 13 99999-8888', +5513999998888",
        "5513999998888, +5513999998888",
        "'(11) 3456-7890', +551134567890",
        "'(55) 99123-4567', +5555991234567",
    )
    fun `normaliza formatos comuns para E164`(
        raw: String,
        expected: String,
    ) {
        assertEquals(expected, BrazilianPhone.normalize(raw))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "abc",
            "(13) 9999-888", // curto
            "(20) 99999-8888", // DDD 20 não existe
            "(13) 89999-8888", // celular sem o 9 na frente
            "(13) 7345-6789", // fixo começando com 7
            "+1 415 555 0100", // outro país
            "(13) 99999-88888", // longo
        ],
    )
    fun `rejeita numeros invalidos`(raw: String) {
        assertNull(BrazilianPhone.normalize(raw), raw)
    }

    @Test
    fun `formata para exibicao`() {
        assertEquals("(13) 99999-8888", BrazilianPhone.display("+5513999998888"))
        assertEquals("(11) 3456-7890", BrazilianPhone.display("+551134567890"))
    }
}
