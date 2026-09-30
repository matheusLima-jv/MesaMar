package br.com.mesamar.common

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyTest {
    @ParameterizedTest
    @CsvSource(
        "150, 15000",
        "'150,5', 15050",
        "'150,50', 15050",
        "'1.234,56', 123456",
        "'1234,56', 123456",
        "'R$ 12,00', 1200",
        "'0,99', 99",
    )
    fun `reais viram centavos`(
        entrada: String,
        centavos: Long,
    ) {
        assertEquals(centavos, Money.parseReais(entrada))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "abc", "12.5", "1,234", "-10", "1.23,00", "12,345"])
    fun `formatos invalidos devolvem null`(entrada: String) {
        assertNull(Money.parseReais(entrada))
    }

    @Test
    fun `formatacao`() {
        assertEquals("R$ 1.234,56", Money.format(123456))
        assertEquals("R$ 0,05", Money.format(5))
        assertEquals("R$ 1.000.000,00", Money.format(100_000_000))
        assertEquals("150,00", Money.toInput(15000))
    }
}
