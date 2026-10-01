package br.com.mesamar.common

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Tokens aleatórios em Base64 URL-safe, de um SecureRandom.
 *
 * - Convite e troca de senha: 256 bits (padrão). No banco vai só o SHA-256 do token.
 * - Token da comanda (QR do cliente): 128 bits, guardado em claro (ver ADR 0007).
 */
object SecureTokens {
    private val random = SecureRandom()

    fun generate(bytesCount: Int = 32): String {
        require(bytesCount >= 16) { "token com menos de 128 bits" }
        val bytes = ByteArray(bytesCount)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(token: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
