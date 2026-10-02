package br.com.mesamar.equipe

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PinRulesTest {
    @ParameterizedTest
    @ValueSource(strings = ["258014", "135792", "904512", " 739104 ", "112358"])
    fun `PINs aceitos`(pin: String) {
        assertNull(PinRules.problema(pin))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "123", "1234567", "12a456", "2580", "13579", "111111", "000000", "123456", "654321", "456789", "987654"])
    fun `PINs recusados`(pin: String) {
        assertNotNull(PinRules.problema(pin))
    }
}
