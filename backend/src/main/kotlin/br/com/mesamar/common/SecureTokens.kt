package br.com.mesamar.common

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Tokens de uso único (convite, troca de senha): 256 bits aleatórios em Base64 URL-safe.
 * No banco vai só o SHA-256 do token; o token em si só existe no link enviado.
 */
object SecureTokens {
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(token: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
