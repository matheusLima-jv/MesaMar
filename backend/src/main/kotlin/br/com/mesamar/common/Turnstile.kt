package br.com.mesamar.common

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.net.http.HttpClient
import java.time.Duration

/**
 * Cloudflare Turnstile (anti-robô, gratuito). Fica desligado enquanto as chaves
 * não forem configuradas; nesse caso o formulário depende só do honeypot e do rate limit.
 */
@ConfigurationProperties(prefix = "mesamar.turnstile")
data class TurnstileProperties(
    val siteKey: String = "",
    val secretKey: String = "",
) {
    val enabled: Boolean get() = siteKey.isNotBlank() && secretKey.isNotBlank()
}

fun interface TurnstileVerifier {
    /** true se o desafio foi resolvido (ou se o Turnstile está desligado). */
    fun verify(
        token: String?,
        remoteIp: String?,
    ): Boolean
}

@Component
class CloudflareTurnstileVerifier(
    private val properties: TurnstileProperties,
) : TurnstileVerifier {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client = cliente("https://challenges.cloudflare.com", CONEXAO, LEITURA)

    override fun verify(
        token: String?,
        remoteIp: String?,
    ): Boolean {
        if (!properties.enabled) return true
        if (token.isNullOrBlank()) return false

        val form =
            LinkedMultiValueMap<String, String>().apply {
                add("secret", properties.secretKey)
                add("response", token)
                remoteIp?.let { add("remoteip", it) }
            }
        return try {
            val result =
                client
                    .post()
                    .uri("/turnstile/v0/siteverify")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body<Map<String, Any?>>()
            result?.get("success") == true
        } catch (e: Exception) {
            // Falha fechada: sem confirmação da Cloudflare, o envio é recusado (a pessoa pode tentar de novo).
            log.warn("Falha ao validar Turnstile: {}", e.message)
            false
        }
    }

    companion object {
        val CONEXAO: Duration = Duration.ofSeconds(5)
        val LEITURA: Duration = Duration.ofSeconds(10)

        /**
         * Cliente com prazo: sem ele, o cliente HTTP padrão do Java espera para sempre, e a Cloudflare lenta prenderia a
         * requisição do formulário (e uma thread do servidor) sem limite.
         */
        internal fun cliente(
            base: String,
            conexao: Duration,
            leitura: Duration,
        ): RestClient {
            val http =
                HttpClient
                    .newBuilder()
                    .connectTimeout(conexao)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build()
            val fabrica = JdkClientHttpRequestFactory(http).apply { setReadTimeout(leitura) }
            return RestClient
                .builder()
                .baseUrl(base)
                .requestFactory(fabrica)
                .build()
        }
    }
}
