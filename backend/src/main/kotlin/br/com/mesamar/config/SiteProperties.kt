package br.com.mesamar.config

import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Configurações públicas do site (landing e páginas institucionais).
 * Todas podem ser definidas por variável de ambiente (ver .env.example).
 */
@Validated
@ConfigurationProperties(prefix = "mesamar.site")
data class SiteProperties(
    /** URL pública, sem barra no final. Usada no canonical, Open Graph e sitemap. */
    @field:Pattern(regexp = "^https?://[^/]+(/.*[^/])?$", message = "deve ser uma URL http(s) sem barra no final")
    val publicBaseUrl: String = "http://localhost:8080",
    /** WhatsApp do botão "Falar no WhatsApp", só dígitos com DDI + DDD (ex.: 5513999998888). Vazio esconde o botão. */
    @field:Pattern(regexp = "^$|^55\\d{10,11}$", message = "deve ter só dígitos no formato 55 + DDD + número")
    val whatsappNumber: String = "",
    /** E-mail de contato para assuntos de privacidade (LGPD). Vazio mostra só o WhatsApp. */
    val contactEmail: String = "",
    /** Token do Cloudflare Web Analytics (sem cookies). Vazio desativa o script. */
    val cloudflareAnalyticsToken: String = "",
) {
    val hasWhatsapp: Boolean get() = whatsappNumber.isNotBlank()
    val hasContactEmail: Boolean get() = contactEmail.isNotBlank()
    val hasAnalytics: Boolean get() = cloudflareAnalyticsToken.isNotBlank()

    fun absoluteUrl(path: String): String = publicBaseUrl + path

    /** Link wa.me com mensagem pré-preenchida (texto codificado para URL). */
    fun whatsappLink(message: String): String =
        "https://wa.me/$whatsappNumber?text=" + java.net.URLEncoder.encode(message, Charsets.UTF_8).replace("+", "%20")
}
