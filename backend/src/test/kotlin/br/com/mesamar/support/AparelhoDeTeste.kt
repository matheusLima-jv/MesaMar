package br.com.mesamar.support

import br.com.mesamar.push.WebPushCripto
import java.security.KeyPair
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * O lado do **aparelho** do Web Push, para os testes: faz o que o navegador faz ao receber uma mensagem
 * (RFC 8291 + RFC 8188). Serve para conferir o que o servidor mandou, sem confiar na própria cifra do servidor.
 */
class AparelhoDeTeste(
    val par: KeyPair = WebPushCripto.gerarPar(),
    val segredoAuth: ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) },
) {
    val publicaBytes: ByteArray get() = WebPushCripto.publicaEmBytes(par.public)

    /** Decifra o corpo `aes128gcm` recebido do serviço de push e devolve o texto da mensagem. */
    fun decifrar(corpo: ByteArray): String {
        require(corpo.size > 86) { "corpo curto demais: ${corpo.size} bytes" }
        val salt = corpo.copyOfRange(0, 16)

        fun byte(i: Int) = corpo[i].toInt() and 0xff
        val tamanhoBloco = (byte(16) shl 24) or (byte(17) shl 16) or (byte(18) shl 8) or byte(19)
        val tamanhoId = corpo[20].toInt() and 0xff
        val publicaServidor = corpo.copyOfRange(21, 21 + tamanhoId)
        val bloco = corpo.copyOfRange(21 + tamanhoId, corpo.size)
        require(tamanhoBloco == WebPushCripto.TAMANHO_BLOCO) { "tamanho de bloco inesperado: $tamanhoBloco" }
        val chaves = WebPushCripto.derivarChaves(par.private, publicaServidor, publicaBytes, publicaServidor, segredoAuth, salt)
        val cifra =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(chaves.cek, "AES"), GCMParameterSpec(128, chaves.nonce))
            }
        val aberto = cifra.doFinal(bloco)
        // O último byte é o delimitador do último bloco (0x02); o resto é a mensagem
        require(aberto.last() == 2.toByte()) { "faltou o delimitador do último bloco" }
        return String(aberto, 0, aberto.size - 1, Charsets.UTF_8)
    }
}
