package br.com.mesamar.equipe

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PinRulesTest {
    @ParameterizedTest
    @ValueSource(strings = ["2580", "1357", "904512", " 7391 ", "1123"])
    fun `PINs aceitos`(pin: String) {
        assertNull(PinRules.problema(pin))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "123", "1234567", "12a4", "1111", "000000", "1234", "4321", "456789", "987654"])
    fun `PINs recusados`(pin: String) {
        assertNotNull(PinRules.problema(pin))
    }
}
