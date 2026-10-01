package br.com.mesamar.push

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.time.Instant

/**
 * Configuração do Web Push. As chaves VAPID identificam o servidor perante os serviços de push (Google, Mozilla,
 * Apple…). **A chave privada é segredo: só no `.env`**, nunca no repositório. Sem as duas chaves, o push fica
 * desligado e o app avisa o dono (o resto do sistema segue funcionando).
 */
@ConfigurationProperties(prefix = "mesamar.push")
data class PushProperties(
    /** Chave pública VAPID (65 bytes em base64url). Vai para o navegador, que a usa para se inscrever. */
    val publicKey: String = "",
    /** Chave privada VAPID (32 bytes em base64url). Segredo. */
    val privateKey: String = "",
    /** Contato do servidor para o serviço de push (`mailto:` ou `https:`). Vazio usa a URL pública do site. */
    val subject: String = "",
    /** Outros hosts de push aceitos, além dos serviços conhecidos. Só para testes. */
    val hostsExtras: List<String> = emptyList(),
    /** Aceita `http` (sem TLS) e porta qualquer nos hosts extras. Só para testes. */
    val permitirHttp: Boolean = false,
) {
    val habilitado: Boolean get() = publicKey.isNotBlank() && privateKey.isNotBlank()
}

/**
 * Autenticação do servidor perante o serviço de push (**VAPID, RFC 8292**): um JWT `ES256` assinado com a chave
 * privada, para o `endpoint` de cada aparelho. Cabe à chave pública (que o navegador guardou na inscrição) conferir.
 */
class Vapid(
    chavePublica: String,
    chavePrivada: String,
    private val contato: String,
) {
    private val privada: ECPrivateKey = WebPushCripto.privadaDeBytes(Base64Url.decodificar(chavePrivada))
    private val publica: String = chavePublica

    /** Valor do cabeçalho `Authorization` para este destino: `vapid t=<jwt>, k=<chave pública>`. */
    fun autorizacao(
        destino: URI,
        agora: Instant = Instant.now(),
    ): String = "vapid t=${jwt(origem(destino), agora)}, k=$publica"

    /** O JWT: `aud` é a origem do serviço de push, `exp` vale 12 h (o máximo permitido é 24 h) e `sub` é o contato. */
    fun jwt(
        audiencia: String,
        agora: Instant,
    ): String {
        val cabecalho = Base64Url.codificar("""{"typ":"JWT","alg":"ES256"}""".toByteArray())
        val corpo = Base64Url.codificar("""{"aud":"$audiencia","exp":${agora.epochSecond + VALIDADE_S},"sub":"$contato"}""".toByteArray())
        val assinado = "$cabecalho.$corpo"
        // O JWT quer a assinatura "crua" (R || S, 64 bytes), e não o formato DER padrão do Java
        val assinatura =
            Signature
                .getInstance("SHA256withECDSAinP1363Format")
                .apply {
                    initSign(privada)
                    update(assinado.toByteArray())
                }.sign()
        return "$assinado.${Base64Url.codificar(assinatura)}"
    }

    private fun origem(destino: URI): String = "${destino.scheme}://${destino.authority}"

    companion object {
        const val VALIDADE_S = 12 * 60 * 60L
    }
}
