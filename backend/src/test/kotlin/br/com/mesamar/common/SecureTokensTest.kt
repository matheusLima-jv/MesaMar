package br.com.mesamar.common

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecureTokensTest {
    @Test
    fun `tokens sao longos, seguros para URL e nunca repetem`() {
        val tokens = List(1000) { SecureTokens.generate() }
        assertEquals(1000, tokens.toSet().size)
        tokens.forEach {
            assertEquals(43, it.length) // 32 bytes em Base64 sem "="
            assertTrue(it.matches(Regex("^[A-Za-z0-9_-]+$")), it)
        }
    }

    @Test
    fun `hash e SHA-256 em hexadecimal e deterministico`() {
        val token = SecureTokens.generate()
        assertEquals(SecureTokens.hash(token), SecureTokens.hash(token))
        assertEquals(64, SecureTokens.hash(token).length)
        assertNotEquals(token, SecureTokens.hash(token))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SecureTokens.hash("abc"))
    }
}
