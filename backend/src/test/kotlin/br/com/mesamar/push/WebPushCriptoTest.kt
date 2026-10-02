package br.com.mesamar.push

import br.com.mesamar.support.AparelhoDeTeste
import org.junit.jupiter.api.Test
import java.net.URI
import java.security.KeyPair
import java.security.Signature
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Conferência contra o **exemplo e o Apêndice A da RFC 8291** (valores copiados do texto da RFC): cada etapa da
 * derivação de chaves e o corpo final têm de bater byte a byte.
 */
class WebPushCriptoTest {
    private fun b(texto: String) = Base64Url.decodificar(texto.replace(Regex("\\s"), ""))

    // ---- Valores da RFC 8291, seção 5 e Apêndice A ----
    private val plaintext = "When I grow up, I want to be a watermelon"
    private val uaPublica = b("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4")
    private val uaPrivada = b("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94")
    private val asPublica = b("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8")
    private val asPrivada = b("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")
    private val salt = b("DGv6ra1nlYgDCS1FRnbzlw")
    private val autenticacao = b("BTBZMqHH6r4Tts7J_aSIgg")
    private val corpoDaRfc =
        b(
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml" +
                "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT" +
                "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
        )

    private val parDoServidor = KeyPair(WebPushCripto.publicaDeBytes(asPublica), WebPushCripto.privadaDeBytes(asPrivada))

    @Test
    fun `as chaves da RFC sao chaves P-256 validas e a publica bate com a privada`() {
        assertContentEquals(asPublica, WebPushCripto.publicaEmBytes(parDoServidor.public))
        assertContentEquals(asPrivada, WebPushCripto.privadaEmBytes(parDoServidor.private))
        assertEquals(65, uaPublica.size)
    }

    @Test
    fun `cada etapa da derivacao de chaves bate com os valores intermediarios da RFC`() {
        val c = WebPushCripto.derivarChaves(parDoServidor.private, uaPublica, uaPublica, asPublica, autenticacao, salt)

        assertContentEquals(b("kyrL1jIIOHEzg3sM2ZWRHDRB62YACZhhSlknJ672kSs"), c.ecdh, "segredo ECDH")
        assertContentEquals(b("Snr3JMxaHVDXHWJn5wdC52WjpCtd2EIEGBykDcZW32k"), c.prkChave, "PRK_key")
        assertContentEquals(b("S4lYMb_L0FxCeq0WhDx813KgSYqU26kOyzWUdsXYyrg"), c.ikm, "IKM")
        assertContentEquals(b("09_eUZGrsvxChDCGRCdkLiDXrReGOEVeSCdCcPBSJSc"), c.prk, "PRK")
        assertContentEquals(b("oIhVW04MRdy2XN9CiKLxTg"), c.cek, "chave de conteúdo (CEK)")
        assertContentEquals(b("4h_95klXJ5E_qnoN"), c.nonce, "nonce")
    }

    @Test
    fun `o corpo cifrado e identico ao exemplo da RFC byte a byte`() {
        val corpo = WebPushCripto.cifrar(plaintext.toByteArray(), uaPublica, autenticacao, parDoServidor, salt)

        // 86 bytes de cabeçalho + 41 da mensagem + 1 de delimitador + 16 da etiqueta GCM
        assertEquals(86 + plaintext.length + 1 + 16, corpo.size)
        assertContentEquals(corpoDaRfc, corpo)
    }

    @Test
    fun `o aparelho da RFC consegue decifrar o corpo do exemplo`() {
        val aparelho =
            AparelhoDeTeste(KeyPair(WebPushCripto.publicaDeBytes(uaPublica), WebPushCripto.privadaDeBytes(uaPrivada)), autenticacao)

        assertEquals(plaintext, aparelho.decifrar(corpoDaRfc))
    }

    @Test
    fun `ida e volta com chaves novas, e cada mensagem usa um salt e uma chave de servidor novos`() {
        val aparelho = AparelhoDeTeste()
        val mensagem = """{"titulo":"Pedido nº 23 · Guarda-sol 12","corpo":"3 itens"}"""

        val a = WebPushCripto.cifrar(mensagem.toByteArray(), aparelho.publicaBytes, aparelho.segredoAuth)
        val b = WebPushCripto.cifrar(mensagem.toByteArray(), aparelho.publicaBytes, aparelho.segredoAuth)

        assertEquals(mensagem, aparelho.decifrar(a))
        assertEquals(mensagem, aparelho.decifrar(b))
        assertNotEquals(a.toList(), b.toList(), "salt e chave efêmera mudam a cada envio")
        assertEquals(86 + mensagem.toByteArray().size + 1 + 16, a.size, "cabeçalho de 86 + mensagem + delimitador + etiqueta GCM")
    }

    @Test
    fun `mensagem grande demais para um bloco e recusada`() {
        val aparelho = AparelhoDeTeste()

        val cabe = ByteArray(WebPushCripto.MENSAGEM_MAX) { 'a'.code.toByte() }
        assertEquals(4096 + 86 - 0, WebPushCripto.cifrar(cabe, aparelho.publicaBytes, aparelho.segredoAuth).size)
        assertFailsWith<IllegalArgumentException> {
            WebPushCripto.cifrar(ByteArray(WebPushCripto.MENSAGEM_MAX + 1), aparelho.publicaBytes, aparelho.segredoAuth)
        }
    }

    @Test
    fun `chave publica que nao esta na curva ou esta mal formada e recusada`() {
        val aparelho = AparelhoDeTeste()
        val forada = aparelho.publicaBytes.copyOf().also { it[64] = (it[64] + 1).toByte() }

        assertFailsWith<IllegalArgumentException> { WebPushCripto.cifrar(byteArrayOf(1), forada, aparelho.segredoAuth) }
        assertFailsWith<IllegalArgumentException> { WebPushCripto.publicaDeBytes(ByteArray(64)) }
        assertFailsWith<IllegalArgumentException> { WebPushCripto.publicaDeBytes(byteArrayOf(2) + ByteArray(64)) }
        assertFailsWith<IllegalArgumentException> { WebPushCripto.cifrar(byteArrayOf(1), aparelho.publicaBytes, ByteArray(15)) }
    }

    // ---- VAPID (RFC 8292) ----

    @Test
    fun `o JWT do VAPID e ES256 com a origem do servico de push, e a chave publica o confere`() {
        val vapid = Vapid(Base64Url.codificar(asPublica), Base64Url.codificar(asPrivada), "mailto:contato@exemplo.com")
        val agora = Instant.parse("2026-10-01T12:00:00Z")

        val cabecalho = vapid.autorizacao(URI("https://fcm.googleapis.com/fcm/send/abc123"), agora)

        assertTrue(cabecalho.startsWith("vapid t="), cabecalho)
        assertContains(cabecalho, ", k=${Base64Url.codificar(asPublica)}")
        val jwt = cabecalho.removePrefix("vapid t=").substringBefore(", k=")
        val (h, c, s) = jwt.split(".")
        assertEquals("""{"typ":"JWT","alg":"ES256"}""", String(Base64Url.decodificar(h)))
        val corpo = String(Base64Url.decodificar(c))
        assertContains(corpo, """"aud":"https://fcm.googleapis.com"""")
        assertContains(corpo, """"exp":${agora.epochSecond + 12 * 3600}""")
        assertContains(corpo, """"sub":"mailto:contato@exemplo.com"""")
        val assinatura = Base64Url.decodificar(s)
        assertEquals(64, assinatura.size, "R || S, 32 bytes cada")
        val confere =
            Signature.getInstance("SHA256withECDSAinP1363Format").run {
                initVerify(WebPushCripto.publicaDeBytes(asPublica))
                update("$h.$c".toByteArray())
                verify(assinatura)
            }
        assertTrue(confere, "a assinatura tem de bater com a chave pública que o navegador recebeu")
    }

    @Test
    fun `a audiencia leva a porta quando o servico de push usa uma`() {
        val vapid = Vapid(Base64Url.codificar(asPublica), Base64Url.codificar(asPrivada), "https://mesamar.0mega.fun")

        val jwt = vapid.autorizacao(URI("http://localhost:8123/push/x")).removePrefix("vapid t=").substringBefore(", k=")

        assertContains(String(Base64Url.decodificar(jwt.split(".")[1])), """"aud":"http://localhost:8123"""")
    }
}
