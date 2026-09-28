package br.com.mesamar.config

import jakarta.validation.Validation
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SitePropertiesTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `configuracao padrao e valida`() {
        assertTrue(validator.validate(SiteProperties()).isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["5513999998888", "551334567890", ""])
    fun `aceita WhatsApp com DDI 55 e DDD ou vazio`(number: String) {
        assertTrue(validator.validate(SiteProperties(whatsappNumber = number)).isEmpty(), number)
    }

    @ParameterizedTest
    @ValueSource(strings = ["13999998888", "+5513999998888", "55 13 99999-8888", "5513", "abc"])
    fun `rejeita WhatsApp fora do formato 55 + DDD + numero`(number: String) {
        assertEquals(1, validator.validate(SiteProperties(whatsappNumber = number)).size, number)
    }

    @ParameterizedTest
    @ValueSource(strings = ["https://mesamar.com.br/", "mesamar.com.br", "ftp://mesamar.com.br"])
    fun `rejeita URL publica sem protocolo http(s) ou com barra final`(url: String) {
        assertEquals(1, validator.validate(SiteProperties(publicBaseUrl = url)).size, url)
    }

    @Test
    fun `link do WhatsApp codifica espacos como porcento-20 e nao como mais`() {
        val link = SiteProperties(whatsappNumber = "5513999998888").whatsappLink("Olá, tudo bem?")

        assertEquals("https://wa.me/5513999998888?text=Ol%C3%A1%2C%20tudo%20bem%3F", link)
    }
}
