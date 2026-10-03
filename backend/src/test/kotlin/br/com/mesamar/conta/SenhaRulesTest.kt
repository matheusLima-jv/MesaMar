package br.com.mesamar.conta

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SenhaRulesTest {
    @Test
    fun `o limite de 72 e em bytes, nao em letras`() {
        assertNull(SenhaRules.problema("a".repeat(72), "x@exemplo.com"))
        assertNotNull(SenhaRules.problema("a".repeat(73), "x@exemplo.com"))
        // 40 letras com acento = 80 bytes: antes passava e o BCrypt dava erro interno
        assertNotNull(SenhaRules.problema("é".repeat(40), "x@exemplo.com"))
        assertNotNull(SenhaRules.problema("senha boa " + "😀".repeat(16), "x@exemplo.com"))
        assertNull(SenhaRules.problema("ação ".repeat(10), "x@exemplo.com"))
    }
}
