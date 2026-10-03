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

    @Test
    fun `valores enormes viram null em vez de estourar ou virar outro numero`() {
        // 184467440737095517 reais × 100 dava a volta no Long e virava 84 centavos
        assertNull(Money.parseReais("184467440737095517"))
        assertNull(Money.parseReais("9".repeat(13)))
        assertNull(Money.parseReais("1".repeat(10_000)))
        assertNull(Money.parseReais("1" + ".000".repeat(10)))
        assertEquals(99_999_999_999_999L, Money.parseReais("999.999.999.999,99"))
        assertEquals(1200, Money.parseReais("000000000000012,00"))
    }
}
