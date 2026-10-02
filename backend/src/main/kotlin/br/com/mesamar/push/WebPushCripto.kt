package br.com.mesamar.push

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Base64Url {
    fun codificar(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decodificar(texto: String): ByteArray = Base64.getUrlDecoder().decode(texto)
}

/**
 * Criptografia do Web Push: **RFC 8291** (mensagem cifrada com ECDH P-256 + HKDF) sobre o conteúdo `aes128gcm`
 * da **RFC 8188**. Feita só com a criptografia do JDK, sem biblioteca de terceiros, e conferida contra o exemplo
 * e os valores intermediários da própria RFC (ver `WebPushCriptoTest`).
 */
object WebPushCripto {
    /** Tamanho do bloco cifrado (record size) que usamos: 4096, o padrão. Cabe uma mensagem só. */
    const val TAMANHO_BLOCO = 4096

    /** Maior mensagem que cabe em um bloco: 4096 menos o byte de fim (0x02) e os 16 bytes da etiqueta GCM. */
    const val MENSAGEM_MAX = TAMANHO_BLOCO - 1 - 16

    private val aleatorio = SecureRandom()

    private val curva: ECParameterSpec by lazy {
        AlgorithmParameters
            .getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    fun gerarPar(): KeyPair =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

    /** Chave pública no formato "ponto não comprimido" (65 bytes: 0x04, X, Y), como o navegador entrega. */
    fun publicaEmBytes(chave: PublicKey): ByteArray {
        val ponto = (chave as ECPublicKey).w
        return byteArrayOf(4) + em32(ponto.affineX) + em32(ponto.affineY)
    }

    fun privadaEmBytes(chave: PrivateKey): ByteArray = em32((chave as ECPrivateKey).s)

    /** Lê uma chave pública de 65 bytes. Recusa o que não é um ponto da curva P-256 (a RFC exige essa conferência). */
    fun publicaDeBytes(bytes: ByteArray): ECPublicKey {
        require(bytes.size == 65 && bytes[0] == 4.toByte()) { "chave pública deve ter 65 bytes no formato não comprimido" }
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        require(naCurva(x, y)) { "o ponto não está na curva P-256" }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), curva)) as ECPublicKey
    }

    fun privadaDeBytes(bytes: ByteArray): ECPrivateKey {
        require(bytes.size == 32) { "chave privada deve ter 32 bytes" }
        return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, bytes), curva)) as ECPrivateKey
    }

    /** `y² = x³ + ax + b (mod p)`, com x e y dentro do campo. */
    private fun naCurva(
        x: BigInteger,
        y: BigInteger,
    ): Boolean {
        val p = (curva.curve.field as ECFieldFp).p
        if (x.signum() < 0 || y.signum() < 0 || x >= p || y >= p) return false
        val direita =
            x
                .pow(3)
                .add(curva.curve.a.multiply(x))
                .add(curva.curve.b)
                .mod(p)
        return y.pow(2).mod(p) == direita
    }

    private fun em32(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size) // zero à esquerda do sinal
            else -> ByteArray(32 - b.size) + b
        }
    }

    fun hmac(
        chave: ByteArray,
        dado: ByteArray,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(chave, "HmacSHA256"))
            doFinal(dado)
        }

    /** HKDF-Expand (RFC 5869) para até 32 bytes: um bloco só, `HMAC(prk, info || 0x01)`. */
    fun hkdfExpandir(
        prk: ByteArray,
        info: ByteArray,
        tamanho: Int,
    ): ByteArray {
        require(tamanho <= 32)
        return hmac(prk, info + byteArrayOf(1)).copyOf(tamanho)
    }

    /** Os valores intermediários da RFC 8291 (Apêndice A), na ordem em que são calculados. */
    class Chaves(
        val ecdh: ByteArray,
        val prkChave: ByteArray,
        val ikm: ByteArray,
        val prk: ByteArray,
        val cek: ByteArray,
        val nonce: ByteArray,
    )

    /**
     * A derivação de chaves (RFC 8291, seções 3.3 e 3.4, e RFC 8188): ECDH, mistura com o segredo de autenticação e
     * chave e nonce do conteúdo. Os dois lados chegam às mesmas chaves, cada um com a sua chave privada e a pública do
     * outro (`publicaDoPar`); o `key_info` leva sempre as duas públicas na mesma ordem (aparelho, depois servidor).
     */
    fun derivarChaves(
        privada: PrivateKey,
        publicaDoPar: ByteArray,
        publicaAparelho: ByteArray,
        publicaServidor: ByteArray,
        segredoAuth: ByteArray,
        salt: ByteArray,
    ): Chaves {
        val ecdh =
            KeyAgreement
                .getInstance("ECDH")
                .apply {
                    init(privada)
                    doPhase(publicaDeBytes(publicaDoPar), true)
                }.generateSecret()
        val prkChave = hmac(segredoAuth, ecdh)
        val chaveInfo = "WebPush: info".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + publicaAparelho + publicaServidor
        val ikm = hkdfExpandir(prkChave, chaveInfo, 32)
        val prk = hmac(salt, ikm)
        val cek = hkdfExpandir(prk, "Content-Encoding: aes128gcm".toByteArray(Charsets.US_ASCII) + byteArrayOf(0), 16)
        val nonce = hkdfExpandir(prk, "Content-Encoding: nonce".toByteArray(Charsets.US_ASCII) + byteArrayOf(0), 12)
        return Chaves(ecdh, prkChave, ikm, prk, cek, nonce)
    }

    /**
     * Cifra a mensagem para o aparelho e devolve o corpo da requisição ao serviço de push (`Content-Encoding: aes128gcm`):
     * cabeçalho de 86 bytes (salt, tamanho do bloco, id e chave pública do servidor) seguido do bloco cifrado.
     * `par` e `salt` só são informados nos testes, para reproduzir o exemplo da RFC.
     */
    fun cifrar(
        mensagem: ByteArray,
        publicaAparelho: ByteArray,
        segredoAuth: ByteArray,
        par: KeyPair = gerarPar(),
        salt: ByteArray = ByteArray(16).also { aleatorio.nextBytes(it) },
    ): ByteArray {
        require(mensagem.size <= MENSAGEM_MAX) { "a mensagem passa de $MENSAGEM_MAX bytes" }
        require(segredoAuth.size == 16) { "o segredo de autenticação tem 16 bytes" }
        require(salt.size == 16)
        val publicaServidor = publicaEmBytes(par.public)
        val chaves = derivarChaves(par.private, publicaAparelho, publicaAparelho, publicaServidor, segredoAuth, salt)
        val cifra =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(chaves.cek, "AES"), GCMParameterSpec(128, chaves.nonce))
            }
        // 0x02 marca o último (e único) bloco; vem depois da mensagem, antes da cifra
        val bloco = cifra.doFinal(mensagem + byteArrayOf(2))
        val tamanho = TAMANHO_BLOCO
        val cabecalho =
            salt +
                byteArrayOf((tamanho ushr 24).toByte(), (tamanho ushr 16).toByte(), (tamanho ushr 8).toByte(), tamanho.toByte()) +
                byteArrayOf(publicaServidor.size.toByte()) +
                publicaServidor
        return cabecalho + bloco
    }
}
