package br.com.mesamar.barraca

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class SlugTest {
    @ParameterizedTest
    @CsvSource(
        "Barraca do Zé!, barraca-do-ze",
        "  Quiosque   Astúrias 12 , quiosque-asturias-12",
        "Açaí & Cia, acai-cia",
        "!!!, barraca",
        "'', barraca",
    )
    fun `slug legivel`(
        nome: String,
        esperado: String,
    ) {
        assertEquals(esperado, Slug.from(nome))
    }

    @Test
    fun `slug limitado a 50 caracteres sem hifen no fim`() {
        assertEquals("a".repeat(49), Slug.from("a".repeat(49) + " b c d"))
        assertEquals(50, Slug.from("x".repeat(80)).length)
    }
}
