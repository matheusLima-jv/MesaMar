package br.com.mesamar.common

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

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
    private val client = RestClient.create("https://challenges.cloudflare.com")

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
}
